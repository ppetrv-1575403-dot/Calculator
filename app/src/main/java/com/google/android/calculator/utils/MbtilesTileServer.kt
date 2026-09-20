package com.google.android.calculator.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class MbtilesTileServer(
    private val context: Context,
    private val dbFile: File,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    private var db: SQLiteDatabase? = null
    private var layerNamesLogged = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MbtilesTileServer"
        const val DEFAULT_PORT = 8085
    }

    fun openDatabase(): Boolean {
        try {
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            val countCursor = db?.rawQuery("SELECT count(*) FROM tiles", null)
            val tileCount = if (countCursor?.moveToFirst() == true) countCursor.getLong(0) else 0
            countCursor?.close()

            val minZCursor = db?.rawQuery("SELECT MIN(zoom_level) FROM tiles", null)
            val maxZCursor = db?.rawQuery("SELECT MAX(zoom_level) FROM tiles", null)
            val minZoom = if (minZCursor?.moveToFirst() == true) minZCursor.getInt(0) else -1
            val maxZoom = if (maxZCursor?.moveToFirst() == true) maxZCursor.getInt(0) else -1
            minZCursor?.close()
            maxZCursor?.close()

            val formatCursor = db?.rawQuery("SELECT value FROM metadata WHERE name='format'", null)
            val format = if (formatCursor?.moveToFirst() == true) formatCursor.getString(0) else "unknown"
            formatCursor?.close()

            val typeCursor = db?.rawQuery("SELECT value FROM metadata WHERE name='type'", null)
            val type = if (typeCursor?.moveToFirst() == true) typeCursor.getString(0) else "unknown"
            typeCursor?.close()

            val vlCursor = db?.rawQuery("SELECT value FROM metadata WHERE name='vector_layers'", null)
            val vectorLayers = if (vlCursor?.moveToFirst() == true) vlCursor.getString(0) else "unknown"
            vlCursor?.close()

            val zoomsCursor = db?.rawQuery(
                "SELECT zoom_level, count(*) FROM tiles GROUP BY zoom_level ORDER BY zoom_level",
                null
            )
            val zoomDistribution = buildString {
                while (zoomsCursor?.moveToNext() == true) {
                    append("z${zoomsCursor.getInt(0)}:${zoomsCursor.getInt(1)} ")
                }
            }
            zoomsCursor?.close()

            FileLogger.i(TAG, "MBTiles: $tileCount тайлов, zoom $minZoom-$maxZoom, формат=$format")
            FileLogger.i(TAG, "Zoom dist: $zoomDistribution")
            FileLogger.i(TAG, "Vector layers metadata: $vectorLayers")

            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to open database", e)
            return false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val parts = uri.trimStart('/').split("/")
        if (parts.size < 3) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        val z = parts[0].toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Invalid z")
        val x = parts[1].toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Invalid x")
        val yStr = parts[2].substringBefore(".").substringBefore("?")
        val y = yStr.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Invalid y")

        val tmsY = (1 shl z) - 1 - y

        return try {
            val data = getTile(z, x, tmsY)
            if (data == null) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Tile not found")
            } else {
                // === ДИАГНОСТИКА: извлекаем реальные имена слоёв из PBF (один раз) ===
                if (!layerNamesLogged && z >= 12) {
                    layerNamesLogged = true
                    try {
                        val names = extractPbfLayerNames(data)
                        FileLogger.i(TAG, "🧩 PBF layer names in tile z=$z x=$x: ${names.joinToString(", ")}")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "PBF parse failed", e)
                    }
                }

                val encoding = when {
                    data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte() -> "gzip"
                    data.size > 2 && data[0] == 0x78.toByte() -> "zlib"
                    else -> "raw/pbf"
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.mapbox-vector-tile",
                    ByteArrayInputStream(data),
                    data.size.toLong()
                )
                if (encoding == "gzip") {
                    response.addHeader("Content-Encoding", "gzip")
                }
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Error serving tile", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    private fun getTile(z: Int, x: Int, tmsY: Int): ByteArray? {
        val db = this.db ?: return null
        try {
            val cursor = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), tmsY.toString())
            )
            if (cursor.moveToFirst()) {
                val data = cursor.getBlob(0)
                cursor.close()
                return data
            }
            cursor.close()
            return null
        } catch (e: Exception) {
            FileLogger.e(TAG, "❌ Error getting tile", e)
            return null
        }
    }

    // === Мини-парсер PBF: извлекает имена слоёв из векторного тайла ===

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).readBytes()

    private fun extractPbfLayerNames(data: ByteArray): List<String> {
        val bytes = if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            gunzip(data)
        } else data
        
        val names = mutableListOf<String>()
        var i = 0
        while (i < bytes.size) {
            val tagRes = readVarint(bytes, i) ?: break
            i = tagRes.second
            val field = (tagRes.first shr 3).toInt()
            val wire = (tagRes.first and 7).toInt()
            when (wire) {
                0 -> { val r = readVarint(bytes, i) ?: break; i = r.second }
                2 -> {
                    val lenRes = readVarint(bytes, i) ?: break
                    i = lenRes.second
                    val len = lenRes.first.toInt()
                    if (field == 3 && i + len <= bytes.size) {
                        parseLayerName(bytes, i, i + len)?.let { names.add(it) }
                    }
                    i += len
                }
                5 -> i += 4
                1 -> i += 8
                else -> break
            }
        }
        return names
    }

    private fun parseLayerName(bytes: ByteArray, start: Int, end: Int): String? {
        var i = start
        while (i < end) {
            val tagRes = readVarint(bytes, i) ?: return null
            i = tagRes.second
            val field = (tagRes.first shr 3).toInt()
            val wire = (tagRes.first and 7).toInt()
            when (wire) {
                2 -> {
                    val lenRes = readVarint(bytes, i) ?: return null
                    i = lenRes.second
                    val len = lenRes.first.toInt()
                    if (field == 1 && i + len <= end) {
                        return String(bytes, i, len, Charsets.UTF_8)
                    }
                    i += len
                }
                0 -> { val r = readVarint(bytes, i) ?: return null; i = r.second }
                5 -> i += 4
                1 -> i += 8
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            i++
            result = result or ((b.toLong() and 0x7f) shl shift)
            if (b and 0x80 == 0) return Pair(result, i)
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    fun close() {
        try {
            db?.close()
            stop()
            FileLogger.d(TAG, "Server stopped")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error stopping server", e)
        }
    }
}