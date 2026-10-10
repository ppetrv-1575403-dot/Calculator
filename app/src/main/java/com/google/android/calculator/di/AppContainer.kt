package com.google.android.calculator.di

import android.content.Context
import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.repository.FolderRepositoryContainerImpl
import com.google.android.calculator.data.repository.FolderRepositoryImpl
import com.google.android.calculator.data.repository.LocationRepositoryContainerImpl
import com.google.android.calculator.data.repository.LocationRepositoryImpl
import com.google.android.calculator.data.repository.PinRepositoryImpl
import com.google.android.calculator.data.repository.VaultRepositoryContainerImpl
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
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.OfflineRegions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        VaultManager.init(appContext)
        OfflineRegions.initialize(appContext)
    }

    val securePreferences by lazy { SecurePreferences(appContext) }
    val cryptoManager by lazy { CryptoManager(appContext) }

    val pinRepository: PinRepository by lazy { PinRepositoryImpl(securePreferences) }

    // === Кэши репозиториев по slotId ===

    private val vaultRepoCache = mutableMapOf<String, VaultRepository>()
    private val locationRepoCache = mutableMapOf<String, LocationRepository>()
    private val folderRepoCache = mutableMapOf<String, FolderRepository>()
    private val containerCache = mutableMapOf<String, VaultContainer>()

    /** Получить или создать контейнер для слота (если включен) */
    private fun containerForSlot(slotId: String): VaultContainer? {
        if (!VaultManager.containerEnabled(slotId)) return null
        val file = VaultManager.containerFile(slotId) ?: return null
        return containerCache.getOrPut(slotId) {
            val c = VaultContainer(file, cryptoManager)
            try {
                if (!c.exists()) c.create() else c.open()
            } catch (e: Exception) {
                FileLogger.e("AppContainer", "Container open failed for slot $slotId", e)
                try { c.create() } catch (e2: Exception) { /* fallback below */ }
            }
            c
        }
    }

    val vaultRepository: VaultRepository
        get() = vaultRepositoryForSlot(VaultManager.currentSlotId)

    val locationRepository: LocationRepository
        get() = locationRepositoryForSlot(VaultManager.currentSlotId)

    val folderRepository: FolderRepository
        get() = folderRepositoryForSlot(VaultManager.currentSlotId)

    /*val vaultRepository: VaultRepository
        get() = vaultRepoCache.getOrPut(VaultManager.currentSlotId) {
            val slotId = VaultManager.currentSlotId
            val container = containerForSlot(slotId)
            if (container != null) {
                FileLogger.i("AppContainer", "Using container repo for slot $slotId")
                VaultRepositoryContainerImpl(appContext, container)
            } else {
                val s = VaultManager.storageSuffix()
                VaultRepositoryImpl(
                    appContext, cryptoManager,
                    "vault$s", "vault_meta$s.enc", "vault_meta$s.txt"
                )
            }
        }

    val locationRepository: LocationRepository
        get() = locationRepoCache.getOrPut(VaultManager.currentSlotId) {
            val slotId = VaultManager.currentSlotId
            val container = containerForSlot(slotId)
            if (container != null) {
                LocationRepositoryContainerImpl(container)
            } else {
                val s = VaultManager.storageSuffix()
                LocationRepositoryImpl(appContext, "locations_meta$s.enc", "locations_meta$s.txt")
            }
        }

    val folderRepository: FolderRepository
        get() = folderRepoCache.getOrPut(VaultManager.currentSlotId) {
            val slotId = VaultManager.currentSlotId
            val container = containerForSlot(slotId)
            if (container != null) {
                FolderRepositoryContainerImpl(container)
            } else {
                val s = VaultManager.storageSuffix()
                FolderRepositoryImpl(appContext, "folders_meta$s.enc", "folders_meta$s.txt")
            }
        }
        */
    // Use case'ы
    val addFileUseCase get() = AddFileUseCase(vaultRepository)
    val getFilesUseCase get() = GetFilesUseCase(vaultRepository)

    val checkPinUseCase by lazy { CheckPinUseCase(pinRepository) }
    val savePinUseCase by lazy { SavePinUseCase(pinRepository) }
    val isFirstLaunchUseCase by lazy { IsFirstLaunchUseCase(pinRepository) }
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

    private val _folderPickerResult = MutableStateFlow<String?>(null)
    val folderPickerResult: StateFlow<String?> = _folderPickerResult
    fun setFolderPickerResult(path: String?) { _folderPickerResult.value = path }
    fun resetFolderPickerResult() { _folderPickerResult.value = null }

    // === Публичные методы получения репозиториев для любого слота ===
    // (без изменения currentSlotId — нужно для seed и других фоновых операций)

    fun vaultRepositoryForSlot(slotId: String): VaultRepository =
        vaultRepoCache.getOrPut(slotId) { createVaultRepo(slotId) }

    fun locationRepositoryForSlot(slotId: String): LocationRepository =
        locationRepoCache.getOrPut(slotId) { createLocationRepo(slotId) }

    fun folderRepositoryForSlot(slotId: String): FolderRepository =
        folderRepoCache.getOrPut(slotId) { createFolderRepo(slotId) }

    private fun createVaultRepo(slotId: String): VaultRepository {
        val container = containerForSlot(slotId)
        return if (container != null) {
            VaultRepositoryContainerImpl(appContext, container)
        } else {
            val s = if (slotId == VaultManager.REAL_ID) "" else "_$slotId"
            VaultRepositoryImpl(appContext, cryptoManager, "vault$s", "vault_meta$s.enc", "vault_meta$s.txt")
        }
    }

    private fun createLocationRepo(slotId: String): LocationRepository {
        val container = containerForSlot(slotId)
        return if (container != null) {
            LocationRepositoryContainerImpl(container)
        } else {
            val s = if (slotId == VaultManager.REAL_ID) "" else "_$slotId"
            LocationRepositoryImpl(appContext, "locations_meta$s.enc", "locations_meta$s.txt")
        }
    }

    private fun createFolderRepo(slotId: String): FolderRepository {
        val container = containerForSlot(slotId)
        return if (container != null) {
            FolderRepositoryContainerImpl(container)
        } else {
            val s = if (slotId == VaultManager.REAL_ID) "" else "_$slotId"
            FolderRepositoryImpl(appContext, "folders_meta$s.enc", "folders_meta$s.txt")
        }
    }


}


