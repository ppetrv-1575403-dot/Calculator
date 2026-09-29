package com.google.android.calculator.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Безопасное удаление оригиналов файлов после переноса в хранилище.
 * Best-effort: на flash-памяти (eMMC/UFS) из-за wear-leveling гарантия
 * неполная, но восстановление обычными средствами становится практически невозможным.
 */
object SecureDelete {

    private const val CHUNK = 64 * 1024

    /** Перезаписать (random + zeros) и удалить */
    suspend fun shred(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = uriToFile(context, uri)
            if (file != null && file.exists()) {
                shredFile(file)
            } else {
                val size = fileSize(context, uri)
                if (size > 0) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        writePatternStream(out, size, random = true)
                        writePatternStream(out, size, random = false)
                        out.flush()
                    }
                }
                deleteUri(context, uri)
            }
        } catch (e: Exception) {
            FileLogger.e("SecureDelete", "Shred failed for $uri", e)
            false
        }
    }

    /** Просто удалить */
    suspend fun delete(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = uriToFile(context, uri)
            if (file != null && file.exists()) file.delete()
            else deleteUri(context, uri)
        } catch (e: Exception) {
            FileLogger.e("SecureDelete", "Delete failed for $uri", e)
            false
        }
    }

    private fun shredFile(file: File): Boolean {
        return try {
            val size = file.length()
            if (size > 0) {
                RandomAccessFile(file, "rw").use { raf ->
                    writePatternRaf(raf, size, random = true)
                    raf.fd.sync()
                }
                RandomAccessFile(file, "rw").use { raf ->
                    writePatternRaf(raf, size, random = false)
                    raf.fd.sync()
                }
            }
            file.delete()
        } catch (e: Exception) {
            FileLogger.e("SecureDelete", "shredFile failed", e)
            file.delete()
        }
    }

    private fun writePatternRaf(raf: RandomAccessFile, size: Long, random: Boolean) {
        val buf = ByteArray(CHUNK)
        var remaining = size
        raf.seek(0)
        while (remaining > 0) {
            val chunk = minOf(CHUNK.toLong(), remaining).toInt()
            if (random) Random.nextBytes(buf, 0, chunk) else buf.fill(0, 0, chunk)
            raf.write(buf, 0, chunk)
            remaining -= chunk
        }
    }

    private fun writePatternStream(out: OutputStream, size: Long, random: Boolean) {
        val buf = ByteArray(CHUNK)
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(CHUNK.toLong(), remaining).toInt()
            if (random) Random.nextBytes(buf, 0, chunk) else buf.fill(0, 0, chunk)
            out.write(buf, 0, chunk)
            remaining -= chunk
        }
    }

    fun uriToFile(context: Context, uri: Uri): File? = try {
        var path: String? = null
        if (uri.scheme == "file") {
            path = uri.path
        } else {
            context.contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0 && c.moveToFirst()) path = c.getString(idx)
            }
        }
        path?.let { File(it).takeIf { it.exists() } }
    } catch (e: Exception) {
        null
    }

    private fun deleteUri(context: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e2: Exception) {
            false
        }
    }

    private fun fileSize(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }
}