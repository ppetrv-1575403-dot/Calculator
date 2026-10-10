package com.google.android.calculator.utils

import android.content.Context

data class OfflineRegion(
    val id: String,
    val name: String,
    val latNorth: Double,
    val lonEast: Double,
    val latSouth: Double,
    val lonWest: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val approximateSizeMb: Int,
    val title: String
) {
    /** Человекочитаемое имя для UI: title, а если пусто — технический name */
    val displayName: String
        get() = title.ifBlank { name }
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

object OfflineRegions {
    private var context: Context? = null
    private var regions: List<OfflineRegion> = emptyList()

    /**
     * Инициализация: загрузить регионы из JSON.
     * Вызывается один раз при запуске приложения.
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.regions = RegionsProvider.loadRegions(this.context!!)
        FileLogger.i("OfflineRegions", "Initialized with ${regions.size} regions")
    }

    fun getRegions(): List<OfflineRegion> = regions

    fun getRegionById(id: String): OfflineRegion? = regions.find { it.id == id }

    fun toOfflineRegionModel(region: OfflineRegion): OfflineRegionModel {
        return OfflineRegionModel(
            id = region.id,
            name = region.name,  // для внутренней логики (имя папки/файлов) — стабильный технический name
            latNorth = region.latNorth,
            lonEast = region.lonEast,
            latSouth = region.latSouth,
            lonWest = region.lonWest,
            minZoom = region.minZoom,
            maxZoom = region.maxZoom
        )
    }

    fun findRegionForPoint(lat: Double, lon: Double): OfflineRegion? {
        return regions.firstOrNull { region ->
            lat in region.latSouth..region.latNorth &&
            lon in region.lonWest..region.lonEast
        }
    }

    /**
     * Обновить список регионов с сервера.
     */
    suspend fun refreshFromServer(): Result<List<OfflineRegion>> {
        val ctx = context ?: return Result.failure(Exception("OfflineRegions not initialized"))
        return try {
            val result = RegionsProvider.refreshRegionsFromServer(ctx)
            if (result.isSuccess) {
                regions = result.getOrNull() ?: emptyList()
                FileLogger.i("OfflineRegions", "Refreshed: ${regions.size} regions")
            }
            result
        } catch (e: Exception) {
            FileLogger.e("OfflineRegions", "Refresh failed", e)
            Result.failure(e)
        }
    }
}