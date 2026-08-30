package com.google.android.calculator.presentation.filepicker

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.usecase.AddFileUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CustomFileItem(
    val name: String,
    val path: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val size: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    appContainer: AppContainer,
    initialPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<CustomFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        isLoading = true
        files = withContext(Dispatchers.IO) {
            loadFilesFromDirectory(currentPath)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = currentPath.substringAfterLast("/", "Файлы"),
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = File(currentPath).parent
                        if (parent != null) currentPath = parent else onBack()
                    }, enabled = !isProcessing) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                }
                files.isEmpty() -> {
                    Text(
                        "Папка пуста",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(files) { file ->
                            FilePickerItem(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) {
                                        currentPath = file.path
                                    } else {
                                        isProcessing = true
                                        scope.launch {
                                            try {
                                                val addFileUseCase = AddFileUseCase(appContainer.vaultRepository)
                                                val fileUri = android.net.Uri.fromFile(File(file.path))
                                                val result = withContext(Dispatchers.IO) {
                                                    addFileUseCase(fileUri, file.name, file.mimeType)
                                                }
                                                
                                                if (result.isSuccess) {
                                                    onBack()
                                                } else {
                                                    errorMessage = "Ошибка: ${result.exceptionOrNull()?.message}"
                                                }
                                            } catch (e: Exception) {
                                                errorMessage = "Ошибка: ${e.message}"
                                            } finally {
                                                isProcessing = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isProcessing
                            )
                        }
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.padding(32.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Шифрование и сохранение...")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePickerItem(file: CustomFileItem, onClick: () -> Unit, enabled: Boolean) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                file.isDirectory -> Icons.Default.Folder
                file.mimeType.startsWith("image/") -> Icons.Default.Image
                file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                else -> Icons.Default.InsertDriveFile
            }
            
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp).padding(end = 12.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                if (!file.isDirectory) {
                    Text(
                        text = "${file.mimeType} • ${formatSize(file.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private suspend fun loadFilesFromDirectory(path: String): List<CustomFileItem> = withContext(Dispatchers.IO) {
    val files = mutableListOf<CustomFileItem>()
    try {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory && dir.canRead()) {
            val children = dir.listFiles() ?: emptyArray()
            for (child in children) {
                // Скрываем скрытые файлы (начинающиеся с точки)
                if (child.name.startsWith(".")) continue
                
                val mimeType = if (child.isDirectory) "inode/directory" else getMimeType(child.name)
                files.add(CustomFileItem(
                    name = child.name,
                    path = child.absolutePath,
                    mimeType = mimeType,
                    isDirectory = child.isDirectory,
                    size = if (child.isDirectory) 0 else child.length()
                ))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    // Сортировка: сначала папки, потом файлы по имени
    files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

private fun getMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast(".", "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp" -> "image/$extension"
        "mp4", "mkv", "avi" -> "video/$extension"
        "txt", "md", "json" -> "text/plain"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}

private fun formatSize(size: Long): String {
    if (size < 1024) return "$size B"
    if (size < 1024 * 1024) return "${size / 1024} KB"
    return "${size / (1024 * 1024)} MB"
}