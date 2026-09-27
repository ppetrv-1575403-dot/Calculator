package com.google.android.calculator.presentation.filepicker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
import java.util.Locale
import java.util.UUID

data class PickerFileItem(
    val name: String, val path: String, val mimeType: String,
    val isDirectory: Boolean, val size: Long
)

/** Черновик фото-точки для диалога подтверждения */
private data class PhotoDraft(
    val uri: Uri,
    val fileName: String,
    val preview: Bitmap?,
    val latText: String,
    val lonText: String,
    val source: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    appContainer: AppContainer,
    initialPath: String,
    folderId: String?,
    onBack: () -> Unit,
    photoLocationMode: Boolean = false
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("Шифрование и сохранение...") }
    var pendingPhoto by remember { mutableStateOf<PhotoDraft?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        isLoading = true
        files = withContext(Dispatchers.IO) { loadFiles(currentPath, photoLocationMode) }
        isLoading = false
    }

    // === Диалог подтверждения фото-точки ===
    val draft = pendingPhoto
    if (draft != null && !isProcessing) {
        PhotoLocationDialog(
            draft = draft,
            onSave = { name, lat, lon ->
                scope.launch {
                    isProcessing = true
                    progressText = "Шифрование и сохранение..."
                    try {
                        val res = withContext(Dispatchers.IO) {
                            appContainer.vaultRepository.addFileWithId(
                                draft.uri, draft.fileName, guessMime(draft.fileName), folderId
                            )
                        }
                        res.onSuccess { photoId ->
                            withContext(Dispatchers.IO) {
                                appContainer.locationRepository.addLocation(
                                    SavedLocation(
                                        id = UUID.randomUUID().toString(),
                                        name = name,
                                        description = "Фото-точка (${draft.source ?: "координаты указаны вручную"})",
                                        latitude = lat,
                                        longitude = lon,
                                        photoId = photoId,
                                        folderId = folderId
                                    )
                                )
                            }
                            handleOriginalFile(context, draft.uri, appContainer)
                            appContainer.requestVaultRefresh()
                            pendingPhoto = null
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "📍 Точка «$name» создана", Toast.LENGTH_LONG).show()
                            }
                            onBack()
                        }.onFailure { e ->
                            pendingPhoto = null
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        isProcessing = false
                    }
                }
            },
            onDismiss = { 
                pendingPhoto = null
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (photoLocationMode)
                            "Фото с координатами: ${currentPath.substringAfterLast("/", "Файлы")}"
                        else
                            currentPath.substringAfterLast("/", "Файлы"),
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
                },
                actions = {
                    // === Кнопка закрытия: всегда возвращает на экран хранилища ===
                    IconButton(onClick = onBack, enabled = !isProcessing) {
                        Icon(Icons.Default.Close, "Закрыть")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                files.isEmpty() -> Text(
                    if (photoLocationMode) "В папке нет изображений" else "Папка пуста",
                    Modifier.align(Alignment.Center).padding(16.dp)
                )
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { file ->
                        Card(
                            onClick = {
                                if (file.isDirectory) {
                                    currentPath = file.path
                                } else if (photoLocationMode) {
                                    // === Режим фото-точки: распознаём координаты и показываем диалог ===
                                    isProcessing = true
                                    progressText = "Распознавание координат..."
                                    scope.launch {
                                        try {
                                            val uri = Uri.fromFile(File(file.path))
                                            val preview = withContext(Dispatchers.IO) { loadThumbnail(file.path, 800) }
                                            val coords = withContext(Dispatchers.IO) {
                                                CoordinateRecognizer.recognize(context, uri)
                                            }
                                            pendingPhoto = PhotoDraft(
                                                uri = uri,
                                                fileName = file.name,
                                                preview = preview,
                                                latText = coords?.let { String.format(Locale.US, "%.6f", it.latitude) } ?: "",
                                                lonText = coords?.let { String.format(Locale.US, "%.6f", it.longitude) } ?: "",
                                                source = coords?.source
                                            )
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                } else {
                                    // === Обычный режим: импорт файла в хранилище ===
                                    isProcessing = true
                                    progressText = "Шифрование и сохранение..."
                                    scope.launch {
                                        try {
                                            val fileUri = Uri.fromFile(File(file.path))
                                            val result = withContext(Dispatchers.IO) {
                                                appContainer.addFileUseCase(fileUri, file.name, file.mimeType, folderId)
                                            }
                                            if (result.isSuccess) {
                                                handleOriginalFile(context, fileUri, appContainer)
                                                appContainer.requestVaultRefresh()
                                                onBack()
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        "Ошибка: ${result.exceptionOrNull()?.message ?: "неизвестно"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
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
                                when {
                                    file.isDirectory -> Icon(
                                        Icons.Default.Folder, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp).padding(end = 12.dp)
                                    )
                                    file.mimeType.startsWith("image/") -> ThumbImage(
                                        file.path,
                                        Modifier.size(48.dp).padding(end = 12.dp)
                                    )
                                    else -> {
                                        val icon = when {
                                            file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                                            FileTypeUtils.isTextFile(file.name, file.mimeType) -> Icons.Default.Description
                                            else -> Icons.Default.InsertDriveFile
                                        }
                                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(48.dp).padding(end = 12.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    if (!file.isDirectory) Text(
                                        formatSize(file.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (photoLocationMode && !file.isDirectory) {
                                    Icon(
                                        Icons.Default.AddLocation, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
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

// ==================== Диалог фото-точки ====================

@Composable
private fun PhotoLocationDialog(
    draft: PhotoDraft,
    onSave: (name: String, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(draft.fileName.substringBeforeLast(".")) }
    var latText by remember { mutableStateOf(draft.latText) }
    var lonText by remember { mutableStateOf(draft.lonText) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая фото-точка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Превью фото
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = draft.preview
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Фото",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Icon(Icons.Default.Image, null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Text(
                    when (draft.source) {
                        "EXIF" -> "✅ Координаты из EXIF фото"
                        "OCR" -> "✅ Координаты распознаны с фото (OCR)"
                        else -> "⚠️ Координаты не найдены — введите вручную"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.source == null)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Название точки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it; error = null },
                        label = { Text("Широта") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it; error = null },
                        label = { Text("Долгота") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latText.replace(',', '.').toDoubleOrNull()
                    val lon = lonText.replace(',', '.').toDoubleOrNull()
                    when {
                        name.isBlank() -> error = "Введите название"
                        lat == null || lon == null -> error = "Некорректные координаты"
                        lat !in -90.0..90.0 || lon !in -180.0..180.0 -> error = "Координаты вне диапазона"
                        else -> onSave(name.trim(), lat, lon)
                    }
                }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ==================== Миниатюры ====================

@Composable
private fun ThumbImage(path: String, modifier: Modifier) {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bmp = withContext(Dispatchers.IO) { loadThumbnail(path, 160) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.Image, null, Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loadThumbnail(path: String, target: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }
} catch (e: Exception) {
    null
}

// ==================== Служебное ====================

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

private fun isImageName(name: String): Boolean =
    name.substringAfterLast(".", "").lowercase() in
            listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

private suspend fun loadFiles(path: String, imagesOnly: Boolean): List<PickerFileItem> = withContext(Dispatchers.IO) {
    val list = mutableListOf<PickerFileItem>()
    val dir = File(path)
    if (dir.exists() && dir.isDirectory && dir.canRead()) {
        dir.listFiles()?.forEach { f ->
            if (!f.name.startsWith(".")) {
                if (f.isDirectory || !imagesOnly || isImageName(f.name)) {
                    list.add(PickerFileItem(
                        f.name, f.absolutePath,
                        if (f.isDirectory) "inode/directory" else guessMime(f.name),
                        f.isDirectory, if (f.isDirectory) 0 else f.length()
                    ))
                }
            }
        }
    }
    list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

private fun guessMime(name: String): String = when (name.substringAfterLast(".", "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "bmp" -> "image/bmp"; "heic", "heif" -> "image/heic"
    "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"
    "pdf" -> "application/pdf"; "zip" -> "application/zip"
    "txt", "md" -> "text/plain"; "py" -> "text/x-python"; "json" -> "application/json"
    else -> "application/octet-stream"
}

private fun formatSize(size: Long): String =
    if (size < 1024) "$size B" else if (size < 1024 * 1024) "${size / 1024} KB" else "${size / (1024 * 1024)} MB"