package com.google.android.calculator.domain.repository

import android.net.Uri
import com.google.android.calculator.domain.model.VaultFile
import java.io.File

interface VaultRepository {
    suspend fun getFiles(): List<VaultFile>
    
    suspend fun addFile(
        uri: Uri,
        originalName: String,
        mimeType: String,
        folderId: String?
    ): Result<Unit>
    
    suspend fun addFileWithId(
        uri: Uri,
        originalName: String,
        mimeType: String,
        folderId: String?
    ): Result<String>
    
    suspend fun deleteFile(fileId: String): Result<Unit>
    
    suspend fun decryptToTempFile(encryptedFileName: String): File?
    
    // ⭐ Новый метод для оптимизированной загрузки фото в память
    suspend fun decryptToBytes(encryptedFileName: String): ByteArray?
    
    suspend fun moveFilesToFolder(fromFolderId: String, toFolderId: String?): Result<Unit>
    
    suspend fun moveFileToFolder(fileId: String, targetFolderId: String?): Result<Unit>

    suspend fun renameFile(id: String, newName: String): Result<Unit>
}