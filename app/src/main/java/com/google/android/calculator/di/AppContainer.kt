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
    val cryptoManager by lazy { CryptoManager }

    val pinRepository: PinRepository by lazy { PinRepositoryImpl(securePreferences) }

    private val vaultRepoCache = mutableMapOf<String, VaultRepository>()
    private val locationRepoCache = mutableMapOf<String, LocationRepository>()
    private val folderRepoCache = mutableMapOf<String, FolderRepository>()
    private val containerCache = mutableMapOf<String, VaultContainer>()
    
    // ⭐ Сохраняем последнюю открытую папку для каждого slot
    private val lastVisitedFolderIds = mutableMapOf<String, String?>()

    private val cacheLock = Any()

    private fun containerForSlot(slotId: String): VaultContainer? {
        if (!VaultManager.containerEnabled(slotId)) return null
        val file = VaultManager.containerFile(slotId) ?: return null
        val password = VaultManager.activeSessionPin ?: run {
            FileLogger.e("AppContainer", "No active session PIN for slot $slotId")
            return null
        }
        
        synchronized(cacheLock) {
            val cached = containerCache[slotId]
            if (cached != null) {
                // ⭐ Контейнер уже существует в кэше
                if (cached.isLocked()) {
                    // sessionKey был сброшен (при блокировке), нужно reopen
                    FileLogger.i("AppContainer", "Reopening cached container for slot $slotId")
                    try {
                        cached.reopen(password)
                    } catch (e: Exception) {
                        FileLogger.e("AppContainer", "Container reopen failed for slot $slotId: ${e.message}", e)
                        if (e.message?.contains("AEADBadTagException") == true || e.message?.contains("BAD_DECRYPT") == true) {
                            FileLogger.w("AppContainer", "Incompatible container detected. Resetting...")
                            file.delete()
                            containerCache.remove(slotId)
                            return containerForSlot(slotId) // Рекурсивный вызов для создания нового
                        }
                        throw e
                    }
                }
                return cached
            }
            
            // Создаём новый контейнер
            val c = VaultContainer(file, cryptoManager)
            try {
                if (!c.exists()) {
                    FileLogger.i("AppContainer", "Creating new container for slot $slotId")
                    c.create(password)
                } else {
                    FileLogger.i("AppContainer", "Opening existing container for slot $slotId")
                    c.open(password)
                }
            } catch (e: Exception) {
                FileLogger.e("AppContainer", "Container open failed for slot $slotId: ${e.message}", e)
                
                if (e.message?.contains("AEADBadTagException") == true || e.message?.contains("BAD_DECRYPT") == true) {
                    FileLogger.w("AppContainer", "Incompatible or corrupted container detected. Resetting...")
                    file.delete()
                    c.create(password)
                } else {
                    throw e
                }
            }
            
            containerCache[slotId] = c
            return c
        }
    }

    fun vaultRepositoryForSlot(slotId: String): VaultRepository =
        vaultRepoCache.getOrPut(slotId) { createVaultRepo(slotId) }

    fun locationRepositoryForSlot(slotId: String): LocationRepository =
        locationRepoCache.getOrPut(slotId) { createLocationRepo(slotId) }

    fun folderRepositoryForSlot(slotId: String): FolderRepository =
        folderRepoCache.getOrPut(slotId) { createFolderRepo(slotId) }

    val vaultRepository: VaultRepository get() = vaultRepositoryForSlot(VaultManager.currentSlotId)
    val locationRepository: LocationRepository get() = locationRepositoryForSlot(VaultManager.currentSlotId)
    val folderRepository: FolderRepository get() = folderRepositoryForSlot(VaultManager.currentSlotId)

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

    /**
     * ⭐ Инвалидирует кэш для указанного slot
     * НЕ удаляет VaultContainer, а только сбрасывает sessionKey
     */
    fun invalidateCacheForSlot(slotId: String) {
        synchronized(cacheLock) {
            // ⭐ Полностью удаляем контейнер из кэша
            containerCache.remove(slotId)

            // Удаляем все репозитории
            vaultRepoCache.remove(slotId)
            locationRepoCache.remove(slotId)
            folderRepoCache.remove(slotId)
        }
        FileLogger.i("AppContainer", "Cache completely invalidated for slot: $slotId")
    }

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

    fun getActiveContainer(): VaultContainer? {
        val slotId = VaultManager.currentSlotId
        if (!VaultManager.containerEnabled(slotId)) return null
        return containerCache[slotId]
    }

    /**
     * ⭐ Сохраняет последнюю открытую папку для slot
     */
    fun setLastVisitedFolderId(slotId: String, folderId: String?) {
        lastVisitedFolderIds[slotId] = folderId
        FileLogger.d("AppContainer", "Last visited folder set: slot=$slotId, folder=$folderId")
    }

    /**
     * ⭐ Возвращает последнюю открытую папку для slot
     */
    fun getLastVisitedFolderId(slotId: String): String? {
        return lastVisitedFolderIds[slotId]
    }
}