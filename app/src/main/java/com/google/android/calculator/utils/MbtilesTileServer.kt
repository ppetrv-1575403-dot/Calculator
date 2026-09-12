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

class MbtilesTileServer(
    private val context: Context,
    private val dbFile: File,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    private var db: SQLiteDatabase? = null
    private var firstRequestShown = false
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
            // Проверяем, есть ли таблица tiles
            val cursor = db?.rawQuery("SELECT count(*) FROM tiles", null)
            val tileCount = if (cursor?.moveToFirst() == true) cursor.getLong(0) else 0
            cursor?.close()

            // Читаем metadata для диагностики
            val formatCursor = db?.rawQuery("SELECT value FROM metadata WHERE name='format'", null)
            val format = if (formatCursor?.moveToFirst() == true) formatCursor.getString(0) else "unknown"
            formatCursor?.close()

            val typeCursor = db?.rawQuery("SELECT value FROM metadata WHERE name='type'", null)
            val type = if (typeCursor?.moveToFirst() == true) typeCursor.getString(0) else "unknown"
            typeCursor?.close()

            Log.d(TAG, "✅ Database opened: $tileCount tiles, format=$format, type=$type")
            mainHandler.post {
                Toast.makeText(
                    context,
                    "📦 База открыта: $tileCount тайлов\nФормат: $format, тип: $type",
                    Toast.LENGTH_LONG
                ).show()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open database", e)
            mainHandler.post {
                Toast.makeText(context, "❌ Ошибка открытия базы: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "📥 Request: $uri")

        // Показываем тост при первом запросе
        if (!firstRequestShown) {
            firstRequestShown = true
            mainHandler.post {
                Toast.makeText(context, "📥 Первый запрос к серверу: $uri", Toast.LENGTH_LONG).show()
            }
        }

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

        // MBTiles использует TMS-схему (Y перевёрнут)
        val tmsY = (1 shl z) - 1 - y

        return try {
            val data = getTile(z, x, tmsY)
            if (data == null) {
                Log.d(TAG, "Tile not found: $z/$x/$tmsY")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Tile not found")
            } else {
                Log.d(TAG, "✅ Serving tile $z/$x/$tmsY (${data.size} bytes)")
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-protobuf",
                    ByteArrayInputStream(data),
                    data.size.toLong()
                )
                // Определяем сжатие по магическим байтам
                if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                    response.addHeader("Content-Encoding", "gzip")
                }
                response.addHeader("Access-Control-Allow-Origin", "*")
                response
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error serving tile", e)
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
            Log.e(TAG, "❌ Error getting tile", e)
            return null
        }
    }

    fun close() {
        try {
            db?.close()
            stop()
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing", e)
        }
    }
}