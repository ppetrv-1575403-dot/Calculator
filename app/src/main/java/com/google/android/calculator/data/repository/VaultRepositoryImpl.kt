package com.google.android.calculator.data.repository

import android.content.Context
import android.net.Uri
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.repository.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class VaultRepositoryImpl(
    private val context: Context,
    private val crypto: CryptoManager
) : VaultRepository {

    private val vaultDir = File(context.filesDir, "secure_vault").apply { mkdirs() }
    private val metaFile = File(context.filesDir, "vault_meta.txt")
    private val mutex = Mutex()

    private data class Meta(val id: String, val originalName: String, val encName: String, val mime: String)

    private fun loadMeta(): MutableList<Meta> {
        if (!metaFile.exists()) return mutableListOf()
        return metaFile.readLines().mapNotNull { line ->
            val parts = line.split("|||")
            if (parts.size == 4) Meta(parts[0], parts[1], parts[2], parts[3]) else null
        }.toMutableList()
    }

    private fun saveMeta(meta: List<Meta>) {
        metaFile.writeText(meta.joinToString("\n") { "${it.id}|||${it.originalName}|||${it.encName}|||${it.mime}" })
    }

    override suspend fun addFile(uri: Uri, originalName: String, mimeType: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val encName = "${System.currentTimeMillis()}.enc"
                    val tempIn = File(context.cacheDir, "temp_in_${System.nanoTime()}").apply { createNewFile() }
                    val encFile = File(vaultDir, encName)

                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(tempIn.outputStream()) }
                        ?: throw IllegalStateException("Cannot read URI")
                    crypto.encrypt(tempIn, encFile)
                    tempIn.delete()

                    val id = System.currentTimeMillis().toString()
                    val current = loadMeta()
                    current.add(Meta(id, originalName, encName, mimeType))
                    saveMeta(current)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    override suspend fun getFiles(): List<VaultFile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadMeta().map { VaultFile(it.id, it.originalName, it.encName, it.mime) }
        }
    }

    override suspend fun decryptToTempFile(encryptedFileName: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val encFile = File(vaultDir, encryptedFileName)
                if (!encFile.exists()) return@withContext null
                val tempOut = File(context.cacheDir, "temp_dec_${System.nanoTime()}").apply { createNewFile() }
                crypto.decrypt(encFile, tempOut)
                tempOut
            } catch (e: Exception) {
                null
            }
        }
}