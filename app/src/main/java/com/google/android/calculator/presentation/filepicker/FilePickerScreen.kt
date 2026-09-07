package com.google.android.calculator.presentation.filepicker

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.utils.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PickerFileItem(
    val name: String, val path: String, val mimeType: String,
    val isDirectory: Boolean, val size: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    appContainer: AppContainer,
    initialPath: String,
    folderId: String?,
    onBack: () -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        isLoading = true
        files = withContext(Dispatchers.IO) { loadFiles(currentPath) }
        isLoading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(currentPath.substringAfterLast("/", "Файлы"), style = MaterialTheme.typography.titleMedium) },
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                files.isEmpty() -> Text("Папка пуста", Modifier.align(Alignment.Center).padding(16.dp))
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { file ->
                        Card(
                            onClick = {
                                if (file.isDirectory) currentPath = file.path
                                else {
                                    isProcessing = true
                                    scope.launch {
                                        try {
                                            val uri = Uri.fromFile(File(file.path))
                                            val result = withContext(Dispatchers.IO) {
                                                appContainer.addFileUseCase(uri, file.name, file.mimeType, folderId)
                                            }
                                            if (result.isSuccess) {
                                                appContainer.requestVaultRefresh()
                                                onBack()
                                            }
                                        } finally { isProcessing = false }
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val icon = when {
                                    file.isDirectory -> Icons.Default.Folder
                                    file.mimeType.startsWith("image/") -> Icons.Default.Image
                                    file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                                    FileTypeUtils.isTextFile(file.name, file.mimeType) -> Icons.Default.Description
                                    else -> Icons.Default.InsertDriveFile
                                }
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).padding(end = 12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    if (!file.isDirectory) Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (isProcessing) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Card(Modifier.padding(32.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Шифрование и сохранение...")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadFiles(path: String): List<PickerFileItem> = withContext(Dispatchers.IO) {
    val list = mutableListOf<PickerFileItem>()
    val dir = File(path)
    if (dir.exists() && dir.isDirectory && dir.canRead()) {
        dir.listFiles()?.forEach { f ->
            if (!f.name.startsWith(".")) {
                list.add(PickerFileItem(
                    f.name, f.absolutePath,
                    if (f.isDirectory) "inode/directory" else guessMime(f.name),
                    f.isDirectory, if (f.isDirectory) 0 else f.length()
                ))
            }
        }
    }
    list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

private fun guessMime(name: String): String = when (name.substringAfterLast(".", "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"
    "pdf" -> "application/pdf"; "zip" -> "application/zip"
    "txt", "md" -> "text/plain"; "py" -> "text/x-python"; "json" -> "application/json"
    else -> "application/octet-stream"
}

private fun formatSize(size: Long): String =
    if (size < 1024) "$size B" else if (size < 1024 * 1024) "${size / 1024} KB" else "${size / (1024 * 1024)} MB"