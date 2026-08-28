package com.google.android.calculator.domain.usecase

import com.google.android.calculator.domain.repository.VaultRepository

class GetFilesUseCase(private val repository: VaultRepository) {
    suspend operator fun invoke() = repository.getFiles()
}