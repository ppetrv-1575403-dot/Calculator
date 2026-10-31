package com.google.android.calculator1.domain.usecase

import android.net.Uri
import com.google.android.calculator1.domain.repository.VaultRepository

class AddFileUseCase(private val repo: VaultRepository) {
    suspend operator fun invoke(uri: Uri, name: String, mimeType: String, folderId: String? = null) =
        repo.addFile(uri, name, mimeType, folderId)
}