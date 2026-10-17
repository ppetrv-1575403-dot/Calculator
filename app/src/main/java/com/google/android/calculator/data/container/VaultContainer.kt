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
        val length: Long,
        val encryptionMode: Int = ENCRYPTION_GCM
    )

    private val mutex = Mutex()
    private val stateLock = Any() // ⭐ Блокировка для синхронизации index и lastKnownModified
    private var index = mutableListOf<Entry>()
    private var lastKnownModified: Long = 0L
    private var sessionKey: SecretKey? = null
    
    private val ivCache = mutableMapOf<String, ByteArray>()

    companion object {
        private const val MAGIC = "CVLT"
        private const val VERSION = 3
        private const val SALT_SIZE = 16
        private const val HEADER_SIZE = 32L
        private const val META_PREFIX = "__meta_"
        private const val BUF = 64 * 1024
        
        const val ENCRYPTION_GCM = 0
        const val ENCRYPTION_CTR = 1
        
        fun needsCTR(mimeType: String): Boolean {
            return mimeType.startsWith("video/") || mimeType.startsWith("audio/")
        }
    }

    private fun requireKey(): SecretKey = sessionKey ?: throw IllegalStateException("Container not unlocked")

    fun path(): String = containerFile.absolutePath
    fun exists(): Boolean = containerFile.exists() && containerFile.length() >= HEADER_SIZE

    fun create(password: String) {
        synchronized(stateLock) {
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
            FileLogger.i("VaultContainer", "Created new container v$VERSION: ${containerFile.name}")
        }
    }

    fun open(password: String) {
        synchronized(stateLock) {
            val currentModified = containerFile.lastModified()
            FileLogger.d("VaultContainer", "Opening container: ${containerFile.name}, modified=$currentModified, lastKnown=$lastKnownModified, indexSize=${index.size}")
            
            if (currentModified != lastKnownModified || index.isEmpty()) {
                RandomAccessFile(containerFile, "r").use { raf ->
                    val magic = ByteArray(4); raf.readFully(magic)
                    require(String(magic) == MAGIC) { "Файл не является контейнером хранилища" }
                    val version = raf.readInt()
                    require(version == VERSION) { "Неподдерживаемая версия контейнера: $version" }

                    val salt = ByteArray(SALT_SIZE)
                    raf.readFully(salt)

                    sessionKey = CryptoManager.deriveKey(password, salt)

                    val indexOffset = raf.readLong()
                    raf.seek(indexOffset)
                    val len = raf.readLong()
                    val enc = ByteArray(len.toInt()); raf.readFully(enc)
                    index = decryptIndex(enc).toMutableList()
                    lastKnownModified = currentModified
                    FileLogger.i("VaultContainer", "open loaded ${index.size} entries, inFolders=${index.count { it.folderId != null }}")
                }
            }
        }
    }

    fun listEntries(): List<Entry> = synchronized(stateLock) { index.filter { !it.id.startsWith(META_PREFIX) } }
    fun hasEntry(id: String): Boolean = synchronized(stateLock) { index.any { it.id == id } }
    fun findEntry(id: String): Entry? = synchronized(stateLock) { index.firstOrNull { it.id == id } }

    suspend fun putEntry(
        id: String, originalName: String, mime: String, folderId: String?, plaintext: File
    ): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
            try {
                val key = requireKey()
                val salt = readSalt()
                val useCTR = needsCTR(mime)
                val offset: Long; val length: Long
                
                RandomAccessFile(containerFile, "rw").use { raf ->
                    raf.seek(raf.length())
                    offset = raf.filePointer
                    
                    if (useCTR) {
                        val tempOut = File.createTempFile("ctr_enc_", ".tmp", containerFile.parentFile)
                        try {
                            crypto.encryptCTRWithKey(plaintext, tempOut, key, salt)
                            tempOut.inputStream().use { ins ->
                                val out = RafOutputStream(raf)
                                ins.copyTo(out, BUF)
                            }
                        } finally {
                            tempOut.delete()
                        }
                    } else {
                        val out: OutputStream = RafOutputStream(raf)
                        crypto.encryptStreamWithKey(out, key, salt).use { cipherOut ->
                            plaintext.inputStream().use { it.copyTo(cipherOut, BUF) }
                        }
                    }
                    length = raf.filePointer - offset
                }
                index.removeAll { it.id == id }
                index.add(Entry(id, originalName, mime, folderId, offset, length, if (useCTR) ENCRYPTION_CTR else ENCRYPTION_GCM))
                appendIndex()
                Result.success(Unit)
            } catch (e: Exception) {
                FileLogger.e("VaultContainer", "putEntry failed: $id", e)
                Result.failure(e)
            }
        }
    }

    suspend fun putEntryBytes(
        id: String, originalName: String, mime: String, folderId: String?, bytes: ByteArray
    ): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
            try {
                val key = requireKey()
                val salt = readSalt()
                val useCTR = needsCTR(mime)
                val offset: Long; val length: Long
                
                RandomAccessFile(containerFile, "rw").use { raf ->
                    raf.seek(raf.length())
                    offset = raf.filePointer
                    
                    if (useCTR) {
                        val tempIn = File.createTempFile("ctr_in_", ".tmp", containerFile.parentFile)
                        val tempOut = File.createTempFile("ctr_out_", ".tmp", containerFile.parentFile)
                        try {
                            tempIn.writeBytes(bytes)
                            crypto.encryptCTRWithKey(tempIn, tempOut, key, salt)
                            tempOut.inputStream().use { ins ->
                                val out = RafOutputStream(raf)
                                ins.copyTo(out, BUF)
                            }
                        } finally {
                            tempIn.delete()
                            tempOut.delete()
                        }
                    } else {
                        val out: OutputStream = RafOutputStream(raf)
                        crypto.encryptStreamWithKey(out, key, salt).use { cipherOut ->
                            cipherOut.write(bytes)
                        }
                    }
                    length = raf.filePointer - offset
                }
                index.removeAll { it.id == id }
                index.add(Entry(id, originalName, mime, folderId, offset, length, if (useCTR) ENCRYPTION_CTR else ENCRYPTION_GCM))
                appendIndex()
                Result.success(Unit)
            } catch (e: Exception) {
                FileLogger.e("VaultContainer", "putEntryBytes failed: $id", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getEntry(id: String, outPlaintext: File): Result<Unit> = mutex.withLock {
        val entry = findEntry(id) ?: return@withLock Result.failure<Unit>(Exception("Entry not found: $id"))
        try {
            val key = requireKey()
            RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val sliceStream: InputStream = BoundedInputStream(RafInputStream(raf), entry.length)
                
                if (entry.encryptionMode == ENCRYPTION_CTR) {
                    val tempEnc = File.createTempFile("ctr_dec_", ".tmp", containerFile.parentFile)
                    try {
                        tempEnc.outputStream().use { out -> sliceStream.copyTo(out) }
                        crypto.decryptCTRWithKey(tempEnc, outPlaintext, key)
                    } finally {
                        tempEnc.delete()
                    }
                } else {
                    crypto.decryptStreamWithKey(sliceStream, key).use { plainIn ->
                        outPlaintext.outputStream().buffered().use { out ->
                            plainIn.copyTo(out, BUF)
                        }
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
        // ⭐ Проверяем, не заблокирован ли контейнер
        if (isLocked()) {
            FileLogger.w("VaultContainer", "getEntryBytes: Container is locked")
            return@withLock Result.failure<ByteArray>(Exception("Container is locked"))
        }
        
        val entry = findEntry(id) ?: return@withLock Result.failure<ByteArray>(Exception("Entry not found: $id"))
        try {
            val key = requireKey()
            val bytes = RandomAccessFile(containerFile, "r").use { raf ->
                raf.seek(entry.offset)
                val slice = ByteArray(entry.length.toInt())
                raf.readFully(slice)
                slice
            }
            
            val plain = if (entry.encryptionMode == ENCRYPTION_CTR) {
                val tempEnc = File.createTempFile("ctr_bytes_", ".tmp", containerFile.parentFile)
                try {
                    tempEnc.writeBytes(bytes)
                    val tempOut = File.createTempFile("ctr_out_", ".tmp", containerFile.parentFile)
                    try {
                        crypto.decryptCTRWithKey(tempEnc, tempOut, key)
                        tempOut.readBytes()
                    } finally {
                        tempOut.delete()
                    }
                } finally {
                    tempEnc.delete()
                }
            } else {
                crypto.decryptToBytesWithKey(bytes, key)
            }
            Result.success(plain)
        } catch (e: Exception) {
            FileLogger.e("VaultContainer", "getEntryBytes failed: $id", e)
            Result.failure(e)
        }
    }
    
    fun readEncryptedBytes(offset: Long, length: Int): ByteArray {
        val result = ByteArray(length)
        RandomAccessFile(containerFile, "r").use { raf ->
            raf.seek(offset)
            val read = raf.read(result)
            if (read < length) {
                return result.copyOf(read)
            }
        }
        return result
    }

    fun getDecryptedSize(entry: Entry): Long {
        return if (entry.encryptionMode == ENCRYPTION_CTR) {
            maxOf(0L, entry.length - 32)
        } else {
            entry.length
        }
    }

    fun getEntryIV(entry: Entry): ByteArray {
        synchronized(stateLock) {
            return ivCache.getOrPut(entry.id) {
                val iv = ByteArray(16) // IV_SIZE_CTR
                RandomAccessFile(containerFile, "r").use { raf ->
                    raf.seek(entry.offset + SALT_SIZE)
                    raf.readFully(iv)
                }
                iv
            }
        }
    }

    fun clearIVCache() {
        synchronized(stateLock) {
            ivCache.clear()
        }
    }

    /**
     * ⭐ Проверяет, заблокирован ли контейнер (sessionKey сброшен)
     */
    fun isLocked(): Boolean = sessionKey == null

    /**
     * ⭐ Сбрасывает sessionKey и кэш IV (вызывается при блокировке хранилища)
     * Индекс и lastKnownModified остаются в памяти для быстрого восстановления
     */
    fun clearSessionKey() {
        synchronized(stateLock) {
            sessionKey = null
            ivCache.clear()
            FileLogger.d("VaultContainer", "Session key cleared, index cached: ${index.size} entries")
        }
    }

    /**
     * ⭐ Повторное открытие контейнера с новым паролем (вызывается при разблокировке)
     * Если индекс уже в памяти и файл не изменился, просто обновляет sessionKey
     */
    fun reopen(password: String) {
        synchronized(stateLock) {
            val currentModified = containerFile.lastModified()
            FileLogger.d("VaultContainer", "Reopening container: modified=$currentModified, lastKnown=$lastKnownModified, indexSize=${index.size}")
            
            if (currentModified != lastKnownModified || index.isEmpty()) {
                // Файл изменился или индекс пуст — читаем с диска
                RandomAccessFile(containerFile, "r").use { raf ->
                    val magic = ByteArray(4); raf.readFully(magic)
                    require(String(magic) == MAGIC) { "Файл не является контейнером хранилища" }
                    val version = raf.readInt()
                    require(version == VERSION) { "Неподдерживаемая версия контейнера: $version" }

                    val salt = ByteArray(SALT_SIZE)
                    raf.readFully(salt)

                    sessionKey = CryptoManager.deriveKey(password, salt)

                    val indexOffset = raf.readLong()
                    raf.seek(indexOffset)
                    val len = raf.readLong()
                    val enc = ByteArray(len.toInt()); raf.readFully(enc)
                    index = decryptIndex(enc).toMutableList()
                    lastKnownModified = currentModified
                    FileLogger.i("VaultContainer", "reopen loaded ${index.size} entries from disk")
                }
            } else {
                // Индекс уже в памяти и файл не изменился — только обновляем sessionKey
                val salt = readSalt()
                sessionKey = CryptoManager.deriveKey(password, salt)
                FileLogger.i("VaultContainer", "reopen with cached index: ${index.size} entries (fast)")
            }
        }
    }
    
    fun readDecryptedRangeSync(entry: Entry, offset: Long, length: Int): ByteArray {
        if (entry.encryptionMode != ENCRYPTION_CTR) {
            throw UnsupportedOperationException("Random access only supported for CTR (video/audio)")
        }
        
        // ⭐ Проверяем, не заблокирован ли контейнер
        if (isLocked()) {
            FileLogger.w("VaultContainer", "readDecryptedRangeSync: Container is locked, returning empty")
            return ByteArray(0)
        }
        
        val key = sessionKey ?: return ByteArray(0)
        
        val decryptedSize = getDecryptedSize(entry)
        if (offset >= decryptedSize) {
            return ByteArray(0)
        }
        
        val actualLength = minOf(length.toLong(), decryptedSize - offset).toInt()
        val iv = getEntryIV(entry)
        
        FileLogger.d("VaultContainer", "Reading CTR range: entry=${entry.id}, offset=$offset, length=$actualLength, entryOffset=${entry.offset}")
        
        return crypto.decryptCTRRangeFromFile(containerFile, key, entry.offset, offset, actualLength, iv)
    }
    
    suspend fun removeEntry(id: String): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
            try {
                val removed = index.removeAll { it.id == id }
                FileLogger.d("VaultContainer", "removeEntry: id=$id, removed=$removed, currentSize=${index.size}")
                appendIndex()
                Result.success(Unit)
            } catch (e: Exception) { 
                FileLogger.e("VaultContainer", "removeEntry FAILED: $id", e)
                Result.failure(e) 
            }
        }
    }

    suspend fun moveEntry(id: String, newFolderId: String?): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
            try {
                val i = index.indexOfFirst { it.id == id }
                FileLogger.d("VaultContainer", "moveEntry: id=$id, newFolder=$newFolderId, foundIndex=$i, currentSize=${index.size}")
                if (i >= 0) index[i] = index[i].copy(folderId = newFolderId)
                appendIndex()
                Result.success(Unit)
            } catch (e: Exception) {
                FileLogger.e("VaultContainer", "moveEntry FAILED: $id", e)
                Result.failure(e)
            }
        }
    }

    suspend fun renameEntry(id: String, newName: String): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
            try {
                val i = index.indexOfFirst { it.id == id }
                FileLogger.d("VaultContainer", "renameEntry: id=$id, newName=$newName, foundIndex=$i")
                if (i >= 0) {
                    index[i] = index[i].copy(originalName = newName)
                    appendIndex()
                }
                Result.success(Unit)
            } catch (e: Exception) {
                FileLogger.e("VaultContainer", "renameEntry FAILED: $id", e)
                Result.failure(e)
            }
        }
    }

    suspend fun putMetaLines(key: String, lines: List<String>): Result<Unit> =
        putEntryBytes(META_PREFIX + key, key, "text/plain", null,
            lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))

    suspend fun getMetaLines(key: String): List<String> =
        getEntryBytes(META_PREFIX + key).getOrNull()
            ?.toString(StandardCharsets.UTF_8)?.split("\n")?.filter { it.isNotEmpty() }
            ?: emptyList()

    suspend fun compact(): Result<Unit> = mutex.withLock {
        synchronized(stateLock) {
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
                    out.seek(24)
                    out.writeLong(off)
                }
                containerFile.delete()
                tmp.renameTo(containerFile)
                lastKnownModified = containerFile.lastModified()
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    private fun readSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        RandomAccessFile(containerFile, "r").use { raf ->
            raf.seek(8)
            raf.readFully(salt)
        }
        return salt
    }

    private fun appendIndex() {
        synchronized(stateLock) {
            val key = requireKey()
            val salt = readSalt()
            val enc = encryptIndex(index, key, salt)
            var off = 0L
            RandomAccessFile(containerFile, "rw").use { raf ->
                raf.seek(raf.length())
                off = raf.filePointer
                raf.writeLong(enc.size.toLong())
                raf.write(enc)
                raf.seek(24)
                raf.writeLong(off)
                raf.fd.sync() // ⭐ Принудительный сброс на диск
            }
            lastKnownModified = containerFile.lastModified()
            FileLogger.i("VaultContainer", "appendIndex saved: ${index.size} entries, offset=$off")
        }
    }

    private fun encryptIndex(entries: List<Entry>, key: SecretKey, salt: ByteArray): ByteArray {
        val plain = entries.joinToString("\n") {
            "${it.id}|||${it.originalName}|||${it.mime}|||${it.folderId ?: ""}|||${it.offset}|||${it.length}|||${it.encryptionMode}"
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
                p[5].toLongOrNull() ?: 0L,
                if (p.size >= 7) p[6].toIntOrNull() ?: ENCRYPTION_GCM else ENCRYPTION_GCM
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