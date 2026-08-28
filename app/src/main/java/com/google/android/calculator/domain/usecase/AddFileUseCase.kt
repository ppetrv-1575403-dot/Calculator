package com.google.android.calculator.domain.usecase

import android.net.Uri
import com.google.android.calculator.domain.repository.VaultRepository

class AddFileUseCase(private val repository: VaultRepository) {
    suspend operator fun invoke(uri: Uri, name: String, mimeType: String) =
        repository.addFile(uri, name, mimeType)
}