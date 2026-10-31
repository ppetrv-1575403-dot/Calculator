package com.google.android.calculator1.utils

import android.content.Context
import android.os.Environment
import org.osmdroid.config.Configuration
import java.io.File

data class OfflineMapInfo(
    val file: File,
    val name: String,
    val sizeBytes: Long
)

object OfflineMapUtils {
    private val ARCHIVE_EXTENSIONS = setOf("mbtiles", "zip", "gemf")

    fun initConfiguration(context: Context) {
        Configuration.getInstance().apply {
            load(context.applicationContext, context.applicationContext.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
    }

    fun getOfflineDir(): File {
        val base = Configuration.getInstance().osmdroidBasePath
        return if (base != null && (base.exists() || base.mkdirs())) base
        else File(Environment.getExternalStorageDirectory(), "osmdroid").apply { mkdirs() }
    }

    fun findOfflineArchives(): List<File> {
        val dirs = listOf(
            Configuration.getInstance().osmdroidBasePath,
            File(Environment.getExternalStorageDirectory(), "osmdroid"),
            File(Environment.getExternalStorageDirectory(), "osmdroid/tiles")
        ).distinct()

        return dirs.flatMap { dir ->
            dir?.listFiles()?.filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTENSIONS }?.toList() ?: emptyList()
        }.distinctBy { it.absolutePath }
    }

    fun getOfflineMapsInfo(): List<OfflineMapInfo> {
        return findOfflineArchives().map { file ->
            OfflineMapInfo(
                file = file,
                name = file.nameWithoutExtension,
                sizeBytes = file.length()
            )
        }.sortedByDescending { it.sizeBytes }
    }

    fun deleteOfflineMap(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
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