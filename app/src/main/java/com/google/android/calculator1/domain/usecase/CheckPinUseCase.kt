package com.google.android.calculator1.domain.usecase

import com.google.android.calculator1.domain.repository.PinRepository

class CheckPinUseCase(private val repo: PinRepository) {
    operator fun invoke(input: String): Boolean {
        val savedPin = repo.getPin() ?: return false
        return input == savedPin
    }
}