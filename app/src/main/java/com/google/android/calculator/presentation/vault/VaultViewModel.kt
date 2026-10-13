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
    
    // ⭐ Явный флаг загрузки для плавного UI
    var isLoading by mutableStateOf(true); private set

    private val folderStack = ArrayDeque<VaultFolder?>()

    init { loadItems() }

    fun refresh() { loadItems() }

    private fun loadItems() {
        isLoading = true // ⭐ Мгновенно показываем загрузку
        viewModelScope.launch {
            // ⭐ Вся тяжёлая работа (включая deriveKey) строго в фоновом потоке
            val result = withContext(Dispatchers.IO) {
                val fid = currentFolder?.id
                val folders = appContainer.folderRepository.getFolders().filter { it.parentId == fid }.map { VaultItem.FolderItem(it) }
                val locations = appContainer.locationRepository.getLocations().filter { it.folderId == fid }.map { VaultItem.LocationItem(it) }
                val files = getFilesUseCase().filter { it.folderId == fid }.map { VaultItem.FileItem(it) }
                folders + locations + files
            }
            // ⭐ Обновляем UI только когда данные готовы
            items = result
            isLoading = false // ⭐ Скрываем загрузку
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
            withContext(Dispatchers.IO) {
                appContainer.folderRepository.addFolder(name, currentFolder?.id)
            }
            loadItems() 
        }
    }

    fun deleteFolder(folder: VaultFolder) {
        viewModelScope.launch {
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
        }
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch { 
            withContext(Dispatchers.IO) {
                repository.deleteFile(file.id)
            }
            loadItems() 
        }
    }

    fun deleteLocation(id: String) {
        viewModelScope.launch { 
            withContext(Dispatchers.IO) {
                appContainer.locationRepository.deleteLocation(id)
            }
            loadItems() 
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
            withContext(Dispatchers.IO) {
                when (item) {
                    is VaultItem.FileItem -> repository.moveFileToFolder(item.file.id, folder?.id)
                    is VaultItem.LocationItem -> appContainer.locationRepository.moveLocationToFolder(item.location.id, folder?.id)
                    is VaultItem.FolderItem -> { }
                }
            }
            itemToMove = null
            loadItems()
        }
    }

    fun moveToParentFolder() {
        val item = itemToMove ?: return
        val parentId = currentFolder?.parentId
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                when (item) {
                    is VaultItem.FileItem -> repository.moveFileToFolder(item.file.id, parentId)
                    is VaultItem.LocationItem -> appContainer.locationRepository.moveLocationToFolder(item.location.id, parentId)
                    is VaultItem.FolderItem -> { }
                }
            }
            itemToMove = null
            loadItems()
        }
    }
}