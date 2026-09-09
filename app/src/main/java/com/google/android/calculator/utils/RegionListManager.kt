package com.google.android.calculator.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import java.io.File
import java.net.URL

object RegionListManager {
    // URL для загрузки списка регионов с сервера.
    // Пусто = используется захардкоженный список из OfflineRegions.
    // Пример: "https://raw.githubusercontent.com/username/repo/main/regions.json"
    private const val REGIONS_JSON_URL = ""

    private const val CACHE_FILE_NAME = "regions_cache.json"

    private val _regions = MutableStateFlow<List<OfflineRegion>>(emptyList())
    val regions: StateFlow<List<OfflineRegion>> = _regions

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    suspend fun loadRegions(context: Context) {
        _isLoading.value = true
        _error.value = null
    
        try {
            val loaded: List<OfflineRegion> = when {
                REGIONS_JSON_URL.isNotBlank() -> loadFromServer(context) ?: OfflineRegions.getRegions()
                else -> loadFromCache(context) ?: OfflineRegions.getRegions()
            }
            _regions.value = loaded
        } catch (e: Exception) {
            _error.value = e.message
            _regions.value = loadFromCache(context) ?: OfflineRegions.getRegions()
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun loadFromServer(context: Context): List<OfflineRegion>? = withContext(Dispatchers.IO) {
        try {
            val json = URL(REGIONS_JSON_URL).readText()
            val regions = parseRegionsJson(json)
            if (regions.isNotEmpty()) {
                saveToCache(context, json)
            }
            regions
        } catch (e: Exception) {
            loadFromCache(context)
        }
    }

    private suspend fun loadFromCache(context: Context): List<OfflineRegion>? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.exists()) return@withContext null
            val json = file.readText()
            parseRegionsJson(json)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveToCache(context: Context, json: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            file.writeText(json)
        } catch (e: Exception) { }
    }

    private fun parseRegionsJson(json: String): List<OfflineRegion> {
        val result = mutableListOf<OfflineRegion>()
        try {
            val root = JSONObject(json)
            val regionsArray = root.optJSONArray("regions") ?: return result

            for (i in 0 until regionsArray.length()) {
                val obj = regionsArray.getJSONObject(i)
                val id = obj.optString("id", "region_$i")
                val name = obj.optString("name", "Регион $i")
                val latNorth = obj.optDouble("latNorth")
                val lonEast = obj.optDouble("lonEast")
                val latSouth = obj.optDouble("latSouth")
                val lonWest = obj.optDouble("lonWest")
                val minZoom = obj.optInt("minZoom", 10)
                val maxZoom = obj.optInt("maxZoom", 15)
                val sizeMb = obj.optInt("approximateSizeMb", 50)

                result.add(
                    OfflineRegion(
                        id = id,
                        name = name,
                        boundingBox = BoundingBox(latNorth, lonEast, latSouth, lonWest),
                        minZoom = minZoom,
                        maxZoom = maxZoom,
                        approximateSizeMb = sizeMb
                    )
                )
            }
        } catch (e: Exception) { }
        return result
    }
}