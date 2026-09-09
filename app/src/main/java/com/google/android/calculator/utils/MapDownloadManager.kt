package com.google.android.calculator.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class DownloadState(
    val regionId: String? = null,
    val progress: Float = 0f,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
    val stage: String = "" // "tiles" | "graph" | ""
)

object MapDownloadManager {
    private const val TAG = "MapDownloadManager"
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private var downloadJob: Job? = null

    fun startDownload(context: Context, region: OfflineRegion) {
        if (_state.value.isDownloading) return

        downloadJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            _state.value = DownloadState(regionId = region.id, isDownloading = true)
            try {
                // Этап 1: Тайлы карты
                _state.value = DownloadState(
                    regionId = region.id, progress = 0f,
                    isDownloading = true, stage = "tiles"
                )
                downloadRegionTiles(region) { progress ->
                    _state.value = DownloadState(
                        regionId = region.id, progress = progress * 0.5f,
                        isDownloading = true, stage = "tiles"
                    )
                }

                // Этап 2: Граф дорог (если указан URL)
                if (region.graphHopperUrl.isNotBlank()) {
                    _state.value = DownloadState(
                        regionId = region.id, progress = 0.5f,
                        isDownloading = true, stage = "graph"
                    )
                    downloadGraphHopperData(context, region) { progress ->
                        _state.value = DownloadState(
                            regionId = region.id,
                            progress = 0.5f + progress * 0.5f,
                            isDownloading = true, stage = "graph"
                        )
                    }
                }

                _state.value = DownloadState(completed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _state.value = DownloadState(error = e.message)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = DownloadState()
    }

    fun resetState() {
        _state.value = DownloadState()
    }

    private suspend fun downloadRegionTiles(
        region: OfflineRegion,
        onProgress: (Float) -> Unit
    ) {
        val bbox = region.boundingBox
        val minZoom = region.minZoom
        val maxZoom = region.maxZoom

        val dir = OfflineMapUtils.getOfflineDir()
        dir.mkdirs()
        val zipFile = File(dir, "${region.id}_${System.currentTimeMillis()}.zip")

        var totalTiles = 0L
        for (z in minZoom..maxZoom) {
            val xRange = getXRange(bbox, z)
            val yRange = getYRange(bbox, z)
            totalTiles += (xRange.count().toLong() * yRange.count().toLong())
        }

        if (totalTiles == 0L) return

        var downloaded = 0L

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (z in minZoom..maxZoom) {
                val xRange = getXRange(bbox, z)
                val yRange = getYRange(bbox, z)
                for (x in xRange) {
                    for (y in yRange) {
                        yield()
                        try {
                            val url = "https://tile.openstreetmap.org/$z/$x/$y.png"
                            val bytes = URL(url).readBytes()
                            zos.putNextEntry(ZipEntry("$z/$x/$y.png"))
                            zos.write(bytes)
                            zos.closeEntry()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed tile $z/$x/$y: ${e.message}")
                        }
                        downloaded++
                        onProgress(downloaded.toFloat() / totalTiles.toFloat())
                    }
                }
            }
        }
    }

    private suspend fun downloadGraphHopperData(
        context: Context,
        region: OfflineRegion,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = URL(region.graphHopperUrl)
            val connection = url.openConnection()
            val totalSize = connection.contentLengthLong
            val tempFile = File(context.cacheDir, "${region.id}_graph.ghz.tmp")
            
            var downloaded = 0L
            url.openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        yield()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            onProgress(downloaded.toFloat() / totalSize.toFloat())
                        }
                    }
                }
            }

            // Распаковываем ZIP в директорию графа
            val graphDir = RoutingManager.getGraphDirPath(context).let {
                File(it, region.id)
            }
            graphDir.mkdirs()
            
            if (tempFile.extension == "zip" || isZipFile(tempFile)) {
                unpackZip(tempFile, graphDir)
            } else {
                // Если это не ZIP, просто копируем как есть
                tempFile.renameTo(File(graphDir, "${region.id}.ghz"))
            }
            
            tempFile.delete()
            onProgress(1f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Graph download failed", e)
            throw e
        }
    }

    private fun isZipFile(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4) return@use false
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun unpackZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val outFile = File(targetDir, entry!!.name)
                if (entry!!.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun getXRange(bbox: BoundingBox, zoom: Int): IntRange {
        val n = 1 shl zoom
        val xMin = ((bbox.lonWest + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val xMax = ((bbox.lonEast + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        return minOf(xMin, xMax)..maxOf(xMin, xMax)
    }

    private fun getYRange(bbox: BoundingBox, zoom: Int): IntRange {
        val n = 1 shl zoom
        val latRadTop = Math.toRadians(bbox.latNorth)
        val latRadBottom = Math.toRadians(bbox.latSouth)
        val yTop = ((1.0 - (kotlin.math.ln(kotlin.math.tan(latRadTop) + 1.0 / kotlin.math.cos(latRadTop)) / Math.PI)) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val yBottom = ((1.0 - (kotlin.math.ln(kotlin.math.tan(latRadBottom) + 1.0 / kotlin.math.cos(latRadBottom)) / Math.PI)) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return minOf(yTop, yBottom)..maxOf(yTop, yBottom)
    }
}