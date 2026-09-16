package com.google.android.calculator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import com.google.android.calculator.utils.Constants

object OfflineMapManager {
    private const val TAG = "OfflineMapManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val MBTILES_DIR = "mbtiles"
    private const val SERVER_PORT = 8085

    private var currentServer: MbtilesTileServer? = null
    private var currentRegionId: String? = null

    private fun toast(context: Context, message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    // === Методы для карт ===

    fun downloadRegion(
        context: Context,
        region: OfflineRegionModel,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mainHandler.post { toast(context, "🌐 Начинаем загрузку карт: ${region.name}") }

                val url = URL(getMbtilesUrl(region.id))
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                if (connection.responseCode != 200) {
                    onError("Ошибка HTTP: ${connection.responseCode}")
                    return@launch
                }

                val totalSize = connection.contentLengthLong
                val targetFile = getMbtilesFile(context, region.id)
                targetFile.parentFile?.mkdirs()
                val tempFile = File(targetFile.parentFile, "${region.id}.tmp")

                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalSize > 0) {
                                val progress = downloaded.toFloat() / totalSize.toFloat()
                                mainHandler.post { onProgress(progress) }
                            }
                        }
                    }
                }

                if (tempFile.length() < 1024) {
                    tempFile.delete()
                    onError("Файл слишком мал или повреждён")
                    return@launch
                }

                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)

                mainHandler.post {
                    toast(context, "✅ Карты загружены: ${region.name}")
                    onComplete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post { onError(e.message ?: "Неизвестная ошибка") }
            }
        }
    }

    fun isRegionDownloaded(context: Context, regionId: String): Boolean {
        val file = getMbtilesFile(context, regionId)
        return file.exists() && file.length() > 1024
    }

    fun getMbtilesFile(context: Context, regionId: String): File {
        val dir = File(context.filesDir, MBTILES_DIR)
        dir.mkdirs()
        return File(dir, "${regionId}.mbtiles")
    }

    fun getDownloadedRegions(context: Context): List<DownloadedRegionInfo> {
        val dir = File(context.filesDir, MBTILES_DIR)
        return dir.listFiles()
            ?.filter { it.extension == "mbtiles" && it.length() > 1024 }
            ?.map { file ->
                DownloadedRegionInfo(
                    regionId = file.nameWithoutExtension,
                    name = OfflineRegions.getRegionById(file.nameWithoutExtension)?.name ?: file.nameWithoutExtension,
                    sizeBytes = file.length()
                )
            }
            ?.sortedByDescending { it.sizeBytes }
            ?: emptyList()
    }

    fun deleteRegion(context: Context, regionId: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            stopServer(regionId)
            val file = getMbtilesFile(context, regionId)
            if (file.exists()) file.delete()
            onComplete()
        } catch (e: Exception) {
            onError(e.message ?: "Ошибка удаления")
        }
    }

    // === Локальный сервер для тайлов ===

    fun startServer(context: Context, regionId: String): String? {
        try {
            if (currentServer != null && currentRegionId == regionId) {
                return "http://localhost:$SERVER_PORT/{z}/{x}/{y}.pbf"
            }

            stopCurrentServer()

            val file = getMbtilesFile(context, regionId)
            if (!file.exists()) return null

            val server = MbtilesTileServer(context, file, SERVER_PORT)
            if (!server.openDatabase()) {
                return null
            }
            server.start()

            currentServer = server
            currentRegionId = regionId

            Log.d(TAG, "✅ Server started on port $SERVER_PORT")
            return "http://localhost:$SERVER_PORT/{z}/{x}/{y}.pbf"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start server", e)
            return null
        }
    }

    fun stopServer(regionId: String) {
        if (currentRegionId == regionId) {
            stopCurrentServer()
        }
    }

    private fun stopCurrentServer() {
        try {
            currentServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
        currentServer = null
        currentRegionId = null
    }

    fun getTileSource(context: Context, regionId: String): String {
        if (isRegionDownloaded(context, regionId)) {
            val localUrl = startServer(context, regionId)
            if (localUrl != null) {
                return localUrl
            }
        }
        return "https://tiles.openfreemap.org/planet"
    }

    // === Методы для графов роутинга ===

    fun isGraphDownloaded(context: Context, regionId: String): Boolean {
        return OfflineRouter.isGraphDownloaded(context, regionId)
    }

    fun getGraphSize(context: Context, regionId: String): Long {
        val graphDir = OfflineRouter.getGraphDir(context, regionId)
        return graphDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteGraph(context: Context, regionId: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            OfflineRouter.unloadGraph()
            val graphDir = OfflineRouter.getGraphDir(context, regionId)
            if (graphDir.exists()) {
                graphDir.deleteRecursively()
            }
            onComplete()
        } catch (e: Exception) {
            onError(e.message ?: "Ошибка удаления графа")
        }
    }

    /**
     * Скачивает граф роутинга для региона с умной распаковкой.
     */
    fun downloadGraph(
        context: Context,
        region: OfflineRegionModel,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mainHandler.post { toast(context, "🧭 Скачиваем граф дорог: ${region.name}") }

                val url = URL(getGraphUrl(region.id))
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                if (connection.responseCode != 200) {
                    onError("Ошибка HTTP: ${connection.responseCode}")
                    return@launch
                }

                val totalSize = connection.contentLengthLong
                val tempFile = File(context.cacheDir, "${region.id}_graph.zip")

                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalSize > 0) {
                                val progress = downloaded.toFloat() / totalSize.toFloat()
                                mainHandler.post { onProgress(progress) }
                            }
                        }
                    }
                }

                // Умная распаковка
                val graphDir = OfflineRouter.getGraphDir(context, region.id)
                if (graphDir.exists()) graphDir.deleteRecursively()
                graphDir.mkdirs()
                
                val tempExtractDir = File(context.cacheDir, "${region.id}_extract")
                if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
                tempExtractDir.mkdirs()
                
                // Распаковываем во временную папку
                FileInputStream(tempFile).use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(tempExtractDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                
                // Разворачиваем верхнюю папку, если она одна
                val topLevel = tempExtractDir.listFiles()?.filter { it.isDirectory }
                val sourceDir = if (topLevel != null && topLevel.size == 1 && 
                    (tempExtractDir.listFiles()?.size ?: 0) == 1) {
                    topLevel[0]
                } else {
                    tempExtractDir
                }
                
                // Перемещаем файлы в целевую папку
                sourceDir.copyRecursively(graphDir, overwrite = true)
                
                tempFile.delete()
                tempExtractDir.deleteRecursively()
                
                // Диагностика
                val files = graphDir.walkTopDown().filter { it.isFile }.toList()
                Log.d(TAG, "Extracted ${files.size} files")

                mainHandler.post {
                    toast(context, "✅ Граф дорог загружен: ${region.name}")
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Graph download failed", e)
                mainHandler.post { onError(e.message ?: "Неизвестная ошибка") }
            }
        }
    }

    // === Приватные методы ===

    private fun getMbtilesUrl(regionId: String): String {
        // Замени на свой реальный логин и репозиторий!
        return Constants.getMbtilesUrl(regionId)
    }

    private fun getGraphUrl(regionId: String): String {
        // Замени на свой реальный логин и репозиторий!
        return Constants.getGraphUrl(regionId)
    }
}

data class OfflineRegionModel(
    val id: String,
    val name: String,
    val latNorth: Double,
    val lonEast: Double,
    val latSouth: Double,
    val lonWest: Double,
    val minZoom: Int,
    val maxZoom: Int
)

data class DownloadedRegionInfo(
    val regionId: String,
    val name: String,
    val sizeBytes: Long
)