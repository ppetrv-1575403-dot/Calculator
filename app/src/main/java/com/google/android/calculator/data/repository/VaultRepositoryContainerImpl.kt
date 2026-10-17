package com.google.android.calculator.data.repository

import android.content.Context
import android.net.Uri
import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class VaultRepositoryContainerImpl(
    private val context: Context,
    private val container: VaultContainer
) : VaultRepository {

    private val mutex = Mutex()

    override suspend fun getFiles(): List<VaultFile> = mutex.withLock {
        container.listEntries().map { e ->
            VaultFile(
                id = e.id,
                originalName = e.originalName,
                encryptedFileName = e.id,
                mimeType = e.mime,
                folderId = e.folderId
            )
        }
    }

    override suspend fun addFile(
        uri: Uri,
        originalName: String,
        mimeType: String,
        folderId: String?
    ): Result<Unit> = addFileImpl(uri, originalName, mimeType, folderId).map { }

    override suspend fun addFileWithId(
        uri: Uri,
        originalName: String,
        mimeType: String,
        folderId: String?
    ): Result<String> = addFileImpl(uri, originalName, mimeType, folderId)

    private suspend fun addFileImpl(
        uri: Uri,
        originalName: String,
        mimeType: String,
        folderId: String?
    ): Result<String> = mutex.withLock {
        try {
            val id = System.currentTimeMillis().toString() + "_" + (System.nanoTime() % 1_000_000)
            val temp = File.createTempFile("vault_in_", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    temp.outputStream().use { out -> ins.copyTo(out) }
                } ?: return@withLock Result.failure(Exception("Cannot open file"))

                container.putEntry(id, originalName, mimeType, folderId, temp).getOrThrow()
                Result.success(id)
            } finally {
                temp.delete()
            }
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "addFile failed", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> = mutex.withLock {
        try {
            container.removeEntry(fileId).getOrThrow() // ⭐ Проверяем результат
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "deleteFile failed", e)
            Result.failure(e)
        }
    }

    override suspend fun decryptToTempFile(encryptedFileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val outFile = File.createTempFile("vault_out_", ".tmp", context.cacheDir)
            container.getEntry(encryptedFileName, outFile).getOrThrow()
            outFile
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "decryptToTempFile failed", e)
            null
        }
    }

    override suspend fun decryptToBytes(encryptedFileName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            container.getEntryBytes(encryptedFileName).getOrNull()
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "decryptToBytes failed", e)
            null
        }
    }

    override suspend fun moveFilesToFolder(fromFolderId: String, toFolderId: String?): Result<Unit> = mutex.withLock {
        try {
            val entries = container.listEntries()
            entries.filter { it.folderId == fromFolderId }.forEach {
                container.moveEntry(it.id, toFolderId).getOrThrow() // ⭐ Проверяем результат
            }
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "moveFilesToFolder failed", e)
            Result.failure(e)
        }
    }

    override suspend fun moveFileToFolder(fileId: String, targetFolderId: String?): Result<Unit> = mutex.withLock {
        try {
            container.moveEntry(fileId, targetFolderId).getOrThrow() // ⭐ Проверяем результат
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("VaultContainerRepo", "moveFileToFolder failed", e)
            Result.failure(e)
        }
    }
}