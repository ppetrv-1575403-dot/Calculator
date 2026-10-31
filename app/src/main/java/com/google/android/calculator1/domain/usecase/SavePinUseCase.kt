package com.google.android.calculator1.domain.usecase

import com.google.android.calculator1.domain.repository.PinRepository

class SavePinUseCase(private val repo: PinRepository) {
    operator fun invoke(pin: String): Boolean {
        if (pin.length < 4) return false
        return try {
            repo.savePin(pin)
            repo.setFirstLaunchDone()
            true
        } catch (e: Exception) { false }
    }
}