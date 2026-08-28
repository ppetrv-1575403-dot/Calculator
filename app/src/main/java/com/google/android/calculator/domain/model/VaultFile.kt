package com.google.android.calculator.domain.model

data class VaultFile(
    val id: String,
    val originalName: String,
    val encryptedFileName: String,
    val mimeType: String
)