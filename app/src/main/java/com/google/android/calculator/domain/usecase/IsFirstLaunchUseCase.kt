package com.google.android.calculator.domain.usecase

import com.google.android.calculator.domain.repository.PinRepository

class IsFirstLaunchUseCase(private val repo: PinRepository) {
    operator fun invoke(): Boolean = repo.isFirstLaunch()
}