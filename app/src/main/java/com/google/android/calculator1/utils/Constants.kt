package com.google.android.calculator1.utils

object Constants {
    const val githubUserName = "ppetrv-1575403-dot"

    private const val BASE_DOWNLOAD_URL = "https://github.com/$githubUserName/map-tiles/releases/download/maps"
    
    // === Методы для графов роутинга ===    /**     * Возвращает URL графа на GitHub.     */    
    fun getMbtilesUrl(regionId: String): String {
        // Пример: https://your-storage.com/maps/gomel_region.mbtiles
        return "$BASE_DOWNLOAD_URL/${regionId}.mbtiles"
    }

    fun getGraphUrl(regionId: String): String {
        // Пример: https://your-storage.com/gomel_region.zip
        return "$BASE_DOWNLOAD_URL/${regionId}_graph.zip"
    }
}

