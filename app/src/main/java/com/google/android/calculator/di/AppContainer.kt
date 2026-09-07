package com.google.android.calculator.di

import android.content.Context
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.repository.FolderRepositoryImpl
import com.google.android.calculator.data.repository.LocationRepositoryImpl
import com.google.android.calculator.data.repository.PinRepositoryImpl
import com.google.android.calculator.data.repository.VaultRepositoryImpl
import com.google.android.calculator.data.secure.SecurePreferences
import com.google.android.calculator.domain.repository.FolderRepository
import com.google.android.calculator.domain.repository.LocationRepository
import com.google.android.calculator.domain.repository.PinRepository
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.domain.usecase.AddFileUseCase
import com.google.android.calculator.domain.usecase.CheckPinUseCase
import com.google.android.calculator.domain.usecase.ClearCacheUseCase
import com.google.android.calculator.domain.usecase.GetFilesUseCase
import com.google.android.calculator.domain.usecase.IsFirstLaunchUseCase
import com.google.android.calculator.domain.usecase.SavePinUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val securePreferences by lazy { SecurePreferences(appContext) }
    private val cryptoManager by lazy { CryptoManager(appContext) }

    val pinRepository: PinRepository by lazy { PinRepositoryImpl(securePreferences) }
    val vaultRepository: VaultRepository by lazy { VaultRepositoryImpl(appContext, cryptoManager) }
    val locationRepository: LocationRepository by lazy { LocationRepositoryImpl(appContext) }
    val folderRepository: FolderRepository by lazy { FolderRepositoryImpl(appContext) }

    val checkPinUseCase by lazy { CheckPinUseCase(pinRepository) }
    val savePinUseCase by lazy { SavePinUseCase(pinRepository) }
    val isFirstLaunchUseCase by lazy { IsFirstLaunchUseCase(pinRepository) }
    val addFileUseCase by lazy { AddFileUseCase(vaultRepository) }
    val getFilesUseCase by lazy { GetFilesUseCase(vaultRepository) }
    val clearCacheUseCase by lazy { ClearCacheUseCase(appContext) }

    private val _navigateToCalculator = MutableStateFlow(false)
    val navigateToCalculator: StateFlow<Boolean> = _navigateToCalculator
    fun triggerNavigateToCalculator() { _navigateToCalculator.value = true }
    fun resetNavigateSignal() { _navigateToCalculator.value = false }

    private val _isFileOperationInProgress = MutableStateFlow(false)
    val isFileOperationInProgress: StateFlow<Boolean> = _isFileOperationInProgress
    fun setFileOperationInProgress(v: Boolean) { _isFileOperationInProgress.value = v }

    private val _vaultRefresh = MutableStateFlow(0)
    val vaultRefresh: StateFlow<Int> = _vaultRefresh
    fun requestVaultRefresh() { _vaultRefresh.value++ }
}