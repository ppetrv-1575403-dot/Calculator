package com.google.android.calculator.utils

import android.content.Context
import java.io.File

/**
 * Утилита для работы с векторными тайлами (.mbtiles)
 */
object VectorTileUtils {
    private const val VECTOR_TILES_DIR = "vector_tiles"

    /**
     * Возвращает директорию для хранения векторных тайлов
     */
    fun getVectorTilesDir(context: Context): File {
        val dir = File(context.filesDir, VECTOR_TILES_DIR)
        dir.mkdirs()
        return dir
    }

    /**
     * Проверяет, есть ли скачанный пакет для региона
     */
    fun isTilesAvailable(context: Context, regionId: String): Boolean {
        val file = getMbtilesFile(context, regionId)
        return file.exists() && file.length() > 0
    }

    /**
     * Возвращает файл .mbtiles для региона
     */
    fun getMbtilesFile(context: Context, regionId: String): File {
        return File(getVectorTilesDir(context), "${regionId}.mbtiles")
    }

    /**
     * URI для использования в MapLibre
     * Формат: mbtiles://path/to/file.mbtiles
     */
    fun getMbtilesUri(context: Context, regionId: String): String {
        val file = getMbtilesFile(context, regionId)
        return "mbtiles://${file.absolutePath}"
    }

    /**
     * Удаляет пакет тайлов
     */
    fun deleteTiles(context: Context, regionId: String) {
        try {
            getMbtilesFile(context, regionId).delete()
        } catch (e: Exception) { }
    }

    /**
     * Возвращает размер пакета в байтах
     */
    fun getTilesSize(context: Context, regionId: String): Long {
        return getMbtilesFile(context, regionId).length()
    }

    /**
     * Возвращает список всех доступных регионов с тайлами
     */
    fun getAvailableRegions(context: Context): List<String> {
        val dir = getVectorTilesDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "mbtiles" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }
}