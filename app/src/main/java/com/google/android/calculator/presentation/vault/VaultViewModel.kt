package com.google.android.calculator.presentation.vault

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.usecase.AddFileUseCase
import com.google.android.calculator.domain.usecase.GetFilesUseCase
import com.google.android.calculator.domain.repository.VaultRepository
import kotlinx.coroutines.launch
import java.io.File

class VaultViewModel(
    private val addFileUseCase: AddFileUseCase,
    private val getFilesUseCase: GetFilesUseCase,
    private val repository: VaultRepository,
    private val appContainer: AppContainer // Добавили
) : ViewModel() {

    var files by mutableStateOf<List<VaultFile>>(emptyList())
        private set
    var fileToExport by mutableStateOf<VaultFile?>(null)
        private set

    init { loadFiles() }

    private fun loadFiles() {
        viewModelScope.launch { files = getFilesUseCase() }
    }

    fun addFile(uri: Uri, name: String, mimeType: String) {
        viewModelScope.launch {
            addFileUseCase(uri, name, mimeType)
            loadFiles()
            appContainer.setFileOperationInProgress(false) // Сбрасываем флаг
        }
    }

    fun deleteFile(file: VaultFile) {
        viewModelScope.launch {
            repository.deleteFile(file.id)
            loadFiles()
        }
    }

    fun prepareExport(file: VaultFile) {
        fileToExport = file
        appContainer.setFileOperationInProgress(true) // Устанавливаем флаг
    }

    fun clearExportState() {
        fileToExport = null
        appContainer.setFileOperationInProgress(false) // Сбрасываем флаг
    }
}