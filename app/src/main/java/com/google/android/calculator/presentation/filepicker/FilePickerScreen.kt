package com.google.android.calculator.presentation.filepicker

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.utils.CoordinateRecognizer
import com.google.android.calculator.utils.FileTypeUtils
import com.google.android.calculator.utils.OriginalFileAction
import com.google.android.calculator.utils.SecureDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("Шифрование и сохранение...") }
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
                                if (file.isDirectory) {
                                    currentPath = file.path
                                } else {
                                    isProcessing = true
                                    scope.launch {
                                        try {
                                            val fileUri = Uri.fromFile(File(file.path))
                                            val isImage = file.mimeType.startsWith("image/")

                                            // Шаг 1: добавляем файл в хранилище
                                            progressText = "Шифрование и сохранение..."
                                            val addResult = withContext(Dispatchers.IO) {
                                                if (isImage) {
                                                    // Для фото — с возвратом id (нужен для привязки точки)
                                                    appContainer.vaultRepository.addFileWithId(
                                                        fileUri, file.name, file.mimeType, folderId
                                                    )
                                                } else {
                                                    // Для обычных файлов — без id
                                                    appContainer.addFileUseCase(
                                                        fileUri, file.name, file.mimeType, folderId
                                                    )
                                                }
                                            }

                                            if (addResult.isSuccess) {
                                                // Шаг 2: для изображений — пытаемся распознать координаты
                                                if (isImage) {
                                                    progressText = "Распознавание координат..."
                                                    val photoId = addResult.getOrNull() as? String
                                                    val coords = withContext(Dispatchers.IO) {
                                                        CoordinateRecognizer.recognize(context, fileUri)
                                                    }

                                                    if (coords != null && photoId != null) {
                                                        progressText = "Создание точки..."
                                                        val locationId = UUID.randomUUID().toString()
                                                        withContext(Dispatchers.IO) {
                                                            appContainer.locationRepository.addLocation(
                                                                SavedLocation(
                                                                    id = locationId,
                                                                    name = file.name.substringBeforeLast("."),
                                                                    description = "Импортировано из ${file.name} (${coords.source})",
                                                                    latitude = coords.latitude,
                                                                    longitude = coords.longitude,
                                                                    photoId = photoId,
                                                                    folderId = folderId
                                                                )
                                                            )
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                "📍 Создана точка: %.5f, %.5f".format(coords.latitude, coords.longitude),
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                    } else if (isImage) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                context,
                                                                "Фото добавлено (координаты не найдены)",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            context,
                                                            "Файл добавлен в хранилище",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }

                                                // Шаг 3: действие с оригиналом
                                                handleOriginalFile(context, fileUri, appContainer)

                                                appContainer.requestVaultRefresh()
                                                onBack()
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        "Ошибка: ${addResult.exceptionOrNull()?.message ?: "неизвестно"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Ошибка: ${e.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } finally {
                                            isProcessing = false
                                        }
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
                            Text(progressText)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun handleOriginalFile(
    context: android.content.Context,
    originalUri: Uri,
    appContainer: AppContainer
) {
    val action = appContainer.securePreferences.originalFileAction
    val result = when (action) {
        OriginalFileAction.KEEP -> return
        OriginalFileAction.DELETE -> SecureDelete.delete(context, originalUri)
        OriginalFileAction.SHRED -> SecureDelete.shred(context, originalUri)
    }
    if (result) {
        withContext(Dispatchers.Main) {
            val msg = when (action) {
                OriginalFileAction.DELETE -> "🗑 Оригинал удалён"
                OriginalFileAction.SHRED -> "🔥 Оригинал затёрт и удалён"
                else -> ""
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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