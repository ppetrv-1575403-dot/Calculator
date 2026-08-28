package com.google.android.calculator.domain.repository

import android.net.Uri
import com.google.android.calculator.domain.model.VaultFile
import java.io.File

interface VaultRepository {
    suspend fun addFile(uri: Uri, originalName: String, mimeType: String): Result<Unit>
    suspend fun getFiles(): List<VaultFile>
    suspend fun decryptToTempFile(encryptedFileName: String): File?
}