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
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.android.calculator.utils.MapStorage
import com.google.android.calculator.utils.OfflineRegions

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        VaultManager.init(appContext)
        OfflineRegions.initialize(appContext)  // ← ДОБАВИТЬ
        MapStorage.migrateFromOldLocations(appContext)
    }

    val securePreferences by lazy { SecurePreferences(appContext) }
    private val cryptoManager by lazy { CryptoManager(appContext) }

    val pinRepository: PinRepository by lazy { PinRepositoryImpl(securePreferences) }

    // === РЕАЛЬНОЕ хранилище ===
    private val vaultRepositoryReal: VaultRepository by lazy {
        VaultRepositoryImpl(appContext, cryptoManager, "vault", "vault_meta.enc", "vault_meta.txt")
    }
    private val locationRepositoryReal: LocationRepository by lazy {
        LocationRepositoryImpl(appContext, "locations_meta.enc", "locations_meta.txt")
    }
    private val folderRepositoryReal: FolderRepository by lazy {
        FolderRepositoryImpl(appContext, "folders_meta.enc", "folders_meta.txt")
    }

    // === ФЕЙКОВОЕ хранилище ===
    private val vaultRepositoryDecoy: VaultRepository by lazy {
        VaultRepositoryImpl(appContext, cryptoManager, "vault_decoy", "vault_meta_decoy.enc", "vault_meta_decoy.txt")
    }
    private val locationRepositoryDecoy: LocationRepository by lazy {
        LocationRepositoryImpl(appContext, "locations_meta_decoy.enc", "locations_meta_decoy.txt")
    }
    private val folderRepositoryDecoy: FolderRepository by lazy {
        FolderRepositoryImpl(appContext, "folders_meta_decoy.enc", "folders_meta_decoy.txt")
    }

    // === Переключаемые репозитории ===
    val vaultRepository: VaultRepository
        get() = if (VaultManager.currentMode == VaultMode.DECOY)
            vaultRepositoryDecoy else vaultRepositoryReal

    val locationRepository: LocationRepository
        get() = if (VaultManager.currentMode == VaultMode.DECOY)
            locationRepositoryDecoy else locationRepositoryReal

    val folderRepository: FolderRepository
        get() = if (VaultManager.currentMode == VaultMode.DECOY)
            folderRepositoryDecoy else folderRepositoryReal

    val checkPinUseCase by lazy { CheckPinUseCase(pinRepository) }
    val savePinUseCase by lazy { SavePinUseCase(pinRepository) }
    val isFirstLaunchUseCase by lazy { IsFirstLaunchUseCase(pinRepository) }
    
    // ⚠️ НЕ by lazy: use case создаётся при каждом обращении,
    // чтобы всегда брать репозиторий ТЕКУЩЕГО режима (REAL/DECOY)
    val addFileUseCase get() = AddFileUseCase(vaultRepository)
    val getFilesUseCase get() = GetFilesUseCase(vaultRepository)    
    
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