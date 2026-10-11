package com.google.android.calculator.data.container

import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class VaultContainer(
    private val containerFile: File,
    private val crypto: CryptoManager
) {
    data class Entry(
        val id: String,
        val originalName: String,
        val mime: String,
        val folderId: String?,
        val offset: Long,
        val length: Long
    )

    private val mutex = Mutex()
    private var index = mutableListOf<Entry>()
    private var lastKnownModified: Long = 0L

    companion object {
        private const val MAGIC = "CVLT"
        private const val VERSION = 2
        private const val SALT_SIZE = 16
        // MAGIC(4) + VERSION(4) + SALT(16) + INDEX_OFFSET(8) = 32
        private const val HEADER_SIZE = 32L
        private const val META_PREFIX = "__meta_"
        private const val BUF = 64 * 1024
    }

    fun path(): String = containerFile.absolutePath
    fun exists(): Boolean = containerFile.exists() && containerFile.length() >= HEADER_SIZE

    fun create(password: String) {
        containerFile.parentFile?.mkdirs()
        val salt = CryptoManager.generateSalt()
        RandomAccessFile(containerFile, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes(MAGIC)
            raf.writeInt(VERSION)
            raf.write(salt)
            raf.writeLong(HEADER_SIZE)
        }
        index = mutableListOf()
        appendIndex(password)
        lastKnownModified = containerFile.lastModified()
    }

    fun open(password: String) {
        val currentModified = containerFile.lastModified()
        if (currentModified != lastKnownModified || index.isEmpty()) {
            RandomAccessFile(containerFile, "r").use { raf ->
                val magic = ByteArray(4); raf.readFully(magic)
                require(String(magic) == MAGIC) { "Файл не является контейнером хранилища" }
                val version = raf.readInt()
                require(version == VERSION) { "Неподдерживаемая версия контейнера: $version" }

                val salt = ByteArray(SALT_SIZE)
                raf.readFully(salt)

                val indexOffset = raf.readLong()
                raf.seek(indexOffset)
                val len = raf.readLong()
                val enc = ByteArray(len.toInt()); raf.readFully(enc)
                index = decryptIndex(enc, password, salt).toMutableList()

                lastKnownModified = currentModified
            }
        }
    }

    fun listEntries(): List<Entry> = index.filter { !it.id.startsWith(META_PREFIX) }
    fun hasEntry(id: String): Boolean = index.any { it.id == id }
    fun findEntry(id: String): Entry? = index.firstOrNull { it.id == id }

    suspend fun putEntry(
        id: String,
        originalName: String,
        mime: String,
        folderId: String?,
        plaintext: File,
        password: String
    ): Result<Unit> = mutex.withLock {
        try {
            val salt = readSalt()
            val offset: Long
            val length: Long
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(raf.length())
                offset = raf.filePointer
                val out: OutputStream = RafOutputStream(raf)
                crypto.encryptStream(out, password, salt).use { cipherOut ->
                    plaintext.inputStream().use { it.copyTo(cipherOut, BUF) }
                }
                length = raf.filePointer - offset
            }
            index.removeAll { it.id == id }
            index.add(Entry(id, originalName, mime, folderId, offset, length))
            appendIndex(password)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "putEntry failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun putEntryBytes(
        id: String,
        originalName: String,
        mime: String,
        folderId: String?,
        bytes: ByteArray,
        password: String
    ): Result<Unit> = mutex.withLock {
        try {
            val salt = readSalt()
            val offset: Long; val length: Long
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(raf.length())
                offset = raf.filePointer
                val out: OutputStream = RafOutputStream(raf)
                crypto.encryptStream(out, password, salt).use { cipherOut ->
                    cipherOut.write(bytes)
                }
                length = raf.filePointer - offset
            }
            index.removeAll { it.id == id }
            index.add(Entry(id, originalName, mime, folderId, offset, length))
            appendIndex(password)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "putEntryBytes failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getEntry(id: String, outPlaintext: File, password: String): Result<Unit> = mutex.withLock {
        val entry = findEntry(id)
            ?: return@withLock Result.failure<Unit>(Exception("Entry not found: $id"))
        try {
            val salt = readSalt()
            RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val sliceStream: InputStream = BoundedInputStream(RafInputStream(raf), entry.length)
                crypto.decryptStream(sliceStream, password, salt).use { plainIn ->
                    outPlaintext.outputStream().buffered().use { out ->
                        plainIn.copyTo(out, BUF)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "getEntry failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getEntryBytes(id: String, password: String): Result<ByteArray> = mutex.withLock {
        val entry = findEntry(id)
            ?: return@withLock Result.failure<ByteArray>(Exception("Entry not found: $id"))
        try {
            val salt = readSalt()
            val bytes = RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val slice = ByteArray(entry.length.toInt())
                raf.readFully(slice)
                slice
            }
            val plain = crypto.decryptToBytes(bytes, password, salt)
            Result.success(plain)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "getEntryBytes failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun removeEntry(id: String, password: String): Result<Unit> = mutex.withLock {
        try {
            index.removeAll { it.id == id }
            appendIndex(password)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun moveEntry(id: String, newFolderId: String?, password: String): Result<Unit> = mutex.withLock {
        try {
            val i = index.indexOfFirst { it.id == id }
            if (i >= 0) index[i] = index[i].copy(folderId = newFolderId)
            appendIndex(password)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun putMetaLines(key: String, lines: List<String>, password: String): Result<Unit> =
        putEntryBytes(META_PREFIX + key, key, "text/plain", null,
            lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8), password)

    suspend fun getMetaLines(key: String, password: String): List<String> =
        getEntryBytes(META_PREFIX + key, password).getOrNull()
            ?.toString(StandardCharsets.UTF_8)?.split("\n")?.filter { it.isNotEmpty() }
            ?: emptyList()

    suspend fun compact(password: String): Result<Unit> = mutex.withLock {
        try {
            val salt = readSalt()
            val tmp = File(containerFile.absolutePath + ".compact")
            val newIndex = mutableListOf<Entry>()
            RandomAccessFile(tmp, "rw").use { out ->
                out.writeBytes(MAGIC); out.writeInt(VERSION); out.write(salt); out.writeLong(HEADER_SIZE)
                val buf = ByteArray(BUF)
                RandomAccessFile(containerFile, "r").use { src ->
                    index.forEach { e ->
                        src.seek(e.offset)
                        val off = out.filePointer
                        var rem = e.length
                        while (rem > 0) {
                            val n = src.read(buf, 0, minOf(BUF.toLong(), rem).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n); rem -= n
                        }
                        newIndex.add(e.copy(offset = off, length = out.filePointer - off))
                    }
                }
                index = newIndex
                val enc = encryptIndex(index, password, salt)
                val off = out.filePointer
                out.writeLong(enc.size.toLong()); out.write(enc)
                out.seek(8); out.writeLong(off)
            }
            containerFile.delete()
            tmp.renameTo(containerFile)
            lastKnownModified = containerFile.lastModified()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // === Внутреннее ===

    private fun readSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        RandomAccessFile(containerFile, "r").use { raf ->
            raf.seek(8) // После MAGIC + VERSION
            raf.readFully(salt)
        }
        return salt
    }

    private fun appendIndex(password: String) {
        val salt = readSalt()
        val enc = encryptIndex(index, password, salt)
        RandomAccessFile(containerFile, "rw").use { raf ->
            raf.seek(raf.length())
            val off = raf.filePointer
            raf.writeLong(enc.size.toLong())
            raf.write(enc)
            raf.seek(8)
            raf.writeLong(off)
        }
        lastKnownModified = containerFile.lastModified()
    }

    private fun encryptIndex(entries: List<Entry>, password: String, salt: ByteArray): ByteArray {
        val plain = entries.joinToString("\n") {
            "${it.id}|||${it.originalName}|||${it.mime}|||${it.folderId ?: ""}|||${it.offset}|||${it.length}"
        }.toByteArray(StandardCharsets.UTF_8)
        val tmp = File.createTempFile("vc_idx", ".enc", containerFile.parentFile)
        try {
            CryptoManager.encryptBytes(plain, tmp, password, salt)
            return tmp.readBytes()
        } finally { tmp.delete() }
    }

    private fun decryptIndex(bytes: ByteArray, password: String, salt: ByteArray): List<Entry> {
        val plain = CryptoManager.decryptToBytes(bytes, password, salt)
        return String(plain, StandardCharsets.UTF_8).split("\n").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("|||")
            if (p.size >= 6) Entry(
                p[0], p[1], p[2],
                p[3].takeIf { it.isNotEmpty() },
                p[4].toLongOrNull() ?: 0L,
                p[5].toLongOrNull() ?: 0L
            ) else null
        }
    }
}

private class RafInputStream(private val raf: RandomAccessFile) : InputStream() {
    override fun read(): Int = raf.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
}

private class RafOutputStream(private val raf: RandomAccessFile) : OutputStream() {
    override fun write(b: Int) { raf.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { raf.write(b, off, len) }
}

private class BoundedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read(); if (b >= 0) remaining--; return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n.toLong()
        return n
    }
    override fun close() { src.close() }
}