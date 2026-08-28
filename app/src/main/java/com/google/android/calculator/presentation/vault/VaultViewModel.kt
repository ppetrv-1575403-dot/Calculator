package com.google.android.calculator.presentation.vault

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.calculator.domain.model.VaultFile // <-- ИСПРАВЛЕННЫЙ ИМПОРТ
import com.google.android.calculator.domain.usecase.AddFileUseCase
import com.google.android.calculator.domain.usecase.GetFilesUseCase
import com.google.android.calculator.domain.repository.VaultRepository
import kotlinx.coroutines.launch
import java.io.File

class VaultViewModel(
    private val addFileUseCase: AddFileUseCase,
    private val getFilesUseCase: GetFilesUseCase,
    private val repository: VaultRepository
) : ViewModel() {

    var files by mutableStateOf<List<VaultFile>>(emptyList())
        private set
    var currentPreviewFile by mutableStateOf<VaultFile?>(null)
        private set
    var decryptedTempFile by mutableStateOf<File?>(null)
        private set
    var decryptedTextContent by mutableStateOf<String?>(null)
        private set

    init { loadFiles() }

    private fun loadFiles() {
        viewModelScope.launch { files = getFilesUseCase() }
    }

    fun addFile(uri: Uri, name: String, mimeType: String) {
        viewModelScope.launch {
            addFileUseCase(uri, name, mimeType)
            loadFiles()
        }
    }

    fun openFile(file: VaultFile) {
        viewModelScope.launch {
            currentPreviewFile = file
            val temp = repository.decryptToTempFile(file.encryptedFileName)
            decryptedTempFile = temp
            if (temp != null && file.mimeType.startsWith("text/")) {
                decryptedTextContent = try { temp.readText() } catch (e: Exception) { null }
            }
        }
    }

    fun closeFile() {
        decryptedTempFile?.delete()
        currentPreviewFile = null
        decryptedTempFile = null
        decryptedTextContent = null
    }
}