package com.google.android.calculator.presentation.vault

import android.net.Uri
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
import kotlinx.coroutines.launch

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

    private val folderStack = ArrayDeque<VaultFolder?>()

    init { loadItems() }

    fun refresh() { loadItems() }

    private fun loadItems() {
        viewModelScope.launch {
            val fid = currentFolder?.id
            val folders = appContainer.folderRepository.getFolders().filter { it.parentId == fid }.map { VaultItem.FolderItem(it) }
            val locations = appContainer.locationRepository.getLocations().filter { it.folderId == fid }.map { VaultItem.LocationItem(it) }
            val files = getFilesUseCase().filter { it.folderId == fid }.map { VaultItem.FileItem(it) }
            items = folders + locations + files
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
        viewModelScope.launch { appContainer.folderRepository.addFolder(name, currentFolder?.id); loadItems() }
    }

    fun deleteFolder(folder: VaultFolder) {
        viewModelScope.launch {
            appContainer.folderRepository.reparentSubfolders(folder.id, folder.parentId)
            repository.moveFilesToFolder(folder.id, folder.parentId)
            appContainer.locationRepository.moveLocationsToFolder(folder.id, folder.parentId)
            appContainer.folderRepository.deleteFolder(folder.id)
            loadItems()
        }
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch { repository.deleteFile(file.id); loadItems() }
    }

    fun deleteLocation(id: String) {
        viewModelScope.launch { appContainer.locationRepository.deleteLocation(id); loadItems() }
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
            when (item) {
                is VaultItem.FileItem -> repository.moveFileToFolder(item.file.id, folder?.id)
                is VaultItem.LocationItem -> appContainer.locationRepository.moveLocationToFolder(item.location.id, folder?.id)
                is VaultItem.FolderItem -> { }
            }
            itemToMove = null
            loadItems()
        }
    }

    fun moveToParentFolder() {
        val item = itemToMove ?: return
        val parentId = currentFolder?.parentId
        viewModelScope.launch {
            when (item) {
                is VaultItem.FileItem -> repository.moveFileToFolder(item.file.id, parentId)
                is VaultItem.LocationItem -> appContainer.locationRepository.moveLocationToFolder(item.location.id, parentId)
                is VaultItem.FolderItem -> { }
            }
            itemToMove = null
            loadItems()
        }
    }
}