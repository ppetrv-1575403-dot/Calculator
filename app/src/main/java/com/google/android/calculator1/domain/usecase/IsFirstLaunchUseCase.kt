package com.google.android.calculator1.domain.usecase

import com.google.android.calculator1.domain.repository.PinRepository

class IsFirstLaunchUseCase(private val repo: PinRepository) {
    operator fun invoke(): Boolean = repo.isFirstLaunch()
}