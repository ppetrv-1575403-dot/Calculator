package com.google.android.calculator.data.repository

import android.content.Context
import android.net.Uri
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.secure.EncryptedMetaStore
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class VaultRepositoryImpl(
    private val context: Context,
    private val crypto: CryptoManager,
    vaultDirName: String = "vault",
    metaFileName: String = "vault_meta.enc",
    metaLegacyFileName: String = "vault_meta.txt"
) : VaultRepository {

    private val vaultDir = File(context.filesDir, vaultDirName).apply { mkdirs() }
    private val metaStore = EncryptedMetaStore(
        context,
        fileName = metaFileName,
        legacyPlaintextFileName = metaLegacyFileName
    )
    private val mutex = Mutex()

    private data class Meta(
        val id: String,
        val originalName: String,
        val encName: String,
        val mime: String,
        val folderId: String? = null
    )

    private fun getPassword(): String {
        return VaultManager.activeSessionPin 
            ?: throw IllegalStateException("Хранилище заблокировано. Введите PIN.")
    }

    private fun loadMeta(): MutableList<Meta> {
        return try {
            metaStore.readLines(getPassword()).mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 4) Meta(
                    p[0], p[1], p[2], p[3],
                    if (p.size >= 5) p[4].takeIf { it.isNotEmpty() } else null
                ) else null
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun saveMeta(list: List<Meta>, password: String) {
        metaStore.writeLines(list.map {
            "${it.id}|||${it.originalName}|||${it.encName}|||${it.mime}|||${it.folderId ?: ""}"
        }, password)
    }

    override suspend fun getFiles() = withContext(Dispatchers.IO) {
        mutex.withLock { loadMeta().map { VaultFile(it.id, it.originalName, it.encName, it.mime, it.folderId) } }
    }

    override suspend fun addFile(uri: Uri, originalName: String, mimeType: String, folderId: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val password = getPassword()
                    val encName = "${System.currentTimeMillis()}.enc"
                    val tempIn = File(context.cacheDir, "vault_temp_in_${System.nanoTime()}")
                    context.contentResolver.openInputStream(uri)?.use { ins -> 
                        tempIn.outputStream().use { out -> ins.copyTo(out) } 
                    } ?: return@withLock Result.failure<Unit>(Exception("Cannot open file"))
                    
                    crypto.encrypt(tempIn, File(vaultDir, encName), password)
                    tempIn.delete()
                    
                    val list = loadMeta()
                    list.add(Meta(System.currentTimeMillis().toString(), originalName, encName, mimeType, folderId))
                    saveMeta(list, password)
                    Result.success(Unit)
                } catch (e: Exception) { Result.failure(e) }
            }
        }

    override suspend fun addFileWithId(uri: Uri, originalName: String, mimeType: String, folderId: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val password = getPassword()
                    val id = System.currentTimeMillis().toString()
                    val encName = "${System.currentTimeMillis()}.enc"
                    val tempIn = File(context.cacheDir, "vault_temp_in_${System.nanoTime()}")
                    context.contentResolver.openInputStream(uri)?.use { ins -> 
                        tempIn.outputStream().use { out -> ins.copyTo(out) } 
                    } ?: return@withLock Result.failure<String>(Exception("Cannot open file"))
                    
                    crypto.encrypt(tempIn, File(vaultDir, encName), password)
                    tempIn.delete()
                    
                    val list = loadMeta()
                    list.add(Meta(id, originalName, encName, mimeType, folderId))
                    saveMeta(list, password)
                    Result.success(id)
                } catch (e: Exception) { Result.failure(e) }
            }
        }

    override suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val password = getPassword()
                val list = loadMeta()
                val f = list.find { it.id == fileId } ?: return@withLock Result.failure<Unit>(Exception("Not found"))
                list.remove(f)
                saveMeta(list, password)
                File(vaultDir, f.encName).delete()
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun decryptToTempFile(encryptedFileName: String) = withContext(Dispatchers.IO) {
        try {
            val password = getPassword()
            val enc = File(vaultDir, encryptedFileName)
            if (!enc.exists()) null
            else File(context.cacheDir, "vault_temp_out_${System.nanoTime()}").also { 
                crypto.decrypt(enc, it, password) 
            }
        } catch (e: Exception) { null }
    }

    // ⭐ Оптимизированный метод: расшифровываем во временный файл и читаем в ByteArray
    override suspend fun decryptToBytes(encryptedFileName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val password = getPassword()
            val enc = File(vaultDir, encryptedFileName)
            if (!enc.exists()) return@withContext null
            
            val tempOut = File.createTempFile("vault_bytes_", ".tmp", context.cacheDir)
            try {
                crypto.decrypt(enc, tempOut, password)
                tempOut.readBytes()
            } finally {
                tempOut.delete() // Сразу удаляем временный файл
            }
        } catch (e: Exception) {
            FileLogger.e("VaultRepository", "decryptToBytes failed", e)
            null
        }
    }

    override suspend fun moveFilesToFolder(fromFolderId: String, toFolderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val password = getPassword()
                val list = loadMeta()
                list.forEachIndexed { i, m -> if (m.folderId == fromFolderId) list[i] = m.copy(folderId = toFolderId) }
                saveMeta(list, password)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun moveFileToFolder(fileId: String, targetFolderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val password = getPassword()
                val list = loadMeta()
                val index = list.indexOfFirst { it.id == fileId }
                if (index < 0) return@withLock Result.failure<Unit>(Exception("File not found"))
                list[index] = list[index].copy(folderId = targetFolderId)
                saveMeta(list, password)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }
}