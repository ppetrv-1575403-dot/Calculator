// utils/OfflineMapUtils.kt
package com.google.android.calculator.utils
import android.content.Context
import android.os.Environment
import org.osmdroid.config.Configuration
import java.io.File
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
    fun findOfflineArchives(): List<File> = listOf(
        Configuration.getInstance().osmdroidBasePath,
        File(Environment.getExternalStorageDirectory(), "osmdroid"),
        File(Environment.getExternalStorageDirectory(), "osmdroid/tiles")
    ).distinct().flatMap { dir ->
        dir?.listFiles()?.filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTENSIONS }?.toList() ?: emptyList()
    }.distinctBy { it.absolutePath }
}