package com.google.android.calculator.utils

import android.content.Context
import android.os.Environment
import android.util.Base64
import java.io.File

object MapStorage {

    private const val BASE_DIR = "Documents/.mediacache"
    private const val MAP_PREFIX = "cache_m_"
    private const val GRAPH_PREFIX = "cache_g_"
    private const val MAP_EXT = ".bin"
    private const val GRAPH_READY_MARKER = ".graph_ready"  // ← НОВЫЙ

    fun getBaseDir(): File =
        File(Environment.getExternalStorageDirectory(), BASE_DIR).apply {
            if (mkdirs() || exists()) {
                val nomedia = File(this, ".nomedia")
                if (!nomedia.exists()) try { nomedia.createNewFile() } catch (_: Exception) {}
            }
        }

    fun isWritable(): Boolean = try {
        getBaseDir().canWrite()
    } catch (e: Exception) { false }

    // === Карты ===

    fun getMapFile(regionName: String): File =
        File(getBaseDir(), MAP_PREFIX + encode(regionName) + MAP_EXT)

    fun hasMap(regionName: String): Boolean {
        val f = getMapFile(regionName)
        return f.exists() && f.length() > 1024
    }

    fun deleteMap(regionName: String): Boolean = getMapFile(regionName).delete()

    fun getMapSize(regionName: String): Long =
        try { getMapFile(regionName).length() } catch (e: Exception) { 0L }

    // === Графы ===

    fun getGraphDir(regionName: String): File =
        File(getBaseDir(), GRAPH_PREFIX + encode(regionName)).apply { mkdirs() }

    /**
     * Граф считается загруженным только если есть маркер `.graph_ready`.
     * Маркер создаётся после успешной атомарной распаковки.
     */
    fun hasGraph(regionName: String): Boolean {
        val d = getGraphDir(regionName)
        if (!d.exists() || !d.isDirectory) return false
        val marker = File(d, GRAPH_READY_MARKER)
        return marker.exists()
    }

    /** Создаёт маркер валидности графа */
    fun markGraphReady(regionName: String) {
        val marker = File(getGraphDir(regionName), GRAPH_READY_MARKER)
        marker.createNewFile()
    }

    /** Удаляет маркер (вызывается перед перекачкой) */
    fun unmarkGraphReady(regionName: String) {
        val marker = File(getGraphDir(regionName), GRAPH_READY_MARKER)
        marker.delete()
    }

    fun deleteGraph(regionName: String): Boolean =
        try { getGraphDir(regionName).deleteRecursively() } catch (e: Exception) { false }

    fun getGraphSize(regionName: String): Long = try {
        getGraphDir(regionName).walkTopDown()
            .filter { it.isFile && it.name != GRAPH_READY_MARKER }
            .sumOf { it.length() }
    } catch (e: Exception) { 0L }

    fun deleteRegion(regionName: String) {
        deleteMap(regionName)
        deleteGraph(regionName)
    }

    fun listRegions(): List<String> = try {
        val fromMaps = getBaseDir().listFiles()
            ?.filter { it.isFile && it.name.startsWith(MAP_PREFIX) && it.name.endsWith(MAP_EXT) }
            ?.mapNotNull { decode(it.name.removePrefix(MAP_PREFIX).removeSuffix(MAP_EXT)) }
            ?: emptyList()
        val fromGraphs = getBaseDir().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(GRAPH_PREFIX) }
            ?.mapNotNull { decode(it.name.removePrefix(GRAPH_PREFIX)) }
            ?: emptyList()
        (fromMaps + fromGraphs).distinct().sorted()
    } catch (e: Exception) { emptyList() }

    fun getTotalSize(): Long = try {
        getBaseDir().walkTopDown()
            .filter { it.isFile && it.name != GRAPH_READY_MARKER }
            .sumOf { it.length() }
    } catch (e: Exception) { 0L }

    fun migrateFromOldLocations(context: Context) {
        try {
            val sources = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            sources.forEach { base ->
                val oldMapsDir = File(base, "mbtiles")
                if (oldMapsDir.exists() && oldMapsDir.isDirectory) {
                    oldMapsDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.extension == "mbtiles") {
                            val region = f.nameWithoutExtension
                            val dest = getMapFile(region)
                            if (!dest.exists() || dest.length() < f.length()) {
                                f.copyTo(dest, overwrite = true)
                                FileLogger.i("MapStorage", "Migrated map: ${f.name}")
                            }
                        }
                    }
                    if (oldMapsDir.listFiles()?.isEmpty() == true) oldMapsDir.delete()
                }

                val oldGraphsDir = File(base, "graphs")
                if (oldGraphsDir.exists() && oldGraphsDir.isDirectory) {
                    oldGraphsDir.listFiles()?.forEach { f ->
                        if (f.isDirectory) {
                            val region = f.name
                            val dest = getGraphDir(region)
                            val destFiles = dest.listFiles()?.filter { it.name != GRAPH_READY_MARKER } ?: emptyList()
                            val srcFiles = f.listFiles()?.toList() ?: emptyList()
                            if (destFiles.size < srcFiles.size) {
                                f.copyRecursively(dest, overwrite = true)
                                markGraphReady(region)  // ← предполагаем, что старые графы валидны
                                FileLogger.i("MapStorage", "Migrated graph: ${f.name}")
                            }
                        }
                    }
                    if (oldGraphsDir.listFiles()?.isEmpty() == true) oldGraphsDir.delete()
                }
            }
        } catch (e: Exception) {
            FileLogger.e("MapStorage", "Migration failed", e)
        }
    }

    private fun encode(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(s: String): String? = try {
        String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) { null }
}