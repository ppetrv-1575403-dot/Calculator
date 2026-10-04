package com.google.android.calculator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

object OfflineMapManager {
    private const val TAG = "OfflineMapManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val SERVER_PORT = 8085

    private var currentServer: MbtilesTileServer? = null
    private var currentRegionId: String? = null

    // === Скачивание теперь через MapDownloadManager ===

    fun downloadRegion(
        context: Context,
        region: OfflineRegionModel,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        MapDownloadManager.start(context, region, forceAll = false)
    }

    fun downloadGraph(
        context: Context,
        region: OfflineRegionModel,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        MapDownloadManager.start(context, region, forceAll = false)
    }

    // === Карты: чтение из MapStorage ===

    fun isRegionDownloaded(context: Context, regionId: String): Boolean =
        MapStorage.hasMap(regionId)

    fun getMbtilesFile(context: Context, regionId: String): File =
        MapStorage.getMapFile(regionId)

    fun getDownloadedRegions(context: Context): List<DownloadedRegionInfo> {
        val baseDir = MapStorage.getBaseDir()
        return baseDir.listFiles()
            ?.filter {
                it.isFile &&
                it.name.startsWith("cache_m_") &&
                it.name.endsWith(".bin") &&
                it.length() > 1024
            }
            ?.mapNotNull { file ->
                val regionId = decode(file.name.removePrefix("cache_m_").removeSuffix(".bin"))
                    ?: return@mapNotNull null
                DownloadedRegionInfo(
                    regionId = regionId,
                    name = OfflineRegions.getRegionById(regionId)?.name ?: regionId,
                    sizeBytes = file.length()
                )
            }
            ?.sortedByDescending { it.sizeBytes }
            ?: emptyList()
    }

    fun deleteRegion(context: Context, regionId: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            stopServer(regionId)
            MapStorage.deleteMap(regionId)
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
            val file = MapStorage.getMapFile(regionId)
            if (!file.exists()) return null
            val server = MbtilesTileServer(context, file, SERVER_PORT)
            if (!server.openDatabase()) return null
            server.start()
            currentServer = server
            currentRegionId = regionId
            FileLogger.d(TAG, "✅ Server started on port $SERVER_PORT for $regionId")
            return "http://localhost:$SERVER_PORT/{z}/{x}/{y}.pbf"
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Failed to start server", e)
            return null
        }
    }

    fun stopServer(regionId: String) {
        if (currentRegionId == regionId) stopCurrentServer()
    }

    private fun stopCurrentServer() {
        try { currentServer?.close() } catch (e: Exception) {
            FileLogger.e(TAG, "Error stopping server", e)
        }
        currentServer = null
        currentRegionId = null
    }

    fun getTileSource(context: Context, regionId: String): String {
        if (isRegionDownloaded(context, regionId)) {
            startServer(context, regionId)?.let { return it }
        }
        return "https://tiles.openfreemap.org/planet"
    }

    // === Графы: делегаты в MapStorage + OfflineRouter ===

    fun isGraphDownloaded(context: Context, regionId: String): Boolean =
        MapStorage.hasGraph(regionId)

    fun getGraphSize(context: Context, regionId: String): Long =
        MapStorage.getGraphSize(regionId)

    fun deleteGraph(context: Context, regionId: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            OfflineRouter.unloadGraph()
            MapStorage.deleteGraph(regionId)
            onComplete()
        } catch (e: Exception) {
            onError(e.message ?: "Ошибка удаления графа")
        }
    }

    // === Кодирование ===

    private fun encode(s: String): String =
        android.util.Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

    private fun decode(s: String): String? = try {
        String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) { null }
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