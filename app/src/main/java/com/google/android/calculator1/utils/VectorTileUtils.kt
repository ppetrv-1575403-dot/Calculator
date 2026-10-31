package com.google.android.calculator1.utils

import android.content.Context
import java.io.File

object VectorTileUtils {
    private const val VECTOR_TILES_DIR = "vector_tiles"

    fun getVectorTilesDir(context: Context): File {
        val dir = File(context.filesDir, VECTOR_TILES_DIR)
        dir.mkdirs()
        return dir
    }

    fun getPmtilesFile(context: Context, regionId: String): File {
        return File(getVectorTilesDir(context), "${regionId}.pmtiles")
    }

    fun isTilesDownloaded(context: Context, regionId: String): Boolean {
        val file = getPmtilesFile(context, regionId)
        return file.exists() && file.length() > 1024
    }

    /**
     * Формирует URI для MapLibre.
     * Локальные файлы: pmtiles:///data/.../file.pmtiles (три слеша)
     * Удалённые: pmtiles://https://...
     */
    fun getTilesUri(context: Context, regionId: String, remoteUrl: String): String {
        val file = getPmtilesFile(context, regionId)
        return if (file.exists() && file.length() > 1024) {
            // Локальный файл: абсолютный путь уже начинается с /
            // Итог: pmtiles:///data/user/0/.../file.pmtiles
            "pmtiles://${file.absolutePath}"
        } else {
            // Удалённый стриминг
            "pmtiles://$remoteUrl"
        }
    }

    fun getTilesSize(context: Context, regionId: String): Long {
        return getPmtilesFile(context, regionId).length()
    }

    fun deleteTiles(context: Context, regionId: String) {
        try {
            val file = getPmtilesFile(context, regionId)
            if (file.exists()) file.delete()
        } catch (e: Exception) { }
    }

    fun getDownloadedRegions(context: Context): List<Pair<String, Long>> {
        return getVectorTilesDir(context).listFiles()
            ?.filter { it.extension == "pmtiles" && it.length() > 1024 }
            ?.map { it.nameWithoutExtension to it.length() }
            ?.sortedByDescending { it.second }
            ?: emptyList()
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}