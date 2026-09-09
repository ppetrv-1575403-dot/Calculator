package com.google.android.calculator.utils

import org.osmdroid.util.BoundingBox

data class OfflineRegion(
    val id: String,
    val name: String,
    val boundingBox: BoundingBox,
    val minZoom: Int = 10,
    val maxZoom: Int = 15,
    val approximateSizeMb: Int,
    // URL для графа дорог GraphHopper (.ghz)
    val graphHopperUrl: String = "",
    // URL для векторных тайлов (.mbtiles)
    val vectorTilesUrl: String = "",
    // Примерный размер векторных тайлов в МБ
    val vectorTilesSizeMb: Int = 0
)

object OfflineRegions {
    fun getRegions(): List<OfflineRegion> = listOf(
        OfflineRegion(
            id = "by_all", name = "🇧🇾 Вся Беларусь",
            boundingBox = BoundingBox(56.2, 32.8, 51.2, 23.1),
            minZoom = 8, maxZoom = 14, approximateSizeMb = 800,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 150
        ),
        OfflineRegion(
            id = "by_minsk_city", name = "Минск",
            boundingBox = BoundingBox(54.05, 27.85, 53.75, 27.35),
            minZoom = 11, maxZoom = 17, approximateSizeMb = 40,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 15
        ),
        OfflineRegion(
            id = "by_minsk_region", name = "Минская область",
            boundingBox = BoundingBox(55.0, 29.0, 53.0, 26.5),
            approximateSizeMb = 120,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 40
        ),
        OfflineRegion(
            id = "by_brest_region", name = "Брестская область",
            boundingBox = BoundingBox(53.0, 26.5, 51.5, 23.0),
            approximateSizeMb = 90,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 35
        ),
        OfflineRegion(
            id = "by_gomel_region", name = "Гомельская область",
            boundingBox = BoundingBox(53.0, 32.0, 51.0, 27.5),
            approximateSizeMb = 100,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 40
        ),
        OfflineRegion(
            id = "by_grodno_region", name = "Гродненская область",
            boundingBox = BoundingBox(54.5, 26.0, 53.0, 23.5),
            approximateSizeMb = 80,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 30
        ),
        OfflineRegion(
            id = "by_vitebsk_region", name = "Витебская область",
            boundingBox = BoundingBox(56.2, 31.0, 54.5, 26.0),
            approximateSizeMb = 95,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 35
        ),
        OfflineRegion(
            id = "by_mogilev_region", name = "Могилёвская область",
            boundingBox = BoundingBox(54.5, 32.5, 52.5, 28.5),
            approximateSizeMb = 90,
            graphHopperUrl = "",
            vectorTilesUrl = "",
            vectorTilesSizeMb = 35
        )
    )

    fun getRegionById(id: String): OfflineRegion? = getRegions().find { it.id == id }

    /**
     * Находит регион, содержащий указанную точку
     */
    fun findRegionForPoint(latitude: Double, longitude: Double): OfflineRegion? {
        return getRegions().firstOrNull { region ->
            val bb = region.boundingBox
            latitude in bb.latSouth..bb.latNorth &&
                    longitude in bb.lonWest..bb.lonEast
        }
    }
}