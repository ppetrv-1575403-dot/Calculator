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
import javax.crypto.SecretKey

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
    private var sessionKey: SecretKey? = null

    companion object {
        private const val MAGIC = "CVLT"
        private const val VERSION = 2
        private const val SALT_SIZE = 16
        private const val HEADER_SIZE = 32L // 4 (MAGIC) + 4 (VERSION) + 16 (SALT) + 8 (OFFSET)
        private const val META_PREFIX = "__meta_"
        private const val BUF = 64 * 1024
    }

    private fun requireKey(): SecretKey = sessionKey ?: throw IllegalStateException("Container not unlocked")

    fun path(): String = containerFile.absolutePath
    fun exists(): Boolean = containerFile.exists() && containerFile.length() >= HEADER_SIZE

    fun create(password: String) {
        containerFile.parentFile?.mkdirs()
        val salt = CryptoManager.generateSalt()
        sessionKey = CryptoManager.deriveKey(password, salt)

        RandomAccessFile(containerFile, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes(MAGIC)
            raf.writeInt(VERSION)
            raf.write(salt)
            raf.writeLong(HEADER_SIZE)
        }
        index = mutableListOf()
        appendIndex()
        lastKnownModified = containerFile.lastModified()
        FileLogger.i("VaultContainer", "Created new container: ${containerFile.name}")
    }

    fun open(password: String) {
        val currentModified = containerFile.lastModified()
        FileLogger.d("VaultContainer", "Opening container: ${containerFile.name}, modified=$currentModified, lastKnown=$lastKnownModified, indexEmpty=${index.isEmpty()}")
        
        if (currentModified != lastKnownModified || index.isEmpty()) {
            FileLogger.d("VaultContainer", "Reading index from file")
            RandomAccessFile(containerFile, "r").use { raf ->
                val magic = ByteArray(4); raf.readFully(magic)
                require(String(magic) == MAGIC) { "Файл не является контейнером хранилища" }
                val version = raf.readInt()
                require(version == VERSION) { "Неподдерживаемая версия контейнера: $version" }

                val salt = ByteArray(SALT_SIZE)
                raf.readFully(salt)

                sessionKey = CryptoManager.deriveKey(password, salt)

                val indexOffset = raf.readLong()
                FileLogger.d("VaultContainer", "Index offset: $indexOffset")
                raf.seek(indexOffset)
                val len = raf.readLong()
                FileLogger.d("VaultContainer", "Index length: $len")
                val enc = ByteArray(len.toInt()); raf.readFully(enc)
                index = decryptIndex(enc).toMutableList()
                FileLogger.i("VaultContainer", "Loaded ${index.size} entries from container")

                lastKnownModified = currentModified
            }
        } else {
            FileLogger.d("VaultContainer", "Using cached index (${index.size} entries)")
        }
    }

    fun listEntries(): List<Entry> = index.filter { !it.id.startsWith(META_PREFIX) }
    fun hasEntry(id: String): Boolean = index.any { it.id == id }
    fun findEntry(id: String): Entry? = index.firstOrNull { it.id == id }

    suspend fun putEntry(
        id: String, originalName: String, mime: String, folderId: String?, plaintext: File
    ): Result<Unit> = mutex.withLock {
        try {
            val key = requireKey()
            val salt = readSalt()
            val offset: Long; val length: Long
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(raf.length())
                offset = raf.filePointer
                val out: OutputStream = RafOutputStream(raf)
                crypto.encryptStreamWithKey(out, key, salt).use { cipherOut ->
                    plaintext.inputStream().use { it.copyTo(cipherOut, BUF) }
                }
                length = raf.filePointer - offset
            }
            index.removeAll { it.id == id }
            index.add(Entry(id, originalName, mime, folderId, offset, length))
            appendIndex()
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "putEntry failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun putEntryBytes(
        id: String, originalName: String, mime: String, folderId: String?, bytes: ByteArray
    ): Result<Unit> = mutex.withLock {
        try {
            val key = requireKey()
            val salt = readSalt()
            val offset: Long; val length: Long
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(raf.length())
                offset = raf.filePointer
                val out: OutputStream = RafOutputStream(raf)
                crypto.encryptStreamWithKey(out, key, salt).use { cipherOut ->
                    cipherOut.write(bytes)
                }
                length = raf.filePointer - offset
            }
            index.removeAll { it.id == id }
            index.add(Entry(id, originalName, mime, folderId, offset, length))
            appendIndex()
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "putEntryBytes failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getEntry(id: String, outPlaintext: File): Result<Unit> = mutex.withLock {
        val entry = findEntry(id) ?: return@withLock Result.failure<Unit>(Exception("Entry not found: $id"))
        try {
            val key = requireKey()
            RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val sliceStream: InputStream = BoundedInputStream(RafInputStream(raf), entry.length)
                crypto.decryptStreamWithKey(sliceStream, key).use { plainIn ->
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

    suspend fun getEntryBytes(id: String): Result<ByteArray> = mutex.withLock {
        val entry = findEntry(id) ?: return@withLock Result.failure<ByteArray>(Exception("Entry not found: $id"))
        try {
            val key = requireKey()
            val bytes = RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val slice = ByteArray(entry.length.toInt())
                raf.readFully(slice)
                slice
            }
            val plain = crypto.decryptToBytesWithKey(bytes, key)
            Result.success(plain)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "getEntryBytes failed: $id", e)
            Result.failure(e)
        }
    }

    suspend fun removeEntry(id: String): Result<Unit> = mutex.withLock {
        try {
            index.removeAll { it.id == id }
            appendIndex()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun moveEntry(id: String, newFolderId: String?): Result<Unit> = mutex.withLock {
        try {
            val i = index.indexOfFirst { it.id == id }
            if (i >= 0) index[i] = index[i].copy(folderId = newFolderId)
            appendIndex()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun putMetaLines(key: String, lines: List<String>): Result<Unit> =
        putEntryBytes(META_PREFIX + key, key, "text/plain", null,
            lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))

    suspend fun getMetaLines(key: String): List<String> =
        getEntryBytes(META_PREFIX + key).getOrNull()
            ?.toString(StandardCharsets.UTF_8)?.split("\n")?.filter { it.isNotEmpty() }
            ?: emptyList()

    suspend fun compact(): Result<Unit> = mutex.withLock {
        try {
            val key = requireKey()
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
                val enc = encryptIndex(index, key, salt)
                val off = out.filePointer
                out.writeLong(enc.size.toLong()); out.write(enc)
                out.seek(24) // ⭐ ИСПРАВЛЕНО: было 8, теперь 24 (смещение INDEX_OFFSET в заголовке)
                out.writeLong(off)
            }
            containerFile.delete()
            tmp.renameTo(containerFile)
            lastKnownModified = containerFile.lastModified()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun readSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        RandomAccessFile(containerFile, "r").use { raf ->
            raf.seek(8) // Соль начинается с 8-го байта
            raf.readFully(salt)
        }
        return salt
    }

    private fun appendIndex() {
        val key = requireKey()
        val salt = readSalt()
        val enc = encryptIndex(index, key, salt)
        var off = 0L
        RandomAccessFile(containerFile, "rw").use { raf ->
            raf.seek(raf.length())
            off = raf.filePointer
            raf.writeLong(enc.size.toLong())
            raf.write(enc)
            raf.seek(24) // ⭐ ИСПРАВЛЕНО: было 8, теперь 24 (чтобы не затирать соль!)
            raf.writeLong(off)
        }
        lastKnownModified = containerFile.lastModified()
        FileLogger.d("VaultContainer", "Appended index: ${index.size} entries, offset=$off, size=${enc.size}")
    }

    private fun encryptIndex(entries: List<Entry>, key: SecretKey, salt: ByteArray): ByteArray {
        val plain = entries.joinToString("\n") {
            "${it.id}|||${it.originalName}|||${it.mime}|||${it.folderId ?: ""}|||${it.offset}|||${it.length}"
        }.toByteArray(StandardCharsets.UTF_8)
        val tmp = File.createTempFile("vc_idx", ".enc", containerFile.parentFile)
        try {
            crypto.encryptBytesWithKey(plain, tmp, key, salt)
            return tmp.readBytes()
        } finally { tmp.delete() }
    }

    private fun decryptIndex(bytes: ByteArray): List<Entry> {
        val key = requireKey()
        val plain = crypto.decryptToBytesWithKey(bytes, key)
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