package com.google.android.calculator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.google.android.calculator.utils.OfflineRegions

object OfflineMapManager {
    private const val TAG = "OfflineMapManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val MBTILES_DIR = "mbtiles"
    private const val SERVER_PORT = 8085

    // Активные серверы по regionId
    private val activeServers = mutableMapOf<String, MbtilesTileServer>()
    private var currentServer: MbtilesTileServer? = null
    private var currentRegionId: String? = null

    private fun toast(context: Context, message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Скачивает .mbtiles файл для региона.
     */
    fun downloadRegion(
        context: Context,
        region: OfflineRegionModel,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mainHandler.post { toast(context, "🌐 Начинаем загрузку: ${region.name}") }

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
                    toast(context, "✅ Карта «${region.name}» загружена (${targetFile.length() / 1024 / 1024} МБ)")
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

    /**
     * Проверяет, скачан ли регион.
     */
    fun isRegionDownloaded(context: Context, regionId: String): Boolean {
        val file = getMbtilesFile(context, regionId)
        return file.exists() && file.length() > 1024
    }

    /**
     * Возвращает файл .mbtiles для региона.
     */
    fun getMbtilesFile(context: Context, regionId: String): File {
        val dir = File(context.filesDir, MBTILES_DIR)
        dir.mkdirs()
        return File(dir, "${regionId}.mbtiles")
    }

    /**
     * Возвращает список скачанных регионов.
     */
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

    /**
     * Удаляет скачанный регион.
     */
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

    /**
     * Запускает локальный сервер для региона и возвращает локальный источник тайлов.
     */
    fun startServer(context: Context, regionId: String): String? {
        try {
            if (currentServer != null && currentRegionId == regionId) {
                return "http://localhost:$SERVER_PORT/{z}/{x}/{y}.pbf"
            }
    
            stopCurrentServer()
    
            val file = getMbtilesFile(context, regionId)
            if (!file.exists()) {
                Log.e(TAG, "MBTiles file not found: ${file.absolutePath}")
                return null
            }
    
            Log.d(TAG, "Starting server: ${file.absolutePath}, size: ${file.length()}")
    
            val server = MbtilesTileServer(context, file, SERVER_PORT)
            if (!server.openDatabase()) {
                Log.e(TAG, "Failed to open database")
                return null
            }
            server.start()
    
            currentServer = server
            currentRegionId = regionId
    
            // Тестовый запрос к серверу через Handler
            // Тестовый запрос через Handler (без корутин)
mainHandler.postDelayed({
    Thread {
        try {
            val testUrl = URL("http://localhost:$SERVER_PORT/10/586/351.pbf")
            val conn = testUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            val size = if (code == 200) conn.contentLength else 0
            conn.disconnect()
            
            mainHandler.post {
                Toast.makeText(
                    context,
                    "🔍 Тест сервера: HTTP $code, размер $size байт",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.d(TAG, "Test request: HTTP $code, size=$size")
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "❌ Тест сервера: ${e.javaClass.simpleName}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            Log.e(TAG, "Test request failed", e)
        }
    }.start()
}, 2000)
                
            return "http://localhost:$SERVER_PORT/{z}/{x}/{y}.pbf"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start server", e)
            return null
        }
    }
    
    /**
     * Останавливает сервер для региона.
     */
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

    /**
     * Возвращает URL источника тайлов: локальный если скачан, иначе онлайн.
     */
    fun getTileSource(context: Context, regionId: String): String {
        if (isRegionDownloaded(context, regionId)) {
            val localUrl = startServer(context, regionId)
            if (localUrl != null) {
                Log.d(TAG, "Using local tiles: $localUrl")
                return localUrl
            }
        }
        // Fallback на онлайн
        Log.d(TAG, "Using online tiles")
        return "https://tiles.openfreemap.org/planet"
    }

    /**
     * Возвращает URL .mbtiles файла на GitHub.
     */
    private fun getMbtilesUrl(regionId: String): String {
        // Замени на свой реальный логин и репозиторий!
        return OfflineRegions.getMbtilesUrl(regionId)
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