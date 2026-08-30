package com.google.android.calculator.presentation.filepicker

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Расширения для определения типов файлов
private val CODE_EXTENSIONS = setOf(
    "py", "js", "ts", "java", "kt", "kts", "cpp", "c", "h", "hpp", "cs", "php",
    "rb", "go", "rs", "swift", "dart", "lua", "sql", "pl", "pm", "proto", "graphql"
)
private val WEB_EXTENSIONS = setOf(
    "html", "htm", "css", "scss", "sass", "less", "xml", "json", "yaml", "yml", "toml"
)
private val DOC_EXTENSIONS = setOf(
    "txt", "md", "markdown", "rst", "log", "csv", "ini", "cfg", "conf"
)
private val SCRIPT_EXTENSIONS = setOf(
    "sh", "bash", "zsh", "ps1", "bat", "cmd"
)

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
        errorMessage = null
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
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val parent = File(currentPath).parent
                            if (parent != null) currentPath = parent else onBack()
                        },
                        enabled = !isProcessing
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
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
                                                val fileUri = Uri.fromFile(File(file.path))
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
                                        }                                    }
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
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
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
            val extension = file.name.substringAfterLast(".", "").lowercase()
            val icon = when {
                file.isDirectory -> Icons.Default.Folder
                file.mimeType.startsWith("image/") -> Icons.Default.Image
                file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                isTextLikeFile(file.name, file.mimeType) -> {
                    if (extension in CODE_EXTENSIONS || extension in WEB_EXTENSIONS || extension in SCRIPT_EXTENSIONS) {
                        Icons.Default.Code
                    } else {
                        Icons.Default.Description
                    }
                }
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
                if (child.name.startsWith(".")) continue

                files.add(
                    CustomFileItem(
                        name = child.name,
                        path = child.absolutePath,
                        mimeType = if (child.isDirectory) "inode/directory" else detectMimeType(child.name),                        isDirectory = child.isDirectory,
                        size = if (child.isDirectory) 0 else child.length()
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

private fun detectMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        in CODE_EXTENSIONS -> "text/x-source-code"
        in WEB_EXTENSIONS -> "text/x-web"
        in DOC_EXTENSIONS -> "text/plain"
        in SCRIPT_EXTENSIONS -> "text/x-shellscript"
        else -> "application/octet-stream"
    }
}

private fun isTextLikeFile(fileName: String, mimeType: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    
    val textMimeTypes = listOf(
        "application/json", 
        "application/xml", 
        "application/javascript",
        "application/x-sh", 
        "application/x-python", 
        "application/x-yaml"
    )
    if (mimeType in textMimeTypes) return true
    
    val ext = fileName.substringAfterLast(".", "").lowercase()
    if (ext.isEmpty()) return true
    
    return ext in CODE_EXTENSIONS || ext in WEB_EXTENSIONS || ext in DOC_EXTENSIONS || ext in SCRIPT_EXTENSIONS
}

private fun formatSize(size: Long): String {
    if (size < 1024) return "$size B"
    if (size < 1024 * 1024) return "${size / 1024} KB"
    return "${size / (1024 * 1024)} MB"
}