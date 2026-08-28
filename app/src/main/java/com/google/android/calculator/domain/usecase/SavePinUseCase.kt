package com.google.android.calculator.domain.usecase

import com.google.android.calculator.domain.repository.PinRepository

class SavePinUseCase(private val repo: PinRepository) {
    operator fun invoke(pin: String): Result<Unit> {
        return if (pin.length >= 4) {
            repo.savePin(pin)
            repo.setFirstLaunchDone()
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("PIN должен быть минимум 4 цифры"))
        }
    }
}