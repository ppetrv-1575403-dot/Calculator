package com.google.android.calculator.utils

import org.osmdroid.util.BoundingBox
import com.google.android.calculator.utils.Constants

data class OfflineRegion(
    val id: String,
    val name: String,
    val boundingBox: BoundingBox,
    val minZoom: Int = 10,
    val maxZoom: Int = 15,
    val approximateSizeMb: Int,
    val graphHopperUrl: String = "",
    val vectorTilesUrl: String = "",
    val vectorTilesSizeMb: Int = 0
)

object OfflineRegions {
    
    val vectorTilesUrlPrefix = "https://github.com/${Constants.githubUserName}/map-tiles/releases/download/maps"

    /**
     * Возвращает URL .mbtiles файла для региона.
     * Формат: {имя_релиза}/{region_id}.mbtiles
     */
    fun getMbtilesUrl(region: OfflineRegion): String {
        return getMbtilesUrl(region.id)
    }

    fun getMbtilesUrl(regionId: String): String {
        return "https://github.com/${Constants.githubUserName}/map-tiles/releases/download/maps/${regionId}.mbtiles"
    }
    
    fun getRegions(): List<OfflineRegion> = listOf(
        OfflineRegion(
            id = "minsk_region", name = "Минская область",
            boundingBox = BoundingBox(55.0, 29.2, 52.8, 26.3),
            approximateSizeMb = 56,
            vectorTilesUrl = "$vectorTilesUrlPrefix/minsk_region.mbtiles",
        ),
        OfflineRegion(
            id = "gomel_region", name = "Гомельская область",
            boundingBox = BoundingBox(53.1, 32.8, 51.2, 27.5),
            approximateSizeMb = 51,
            vectorTilesUrl = "$vectorTilesUrlPrefix/gomel_region.mbtiles",
            vectorTilesSizeMb = 51
        ),
        OfflineRegion(
            id = "moscow_region", name = "Московская область",
            boundingBox = BoundingBox(56.9, 40.2, 54.2, 35.1),
            approximateSizeMb = 81,
            vectorTilesUrl = "$vectorTilesUrlPrefix/moscow_region.mbtiles",
            vectorTilesSizeMb = 81
        ),
        OfflineRegion(
            id = "leningrad_region", name = "Ленинградская область",
            boundingBox = BoundingBox(61.4, 35.7, 58.4, 27.7),
            approximateSizeMb = 85,
            vectorTilesUrl = "$vectorTilesUrlPrefix/leningrad_region.mbtiles",
            vectorTilesSizeMb = 85
        ),
        OfflineRegion(
            id = "spb", name = "Санкт-Петербург",
            boundingBox = BoundingBox(60.1, 30.6, 59.7, 29.9),
            approximateSizeMb = 50,
            vectorTilesUrl = "$vectorTilesUrlPrefix/spb.mbtiles",
            vectorTilesSizeMb = 50
        )
    )

    fun getRegionById(id: String): OfflineRegion? = getRegions().find { it.id == id }

    fun findRegionForPoint(latitude: Double, longitude: Double): OfflineRegion? {
        return getRegions().firstOrNull { region ->
            val bb = region.boundingBox
            latitude in bb.latSouth..bb.latNorth &&
                    longitude in bb.lonWest..bb.lonEast
        }
    }

    /**
     * Конвертирует OfflineRegion в модель для OfflineMapManager.
     */
    fun toOfflineRegionModel(region: OfflineRegion): OfflineRegionModel {
        return OfflineRegionModel(
            id = region.id,
            name = region.name,
            latNorth = region.boundingBox.latNorth,
            lonEast = region.boundingBox.lonEast,
            latSouth = region.boundingBox.latSouth,
            lonWest = region.boundingBox.lonWest,
            minZoom = region.minZoom,
            maxZoom = region.maxZoom
        )
    }
}