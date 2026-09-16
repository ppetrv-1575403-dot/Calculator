package com.google.android.calculator.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.graphhopper.GraphHopper
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.config.Profile
import java.io.File

/**
 * Оффлайн-роутинг через GraphHopper.
 */
object OfflineRouter {
    private const val TAG = "OfflineRouter"
    private const val GRAPHS_DIR = "graphs"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var graphHopper: GraphHopper? = null
    private var currentRegionId: String? = null

    private fun toast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun getGraphsDir(context: Context): File {
        val dir = File(context.filesDir, GRAPHS_DIR)
        dir.mkdirs()
        return dir
    }

    fun getGraphDir(context: Context, regionId: String): File {
        return File(getGraphsDir(context), regionId)
    }

    fun isGraphDownloaded(context: Context, regionId: String): Boolean {
        val graphDir = getGraphDir(context, regionId)
        if (!graphDir.exists()) return false
        
        val files = graphDir.listFiles() ?: return false
        return files.isNotEmpty()
    }

    fun loadGraph(context: Context, regionId: String): String? {
        try {
            if (graphHopper != null && currentRegionId == regionId) {
                FileLogger.d(TAG, "Graph already loaded for $regionId")
                return null
            }

            unloadGraph()

            val graphDir = getGraphDir(context, regionId)
            if (!graphDir.exists()) {
                FileLogger.e(TAG, "Graph dir not found: ${graphDir.absolutePath}")
                return "Папка графа не найдена"
            }

            val files = graphDir.listFiles()
            if (files.isNullOrEmpty()) {
                FileLogger.e(TAG, "Graph dir is empty")
                return "Папка графа пуста"
            }

            FileLogger.i(TAG, "Loading graph from: ${graphDir.absolutePath}")
            FileLogger.i(TAG, "Files: ${files.map { it.name }.joinToString(", ")}")
            
            toast(context, "📦 Граф: ${files.size} файлов")

            val hopper = GraphHopper()
            hopper.setGraphHopperLocation(graphDir.absolutePath)
            
            // Явно указываем профили, которые должны совпадать с графом
            val carProfile = Profile("car").setVehicle("car").setWeighting("fastest")
            val footProfile = Profile("foot").setVehicle("foot").setWeighting("fastest")
            hopper.setProfiles(listOf(carProfile, footProfile))
            
            var loadException: Exception? = null
            val loaded = try {
                val result = hopper.load()
                FileLogger.i(TAG, "load() returned: $result")
                result
            } catch (e: Exception) {
                loadException = e
                FileLogger.e(TAG, "load() failed", e)
                
                // Если профили не совпадают — удаляем граф и сообщаем
                if (e.message?.contains("Profiles do not match") == true) {
                    FileLogger.w(TAG, "Deleting incompatible graph")
                    graphDir.deleteRecursively()
                    return "Граф несовместим — удалён. Скачайте заново."
                }
                false
            }
            
            val finalLoaded = if (!loaded) {
                try {
                    FileLogger.i(TAG, "Trying importOrLoad()...")
                    val hopper2 = GraphHopper()
                    hopper2.setGraphHopperLocation(graphDir.absolutePath)
                    hopper2.setProfiles(listOf(carProfile, footProfile))
                    hopper2.importOrLoad()
                } catch (e: Exception) {
                    FileLogger.e(TAG, "importOrLoad() also failed", e)
                    loadException = e
                    
                    if (e.message?.contains("Profiles do not match") == true) {
                        graphDir.deleteRecursively()
                        return "Граф несовместим — удалён. Скачайте заново."
                    }
                    false
                }
            } else {
                true
            }

            if (finalLoaded != true) {
                val errorMsg = loadException?.message ?: "Неизвестная ошибка"
                FileLogger.e(TAG, "❌ Graph load failed: $errorMsg")
                return "Ошибка: $errorMsg"
            }

            graphHopper = hopper
            currentRegionId = regionId

            FileLogger.i(TAG, "✅ Graph loaded for $regionId")
            toast(context, "✅ Граф загружен в память")
            
            return null
        } catch (e: NoClassDefFoundError) {
            FileLogger.e(TAG, "❌ NoClassDefFoundError", e)
            return "Отсутствует библиотека: ${e.message}"
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Failed to load graph", e)
            return "Ошибка: ${e.message}"
        }
    }

    fun unloadGraph() {
        try {
            graphHopper?.close()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error closing graph", e)
        }
        graphHopper = null
        currentRegionId = null
    }

    fun getRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        vehicle: String
    ): List<Pair<Double, Double>>? {
        val hopper = graphHopper ?: return null

        return try {
            val request = GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(vehicle)
                .setLocale(java.util.Locale.US)

            val response: GHResponse = hopper.route(request)

            if (response.hasErrors()) {
                FileLogger.e(TAG, "Route errors: ${response.errors}")
                return null
            }

            val path = response.best
            val points = mutableListOf<Pair<Double, Double>>()

            for (i in 0 until path.points.size()) {
                val point = path.points[i]
                points.add(Pair(point.lat, point.lon))
            }

            FileLogger.d(TAG, "✅ Route found: ${points.size} points, distance=${path.distance}m")
            points
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Route failed", e)
            null
        }
    }

    fun getRouteDistance(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        vehicle: String
    ): Double? {
        val hopper = graphHopper ?: return null

        return try {
            val request = GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(vehicle)
                .setLocale(java.util.Locale.US)

            val response = hopper.route(request)

            if (response.hasErrors()) return null

            response.best.distance
        } catch (e: Exception) {
            FileLogger.e(TAG, "Distance calc failed", e)
            null
        }
    }
}