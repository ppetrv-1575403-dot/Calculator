package com.google.android.calculator.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Провайдер списка регионов для оффлайн-карт и графов.
 * Читает из assets/regions.json (fallback) или скачивает с сервера.
 */
object RegionsProvider {

    private const val REGIONS_FILENAME = "regions.json"
    private const val GRAPHS_FILENAME = "graphs.json"

    /** URL для скачивания обновлённого списка (замени на свой) */
    private const val REGIONS_URL = "https://raw.githubusercontent.com/yourusername/yourrepo/main/regions.json"
    private const val GRAPHS_URL = "https://raw.githubusercontent.com/yourusername/yourrepo/main/graphs.json"

    /**
     * Загрузить регионы: сначала из кэша, потом из assets.
     * Если кэш есть — используем его.
     */
    fun loadRegions(context: Context): List<OfflineRegion> {
        return try {
            // 1. Проверяем кэш в MapStorage
            val cachedFile = File(MapStorage.getBaseDir(), REGIONS_FILENAME)
            if (cachedFile.exists()) {
                parseRegionsJson(cachedFile.readText())
            } else {
                // 2. Читаем из assets
                val jsonText = context.assets.open(REGIONS_FILENAME).bufferedReader().use { it.readText() }
                parseRegionsJson(jsonText)
            }
        } catch (e: Exception) {
            FileLogger.e("RegionsProvider", "Failed to load regions", e)
            emptyList()
        }
    }

    /**
     * Загрузить список регионов с графами (объединение из двух JSON).
     */
    fun loadRegionsWithGraphs(context: Context): List<OfflineRegion> {
        return try {
            val regions = loadRegions(context)
            val graphsJson = loadGraphsJson(context)
            
            // Обогащаем регионы информацией о графах
            regions.map { region ->
                val hasGraphConfig = graphsJson.any { it.name == region.id }
                region.copy() // пока просто возвращаем, структура OfflineRegion не меняется
            }
        } catch (e: Exception) {
            FileLogger.e("RegionsProvider", "Failed to load regions with graphs", e)
            emptyList()
        }
    }

    /**
     * Загрузить JSON с графами.
     */
    private fun loadGraphsJson(context: Context): List<GraphConfig> {
        return try {
            val cachedFile = File(MapStorage.getBaseDir(), GRAPHS_FILENAME)
            val jsonText = if (cachedFile.exists()) {
                cachedFile.readText()
            } else {
                context.assets.open(GRAPHS_FILENAME).bufferedReader().use { it.readText() }
            }
            parseGraphsJson(jsonText)
        } catch (e: Exception) {
            FileLogger.e("RegionsProvider", "Failed to load graphs", e)
            emptyList()
        }
    }

    /**
     * Обновить список регионов с сервера (кэшируем в MapStorage).
     */
    suspend fun refreshRegionsFromServer(context: Context): Result<List<OfflineRegion>> {
        return withContext(Dispatchers.IO) {
            try {
                // Скачиваем regions.json
                val regionsText = URL(REGIONS_URL).readText()
                val regionsFile = File(MapStorage.getBaseDir(), REGIONS_FILENAME)
                regionsFile.writeText(regionsText)

                // Скачиваем graphs.json
                val graphsText = URL(GRAPHS_URL).readText()
                val graphsFile = File(MapStorage.getBaseDir(), GRAPHS_FILENAME)
                graphsFile.writeText(graphsText)

                FileLogger.i("RegionsProvider", "Regions refreshed from server")
                Result.success(parseRegionsJson(regionsText))
            } catch (e: Exception) {
                FileLogger.e("RegionsProvider", "Failed to refresh regions", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Парсинг JSON → List<OfflineRegion>
     */
    private fun parseRegionsJson(jsonText: String): List<OfflineRegion> {
        val jsonObj = JSONObject(jsonText)
        val regionsArray = jsonObj.getJSONArray("regions")
        
        return (0 until regionsArray.length()).map { i ->
            val regionObj = regionsArray.getJSONObject(i)
            
            val name = regionObj.getString("name")
            val bbox = regionObj.getString("bbox").split(",").map { it.trim().toDouble() }
            val maxzoom = if (regionObj.has("maxzoom")) regionObj.getInt("maxzoom") else 15
            
            // bbox: "west,south,east,north"
            val lonWest = bbox[0]
            val latSouth = bbox[1]
            val lonEast = bbox[2]
            val latNorth = bbox[3]
            
            // Название для отображения: преобразуем ID в читаемый вид
            val displayName = name.replace("_", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() } 
            }
            
            OfflineRegion(
                id = name,
                name = displayName,
                latNorth = latNorth,
                lonEast = lonEast,
                latSouth = latSouth,
                lonWest = lonWest,
                minZoom = 0,
                maxZoom = maxzoom
            )
        }
    }

    /**
     * Парсинг graphs.json
     */
    private fun parseGraphsJson(jsonText: String): List<GraphConfig> {
        val jsonObj = JSONObject(jsonText)
        val graphsArray = jsonObj.getJSONArray("graphs")
        
        return (0 until graphsArray.length()).map { i ->
            val graphObj = graphsArray.getJSONObject(i)
            GraphConfig(
                name = graphObj.getString("name"),
                bbox = graphObj.getString("bbox"),
                sourceUrl = graphObj.getString("source_url"),
                sourceName = graphObj.getString("source_name")
            )
        }
    }

    private data class GraphConfig(
        val name: String,
        val bbox: String,
        val sourceUrl: String,
        val sourceName: String
    )
}