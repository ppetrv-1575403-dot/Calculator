package com.google.android.calculator.domain.usecase

import com.google.android.calculator.domain.repository.PinRepository

class CheckPinUseCase(private val repo: PinRepository) {
    operator fun invoke(input: String): Boolean {
        val savedPin = repo.getPin() ?: return false
        return input == savedPin
    }
}