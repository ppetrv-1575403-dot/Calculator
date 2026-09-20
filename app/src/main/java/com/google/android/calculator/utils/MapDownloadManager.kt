package com.google.android.calculator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadState(
    val regionId: String? = null,
    val progress: Float = 0f,
    val isDownloading: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false
)

object MapDownloadManager {
    private const val TAG = "MapDownloadManager"
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private var downloadJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(context: Context, message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun startDownload(context: Context, region: OfflineRegion) {
        if (_state.value.isDownloading) {
            FileLogger.w("", "⚠️ Загрузка уже идёт")
            return
        }

        // Проверяем, есть ли ссылка на векторные тайлы
        val tilesUrl = region.vectorTilesUrl.takeIf { it.isNotBlank() }
        if (tilesUrl == null) {
            FileLogger.e("", "❌ Для региона «${region.name}» не настроена ссылка на карту")
            return
        }

        FileLogger.w("", "🚀 Начинаем загрузку: ${region.name}")

        downloadJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            _state.value = DownloadState(regionId = region.id, isDownloading = true)
            try {
                downloadPmtiles(context, region, tilesUrl) { progress ->
                    _state.value = DownloadState(
                        regionId = region.id,
                        progress = progress,
                        isDownloading = true
                    )
                }
                _state.value = DownloadState(completed = true)
                FileLogger.i("", "✅ Карта «${region.name}» загружена!")
            } catch (e: CancellationException) {
                _state.value = DownloadState()
                FileLogger.e("", "⏹️ Загрузка отменена")
                throw e
            } catch (e: Exception) {
                //Log.e(TAG, "Download failed", e)
                _state.value = DownloadState(error = e.message)
                FileLogger.e("", "❌ Ошибка: ${e.message}")
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

    /**
     * Скачивает один .pmtiles файл по ссылке.
     */
    private suspend fun downloadPmtiles(
        context: Context,
        region: OfflineRegion,
        url: String,
        onProgress: (Float) -> Unit
    ) {
        // Целевой файл
        val targetFile = VectorTileUtils.getPmtilesFile(context, region.id)
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${region.id}.pmtiles.tmp")

        toast(context, "🌐 Подключаемся к серверу...")

        val connection = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "calculator_vault_app")
            conn.connect()
            conn
        }

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            connection.disconnect()
            throw Exception("Сервер вернул код $responseCode")
        }

        val totalSize = connection.contentLengthLong
        val sizeMb = if (totalSize > 0) totalSize / 1024 / 1024 else 0
        FileLogger.i("", "📦 Размер файла: ${if (sizeMb > 0) "$sizeMb МБ" else "неизвестен"}")

        var downloaded = 0L
        var lastToastPercent = 0

        withContext(Dispatchers.IO) {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        yield()
                        output.write(buffer, 0, read)
                        downloaded += read

                        val progress = if (totalSize > 0) downloaded.toFloat() / totalSize.toFloat() else 0f
                        onProgress(progress)

                        // Тост каждые 10%
                        val percent = (progress * 100).toInt()
                        if (percent >= lastToastPercent + 10) {
                            lastToastPercent = percent
                            val downloadedMb = downloaded / 1024 / 1024
                            //mainHandler.post {
                            //    Toast.makeText(context, "📥 $percent% (${downloadedMb} МБ)", Toast.LENGTH_SHORT).show()
                            //}
                        }
                    }
                    output.flush()
                }
            }
        }

        connection.disconnect()

        // Проверяем что скачалось что-то валидное
        if (tempFile.length() < 1024) {
            tempFile.delete()
            throw Exception("Файл слишком мал или повреждён")
        }

        // Переименовываем временный файл в целевой
        if (targetFile.exists()) targetFile.delete()
        tempFile.renameTo(targetFile)

        val finalMb = targetFile.length() / 1024 / 1024
        FileLogger.i("", "💾 Сохранено: ${targetFile.name} ($finalMb МБ)")
    }
}