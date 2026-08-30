package com.google.android.calculator.di

import android.content.Context
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.repository.PinRepositoryImpl
import com.google.android.calculator.data.repository.VaultRepositoryImpl
import com.google.android.calculator.data.secure.SecurePreferences
import com.google.android.calculator.domain.repository.PinRepository
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.domain.usecase.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    
    val securePreferences by lazy { SecurePreferences(appContext) }
    private val cryptoManager by lazy { CryptoManager(appContext) }

    val pinRepository: PinRepository by lazy { PinRepositoryImpl(securePreferences) }
    val vaultRepository: VaultRepository by lazy { VaultRepositoryImpl(appContext, cryptoManager) }

    val checkPinUseCase by lazy { CheckPinUseCase(pinRepository) }
    val savePinUseCase by lazy { SavePinUseCase(pinRepository) }
    val isFirstLaunchUseCase by lazy { IsFirstLaunchUseCase(pinRepository) }
    val addFileUseCase by lazy { AddFileUseCase(vaultRepository) }
    val getFilesUseCase by lazy { GetFilesUseCase(vaultRepository) }
    val clearCacheUseCase by lazy { ClearCacheUseCase(appContext) }

    private val _navigateToCalculator = MutableStateFlow(false)
    val navigateToCalculator: StateFlow<Boolean> = _navigateToCalculator

    fun triggerNavigateToCalculator() {
        _navigateToCalculator.value = true
    }

    fun resetNavigateSignal() {
        _navigateToCalculator.value = false
    }

    // Флаг для блокировки finishAndRemoveTask во время операций с файлами
    private val _isFileOperationInProgress = MutableStateFlow(false)
    val isFileOperationInProgress: StateFlow<Boolean> = _isFileOperationInProgress

    fun setFileOperationInProgress(inProgress: Boolean) {
        _isFileOperationInProgress.value = inProgress
    }
}