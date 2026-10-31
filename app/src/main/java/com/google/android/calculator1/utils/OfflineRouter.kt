package com.google.android.calculator1.utils

import android.content.Context
import com.graphhopper.GraphHopper
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.config.Profile
import java.io.File

object OfflineRouter {
    private const val TAG = "OfflineRouter"

    private var graphHopper: GraphHopper? = null
    private var currentRegionId: String? = null

    fun getGraphsDir(context: Context): File = MapStorage.getBaseDir()

    fun getGraphDir(context: Context, regionId: String): File = MapStorage.getGraphDir(regionId)

    fun isGraphDownloaded(context: Context, regionId: String): Boolean =
        MapStorage.hasGraph(regionId)

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

            val hopper = GraphHopper()
            hopper.setGraphHopperLocation(graphDir.absolutePath)

            val carProfile = Profile("car")
                .setVehicle("car")
                .setWeighting("fastest")
                .setTurnCosts(true)
            val footProfile = Profile("foot")
                .setVehicle("foot")
                .setWeighting("shortest")
                .setTurnCosts(false)

            hopper.setProfiles(listOf(carProfile, footProfile))

            var loadException: Exception? = null
            val loaded = try {
                hopper.load()
            } catch (e: Exception) {
                loadException = e
                FileLogger.e(TAG, "load() failed", e)
                if (e.message?.contains("Profiles do not match") == true ||
                    e.message?.contains("not found") == true) {
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
                    loadException = e
                    FileLogger.e(TAG, "importOrLoad() also failed", e)
                    if (e.message?.contains("Profiles do not match") == true ||
                        e.message?.contains("not found") == true) {
                        graphDir.deleteRecursively()
                        return "Граф несовместим — удалён. Скачайте заново."
                    }
                    false
                }
            } else true

            if (finalLoaded != true) {
                val errorMsg = loadException?.message ?: "Неизвестная ошибка"
                FileLogger.e(TAG, "❌ Graph load failed: $errorMsg")
                return "Ошибка: $errorMsg"
            }

            graphHopper = hopper
            currentRegionId = regionId
            FileLogger.i(TAG, "✅ Graph loaded for $regionId")
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
        try { graphHopper?.close() } catch (e: Exception) {
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
        val hopper = graphHopper ?: run {
            FileLogger.e(TAG, "getRoute called but graphHopper is null")
            return null
        }

        val available = try { hopper.profiles.map { it.name } } catch (e: Exception) { emptyList() }
        val actualVehicle = if (vehicle in available) vehicle else "car"

        return try {
            val request = GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(actualVehicle)
                .setLocale(java.util.Locale.US)
            val response: GHResponse = hopper.route(request)
            if (response.hasErrors()) {
                FileLogger.e(TAG, "Route errors: ${response.errors.map { it.message }}")
                return null
            }
            val path = response.best
            val points = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until path.points.size()) {
                points.add(Pair(path.points[i].lat, path.points[i].lon))
            }
            FileLogger.i(TAG, "✅ Route[$actualVehicle]: ${points.size} pts, ${path.distance.toInt()}m")
            points
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Route failed for '$vehicle'", e)
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
            val actualVehicle = if (hopper.profiles.any { it.name == vehicle }) vehicle else "car"
            val request = GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(actualVehicle)
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