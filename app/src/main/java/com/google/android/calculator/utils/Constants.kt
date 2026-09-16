package com.google.android.calculator.utils

object Constants {
    val githubUserName = "ppetrv-1575403-dot"

    // === Методы для графов роутинга ===    /**     * Возвращает URL графа на GitHub.     */    
    fun getGraphUrl(regionId: String): String {        
        return "https://github.com/$githubUserName/map-tiles/releases/download/maps/${regionId}_graph.zip"
    }

    fun getMbtilesUrl(regionId: String): String {
        return "https://github.com/$githubUserName/map-tiles/releases/download/maps/${regionId}.mbtiles"
    }
}
