package com.google.android.calculator.presentation.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.domain.usecase.AddFileUseCase
import com.google.android.calculator.domain.usecase.GetFilesUseCase
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class VaultItem {
    data class FolderItem(val folder: VaultFolder) : VaultItem()
    data class FileItem(val file: VaultFile) : VaultItem()
    data class LocationItem(val location: SavedLocation) : VaultItem()
}

class VaultViewModel(
    private val addFileUseCase: AddFileUseCase,
    private val getFilesUseCase: GetFilesUseCase,
    private val repository: VaultRepository,
    private val appContainer: AppContainer
) : ViewModel() {

    var items by mutableStateOf<List<VaultItem>>(emptyList()); private set
    var currentFolder by mutableStateOf<VaultFolder?>(null); private set
    var fileToExport by mutableStateOf<VaultFile?>(null); private set
    var itemToMove by mutableStateOf<VaultItem?>(null); private set
    var isLoading by mutableStateOf(true); private set

    private val folderStack = ArrayDeque<VaultFolder?>()

    init { loadItems() }

    fun refresh() { loadItems() }

    private fun loadItems() {
        // ⭐ Защита: не загружаем данные, если контейнер заблокирован
        // Это предотвращает перезапись UI пустым списком при блокировке
        val container = appContainer.getActiveContainer()
        if (container != null && container.isLocked()) {
            FileLogger.w("VaultViewModel", "loadItems skipped: container is locked")
            isLoading = false
            return
        }
        
        isLoading = true
        viewModelScope.launch {
            try {
                val fid = currentFolder?.id
                
                val result = withContext(Dispatchers.IO) {
                    val folders = appContainer.folderRepository.getFolders()
                        .filter { it.parentId == fid }
                        .map { VaultItem.FolderItem(it) }
                    val locations = appContainer.locationRepository.getLocations()
                        .filter { it.folderId == fid }
                        .map { VaultItem.LocationItem(it) }
                    val files = getFilesUseCase()
                        .filter { it.folderId == fid }
                        .map { VaultItem.FileItem(it) }
                    folders + locations + files
                }
                
                // ⭐ Дополнительная защита: не перезаписываем, если получили пустой список при ошибке
                // Если items уже содержит данные, и мы получили пустой результат — это подозрительно
                if (result.isEmpty() && items.isNotEmpty() && container?.isLocked() == true) {
                    FileLogger.w("VaultViewModel", "loadItems: empty result ignored (container locked)")
                } else {
                    items = result
                }
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error loading items", e)
                // ⭐ Не перезаписываем items при ошибке — сохраняем текущее состояние
                // items = emptyList() // УБРАНО!
            } finally {
                isLoading = false
            }
        }
    }
    
    fun openFolder(folder: VaultFolder) {
        folderStack.addLast(currentFolder)
        currentFolder = folder
        loadItems()
    }

    fun navigateUpFolder(): Boolean {
        if (folderStack.isEmpty()) return false
        currentFolder = folderStack.removeLast()
        loadItems()
        return true
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appContainer.folderRepository.addFolder(name, currentFolder?.id)
                }
                loadItems()
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error creating folder", e)
            }
        }
    }

    fun deleteFolder(folder: VaultFolder) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val allFolders = appContainer.folderRepository.getFolders()
                    val foldersToDeleteIds = mutableListOf(folder.id)
                    
                    fun collectSubfolders(parentId: String) {
                        val children = allFolders.filter { it.parentId == parentId }
                        children.forEach { child ->
                            foldersToDeleteIds.add(child.id)
                            collectSubfolders(child.id)
                        }
                    }
                    collectSubfolders(folder.id)

                    val allFiles = getFilesUseCase()
                    val filesToDelete = allFiles.filter { it.folderId in foldersToDeleteIds }

                    val allLocations = appContainer.locationRepository.getLocations()
                    val locationsToDelete = allLocations.filter { it.folderId in foldersToDeleteIds }

                    filesToDelete.forEach { file ->
                        repository.deleteFile(file.id)
                    }

                    locationsToDelete.forEach { location ->
                        appContainer.locationRepository.deleteLocation(location.id)
                    }

                    foldersToDeleteIds.forEach { folderId ->
                        appContainer.folderRepository.deleteFolder(folderId)
                    }
                }
                
                if (currentFolder?.id == folder.id) {
                    navigateUpFolder()
                } else {
                    loadItems()
                }
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error deleting folder", e)
            }
        }
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.deleteFile(file.id)
                }
                loadItems()
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error deleting file", e)
            }
        }
    }

    fun deleteLocation(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appContainer.locationRepository.deleteLocation(id)
                }
                loadItems()
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error deleting location", e)
            }
        }
    }

    fun prepareExport(file: VaultFile) {
        fileToExport = file
        appContainer.setFileOperationInProgress(true)
    }

    fun clearExportState() {
        fileToExport = null
        appContainer.setFileOperationInProgress(false)
    }

    fun startMove(item: VaultItem) {
        itemToMove = item
    }

    fun cancelMove() {
        itemToMove = null
    }

    fun moveToFolder(folder: VaultFolder?) {
        val item = itemToMove ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (item) {
                        is VaultItem.FileItem -> {
                            repository.moveFileToFolder(item.file.id, folder?.id)
                        }
                        is VaultItem.LocationItem -> {
                            appContainer.locationRepository.moveLocationToFolder(item.location.id, folder?.id)
                        }
                        is VaultItem.FolderItem -> {
                            // Папки не перемещаем через этот метод
                        }
                        else -> {
                            FileLogger.w("VaultViewModel", "Unknown VaultItem type in moveToFolder")
                        }
                    }
                }
                itemToMove = null
                loadItems()
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error in moveToFolder", e)
            }
        }
    }

    fun moveToParentFolder() {
        val item = itemToMove ?: return
        val parentId = currentFolder?.parentId
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (item) {
                        is VaultItem.FileItem -> {
                            repository.moveFileToFolder(item.file.id, parentId)
                        }
                        is VaultItem.LocationItem -> {
                            appContainer.locationRepository.moveLocationToFolder(item.location.id, parentId)
                        }
                        is VaultItem.FolderItem -> {
                            // Папки не перемещаем через этот метод
                        }
                        else -> {
                            FileLogger.w("VaultViewModel", "Unknown VaultItem type in moveToParentFolder")
                        }
                    }
                }
                itemToMove = null
                loadItems()
            } catch (e: Exception) {
                FileLogger.e("VaultViewModel", "Error in moveToParentFolder", e)
            }
        }
    }
}