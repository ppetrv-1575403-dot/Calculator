package com.google.android.calculator1.domain.usecase

import com.google.android.calculator1.domain.repository.VaultRepository

class GetFilesUseCase(private val repository: VaultRepository) {
    suspend operator fun invoke() = repository.getFiles()
}