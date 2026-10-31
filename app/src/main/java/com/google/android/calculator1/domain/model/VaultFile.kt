package com.google.android.calculator1.domain.model

data class VaultFile(
    val id: String,
    val originalName: String,
    val encryptedFileName: String,
    val mimeType: String,
    val folderId: String? = null
)