package com.google.android.calculator1.utils

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object MapDownloadManager {

    enum class Status { IDLE, DOWNLOADING, PAUSED, COMPLETED, ERROR }
    enum class Phase { MAPS, GRAPH }

    data class State(
        val status: Status = Status.IDLE,
        val phase: Phase? = null,
        val progress: Float = 0f,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<State>>()

    fun getState(regionId: String): StateFlow<State> =
        states.getOrPut(regionId) { MutableStateFlow(State()) }

    fun isActive(regionId: String): Boolean =
        states[regionId]?.value?.status == Status.DOWNLOADING

    fun start(context: Context, region: OfflineRegionModel, forceAll: Boolean = false) {
        val regionId = region.id
        cancelDownload(regionId)

        val state = states.getOrPut(regionId) { MutableStateFlow(State()) }
        if (state.value.status == Status.ERROR) state.value = State()

        val appCtx = context.applicationContext
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val needMaps = forceAll || !MapStorage.hasMap(regionId)
                val needGraph = forceAll || !MapStorage.hasGraph(regionId)

                if (needMaps) {
                    if (forceAll) MapStorage.deleteMap(regionId)
                    state.value = state.value.copy(status = Status.DOWNLOADING, phase = Phase.MAPS, error = null)
                    downloadMap(appCtx, region, state)
                }
                if (needGraph) {
                    MapStorage.unmarkGraphReady(regionId)
                    if (forceAll) MapStorage.deleteGraph(regionId)
                    state.value = state.value.copy(status = Status.DOWNLOADING, phase = Phase.GRAPH, progress = 0f)
                    downloadGraph(appCtx, region, state)
                }
                state.value = state.value.copy(status = Status.COMPLETED, phase = null, progress = 1f)
            } catch (e: CancellationException) {
                state.value = state.value.copy(status = Status.PAUSED)
                throw e
            } catch (e: Exception) {
                FileLogger.e("MapDL", "Download failed for $regionId", e)
                state.value = state.value.copy(status = Status.ERROR, error = e.message ?: "Неизвестная ошибка")
            }
        }
        jobs[regionId] = job
    }

    fun pause(regionId: String) = cancelDownload(regionId)

    private fun cancelDownload(regionId: String) {
        jobs.remove(regionId)?.cancel()
    }

    fun resetError(regionId: String) {
        states[regionId]?.let { s -> if (s.value.status == Status.ERROR) s.value = State() }
    }

    private suspend fun downloadMap(
        context: Context,
        region: OfflineRegionModel,
        state: MutableStateFlow<State>
    ) {
        val url = Constants.getMbtilesUrl(region.id)
        FileLogger.d("MapDL", "📥 Map URL: $url")

        val targetFile = MapStorage.getMapFile(region.id)
        targetFile.parentFile?.mkdirs()
        val tmpFile = File(targetFile.parentFile, "tmp_${encode(region.id)}.bin")
        val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        val code = connection.responseCode
        val totalSize: Long; val append: Boolean
        when (code) {
            200 -> { totalSize = connection.contentLengthLong; tmpFile.delete(); append = false }
            206 -> {
                totalSize = connection.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    ?: (existingBytes + connection.contentLengthLong)
                append = true
            }
            416 -> { tmpFile.delete(); connection.disconnect(); return downloadMap(context, region, state) }
            else -> {
                connection.disconnect()
                throw Exception("Карта недоступна (HTTP $code): $url")
            }
        }

        try {
            connection.inputStream.use { input ->
                FileOutputStream(tmpFile, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = if (append) existingBytes else 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            state.value = state.value.copy(
                                progress = downloaded.toFloat() / totalSize.toFloat(),
                                downloadedBytes = downloaded, totalBytes = totalSize
                            )
                        }
                    }
                }
            }
        } finally { connection.disconnect() }

        if (tmpFile.length() < 1024) { tmpFile.delete(); throw Exception("Файл карты слишком мал или повреждён") }
        if (targetFile.exists()) targetFile.delete()
        tmpFile.renameTo(targetFile)
        FileLogger.i("MapDL", "✅ Map downloaded: ${tmpFile.length()} bytes")
    }

    private suspend fun downloadGraph(
        context: Context,
        region: OfflineRegionModel,
        state: MutableStateFlow<State>
    ) {
        val url = Constants.getGraphUrl(region.id)
        FileLogger.d("MapDL", "📥 Graph URL: $url")

        val zipTmp = File(context.cacheDir, "tmp_${encode(region.id)}_graph.zip")
        val existingBytes = if (zipTmp.exists()) zipTmp.length() else 0L

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
        }

        val code = connection.responseCode
        val totalSize: Long; val append: Boolean
        when (code) {
            200 -> { totalSize = connection.contentLengthLong; zipTmp.delete(); append = false }
            206 -> {
                totalSize = connection.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    ?: (existingBytes + connection.contentLengthLong)
                append = true
            }
            416 -> { zipTmp.delete(); connection.disconnect(); return downloadGraph(context, region, state) }
            else -> {
                connection.disconnect()
                throw Exception("Граф недоступен (HTTP $code): $url")
            }
        }

        try {
            connection.inputStream.use { input ->
                FileOutputStream(zipTmp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = if (append) existingBytes else 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0) {
                            state.value = state.value.copy(
                                progress = downloaded.toFloat() / totalSize.toFloat(),
                                downloadedBytes = downloaded, totalBytes = totalSize
                            )
                        }
                    }
                }
            }
        } finally { connection.disconnect() }

        val readyDir = File(context.cacheDir, "ready_${encode(region.id)}_graph")
        if (readyDir.exists()) readyDir.deleteRecursively()
        readyDir.mkdirs()

        try {
            ZipInputStream(zipTmp.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    val outFile = File(readyDir, entry.name)
                    if (entry.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val topLevel = readyDir.listFiles()?.filter { it.isDirectory }
            val sourceDir = if (topLevel != null && topLevel.size == 1 &&
                (readyDir.listFiles()?.size ?: 0) == 1) topLevel[0] else readyDir

            val finalDir = MapStorage.getGraphDir(region.id)
            val leftoverTmp = File(finalDir.parentFile, "tmp_${finalDir.name}")
            if (leftoverTmp.exists()) leftoverTmp.deleteRecursively()

            sourceDir.copyRecursively(leftoverTmp, overwrite = true)

            val files = leftoverTmp.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) {
                leftoverTmp.deleteRecursively()
                throw Exception("Граф распакован, но папка пуста")
            }

            if (finalDir.exists()) finalDir.deleteRecursively()
            if (!leftoverTmp.renameTo(finalDir)) {
                leftoverTmp.copyRecursively(finalDir, overwrite = true)
                leftoverTmp.deleteRecursively()
            }

            MapStorage.markGraphReady(region.id)
            FileLogger.i("MapDL", "✅ Graph extracted: ${files.size} files from $url")
        } finally {
            zipTmp.delete()
            readyDir.deleteRecursively()
        }
    }

    private fun encode(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}