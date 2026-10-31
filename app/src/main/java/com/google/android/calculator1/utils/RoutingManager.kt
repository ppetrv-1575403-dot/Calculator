package com.google.android.calculator1.utils

import android.content.Context
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.ResponsePath
import com.graphhopper.config.Profile
import com.graphhopper.util.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Менеджер маршрутизации.
 * Использует GraphHopper (оффлайн) если доступен граф, иначе возвращает null.
 */
object RoutingManager {
    private const val TAG = "RoutingManager"
    private const val GRAPHS_DIR = "gh_graphs"
    private const val PROFILE = "foot"

    private var hopper: GraphHopper? = null
    private var loadedRegionId: String? = null

    /**
     * Проверяет, доступен ли оффлайн-роутинг для региона
     */
    fun isGraphAvailable(context: Context, regionId: String): Boolean {
        val graphDir = getGraphDir(context, regionId)
        return graphDir.exists() && graphDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Загружает граф для региона (если не загружен ранее)
     * Длительная операция — вызывать в фоне
     */
    suspend fun loadGraph(context: Context, regionId: String): Boolean = withContext(Dispatchers.IO) {
        if (loadedRegionId == regionId && hopper != null) return@withContext true

        val graphDir = getGraphDir(context, regionId)
        if (!graphDir.exists() || graphDir.listFiles()?.isEmpty() != false) {
            Log.w(TAG, "Graph not found for region $regionId")
            return@withContext false
        }

        try {
            val h = GraphHopper()
            h.setProfiles(Profile(PROFILE))
            h.graphHopperLocation = graphDir.absolutePath
            h.importOrLoad()
            
            hopper = h
            loadedRegionId = regionId
            Log.i(TAG, "Graph loaded for region $regionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load graph for $regionId", e)
            hopper = null
            loadedRegionId = null
            false
        }
    }

    /**
     * Строит маршрут оффлайн. Возвращает null если граф не загружен.
     */
    suspend fun routeOffline(
        context: Context,
        regionId: String,
        from: GeoPoint,
        to: GeoPoint
    ): ResponsePath? = withContext(Dispatchers.IO) {
        if (!isGraphAvailable(context, regionId)) return@withContext null
        if (loadedRegionId != regionId) {
            if (!loadGraph(context, regionId)) return@withContext null
        }

        val h = hopper ?: return@withContext null

        try {
            val request = GHRequest(
                from.latitude, from.longitude,
                to.latitude, to.longitude
            ).setProfile(PROFILE)
            
            val response = h.route(request)
            if (response.hasErrors() || response.all.isEmpty()) {
                Log.w(TAG, "Routing failed: ${response.errors}")
                return@withContext null
            }
            response.best
        } catch (e: Exception) {
            Log.e(TAG, "Routing exception", e)
            null
        }
    }

    /**
     * Конвертирует ResponsePath GraphHopper в список GeoPoint для отрисовки
     */
    fun pathToGeoPoints(path: ResponsePath): List<GeoPoint> {
        return path.points.map { GeoPoint(it.lat, it.lon) }
    }

    private fun getGraphDir(context: Context, regionId: String): File {
        val dir = File(context.filesDir, "$GRAPHS_DIR/$regionId")
        dir.mkdirs()
        return dir
    }

    /**
     * Удаляет граф для региона
     */
    fun deleteGraph(context: Context, regionId: String) {
        try {
            val dir = getGraphDir(context, regionId)
            dir.deleteRecursively()
            if (loadedRegionId == regionId) {
                hopper = null
                loadedRegionId = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete graph", e)
        }
    }

    fun getGraphDirPath(context: Context): File {
        return File(context.filesDir, GRAPHS_DIR)
    }
}