package com.google.android.calculator.presentation.filepicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.utils.CoordinateRecognizer
import com.google.android.calculator.utils.FileTypeUtils
import com.google.android.calculator.utils.FolderExporter
import com.google.android.calculator.utils.OriginalFileAction
import com.google.android.calculator.utils.SecureDelete
import kotlinx.coroutines.CoroutineScope
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

private data class PhotoDraft(
    val uri: Uri,
    val fileName: String,
    val preview: Bitmap?,
    val latText: String,
    val lonText: String,
    val source: String?
)

private enum class FileFilter { ALL, IMAGES, DOCUMENTS, ZIP }
private enum class ViewMode { LIST, GALLERY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    appContainer: AppContainer,
    initialPath: String,
    folderId: String?,
    onBack: () -> Unit,
    photoLocationMode: Boolean = false,
    importMode: Boolean = false,
    importFolderMode: Boolean = false,
    folderPickerMode: Boolean = false,
    onFolderPicked: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(initialPath) }
    var allFiles by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var pendingPhoto by remember { mutableStateOf<PhotoDraft?>(null) }
    var photoQueue by remember { mutableStateOf<List<PickerFileItem>>(emptyList()) }
    var isImportingFolder by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    var filter by remember {
        mutableStateOf(
            when {
                importMode -> FileFilter.ZIP
                folderPickerMode -> FileFilter.ALL
                importFolderMode -> FileFilter.ALL
                photoLocationMode -> FileFilter.IMAGES
                else -> FileFilter.ALL
            }
        )
    }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }

    val scope = rememberCoroutineScope()

    val isSearchMode = filter != FileFilter.ALL
    val displayFiles = if (isSearchMode) searchResults else allFiles

    // ⭐ ИСПРАВЛЕНО: используем MediaStore для быстрого поиска
    LaunchedEffect(currentPath, filter) {
        isLoading = true
        if (filter == FileFilter.ALL) {
            // Для локальной навигации используем старый метод
            allFiles = withContext(Dispatchers.IO) { loadFiles(currentPath) }
            searchResults = emptyList()
        } else {
            // ⭐ Для фильтров используем MediaStore (быстро!)
            searchResults = withContext(Dispatchers.IO) {
                loadFilesFromMediaStore(context, filter, currentPath)
            }
            allFiles = emptyList()
        }
        isLoading = false
    }

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

                            val remaining = photoQueue
                            if (remaining.isNotEmpty()) {
                                val next = remaining.first()
                                photoQueue = remaining.drop(1)
                                progressText = "Распознавание координат..."
                                pendingPhoto = withContext(Dispatchers.IO) {
                                    buildPhotoDraft(context, next)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "📍 Точка «$name» создана",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                onBack()
                            }
                        }.onFailure { e ->
                            pendingPhoto = null
                            photoQueue = emptyList()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } finally {
                        isProcessing = false
                        progressText = ""
                    }
                }
            },
            onDismiss = {
                pendingPhoto = null
                photoQueue = emptyList()
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                selectionMode -> "Выбрано: ${selectedPaths.size}"
                                folderPickerMode -> "Выбор папки"
                                importFolderMode -> "Импорт папки"
                                importMode -> "Импорт ZIP"
                                photoLocationMode -> "Выбор фото"
                                else -> currentPath.substringAfterLast("/", "Файлы")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                selectionMode = false
                                selectedPaths = emptySet()
                            }
                        ) {
                            Icon(Icons.Default.Close, "Отменить выбор")
                        }
                    } else {
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
                },
                actions = {
                    when {
                        folderPickerMode -> {
                            Row {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.Default.Close, "Закрыть")
                                }
                                IconButton(
                                    onClick = {
                                        onFolderPicked?.invoke(currentPath)
                                        onBack()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        "Выбрать эту папку",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        importFolderMode -> {
                            Row {
                                IconButton(
                                    onClick = {
                                        isImportingFolder = true
                                        scope.launch {
                                            val count = FolderExporter.importFolderFromPath(
                                                sourcePath = currentPath,
                                                targetFolderId = folderId,
                                                vaultRepository = appContainer.vaultRepository,
                                                folderRepository = appContainer.folderRepository
                                            )
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "✅ Импортировано файлов: $count",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                appContainer.requestVaultRefresh()
                                            }
                                            isImportingFolder = false
                                            onBack()
                                        }
                                    },
                                    enabled = !isImportingFolder
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        "Импортировать эту папку",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = onBack) {
                                    Icon(Icons.Default.Close, "Закрыть")
                                }
                            }
                        }
                        importMode -> {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.Close, "Закрыть")
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = { viewMode = if (viewMode == ViewMode.LIST) ViewMode.GALLERY else ViewMode.LIST },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                                    if (viewMode == ViewMode.LIST) "Галерея" else "Список"
                                )
                            }

                            IconButton(
                                onClick = {
                                    selectionMode = !selectionMode
                                    if (!selectionMode) selectedPaths = emptySet()
                                },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    Icons.Default.Checklist,
                                    if (selectionMode) "Выйти из выбора" else "Выбрать несколько",
                                    tint = if (selectionMode)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (!selectionMode) {
                                IconButton(onClick = onBack, enabled = !isProcessing) {
                                    Icon(Icons.Default.Close, "Закрыть")
                                }
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!importMode && !folderPickerMode && !importFolderMode && selectionMode && selectedPaths.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val items = selectedPaths.toList()
                        scope.launch {
                            processBulkSelection(
                                context = context,
                                paths = items,
                                appContainer = appContainer,
                                folderId = folderId,
                                photoLocationMode = photoLocationMode,
                                onSetProgress = { t ->
                                    progressText = t
                                    isProcessing = t.isNotEmpty()
                                },
                                onPhotoDraft = { d, queue ->
                                    pendingPhoto = d
                                    photoQueue = queue
                                },
                                onDone = {
                                    selectedPaths = emptySet()
                                    selectionMode = false
                                    onBack()
                                }
                            )
                        }
                    },
                    modifier = Modifier.navigationBarsPadding().padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = {
                        Icon(
                            if (photoLocationMode) Icons.Default.AddLocation else Icons.Default.Add,
                            null
                        )
                    },
                    text = {
                        Text(
                            if (photoLocationMode) "Фото-точки: ${selectedPaths.size}"
                            else "Добавить: ${selectedPaths.size}"
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            if (!photoLocationMode && !importMode && !importFolderMode && !folderPickerMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == FileFilter.ALL,
                        onClick = { filter = FileFilter.ALL },
                        label = { Text("Все") },
                        leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = filter == FileFilter.IMAGES,
                        onClick = { filter = FileFilter.IMAGES },
                        label = { Text("Фото") },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = filter == FileFilter.DOCUMENTS,
                        onClick = { filter = FileFilter.DOCUMENTS },
                        label = { Text("Документы") },
                        leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) }
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    displayFiles.isEmpty() && !isLoading -> {
                        // Пустой список
                    }
                    viewMode == ViewMode.LIST -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayFiles, key = { it.path }) { file ->
                                ListItemCard(
                                    file = file,
                                    subtitle = if (isSearchMode) relativePath(currentPath, file.path) else null,
                                    isSelected = selectedPaths.contains(file.path),
                                    selectionMode = selectionMode,
                                    photoLocationMode = photoLocationMode,
                                    onClick = {
                                        handleFileClick(
                                            file = file,
                                            selectionMode = selectionMode,
                                            selectedPaths = selectedPaths,
                                            onSelectionChange = { selectedPaths = it },
                                            onNavigate = { newPath ->
                                                currentPath = newPath
                                                if (!importMode && !folderPickerMode && !importFolderMode) filter = FileFilter.ALL
                                            },
                                            photoLocationMode = photoLocationMode,
                                            importMode = importMode,
                                            context = context,
                                            appContainer = appContainer,
                                            folderId = folderId,
                                            scope = scope,
                                            onProcessingChange = { p, t ->
                                                isProcessing = p
                                                progressText = t
                                            },
                                            onPhotoDraft = { d -> pendingPhoto = d },
                                            onBack = onBack
                                        )
                                    },
                                    onCheckboxChange = { checked ->
                                        selectedPaths = if (checked)
                                            selectedPaths + file.path
                                        else
                                            selectedPaths - file.path
                                    }
                                )
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayFiles, key = { it.path }) { file ->
                                GalleryItem(
                                    file = file,
                                    isSelected = selectedPaths.contains(file.path),
                                    selectionMode = selectionMode,
                                    onClick = {
                                        handleFileClick(
                                            file = file,
                                            selectionMode = selectionMode,
                                            selectedPaths = selectedPaths,
                                            onSelectionChange = { selectedPaths = it },
                                            onNavigate = { newPath ->
                                                currentPath = newPath
                                                if (!importMode && !folderPickerMode && !importFolderMode) filter = FileFilter.ALL
                                            },
                                            photoLocationMode = photoLocationMode,
                                            importMode = importMode,
                                            context = context,
                                            appContainer = appContainer,
                                            folderId = folderId,
                                            scope = scope,
                                            onProcessingChange = { p, t ->
                                                isProcessing = p
                                                progressText = t
                                            },
                                            onPhotoDraft = { d -> pendingPhoto = d },
                                            onBack = onBack
                                        )
                                    },
                                    onLongClick = {
                                        if (!selectionMode && !importMode && !folderPickerMode && !importFolderMode) {
                                            selectionMode = true
                                            selectedPaths = setOf(file.path)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (isProcessing || isImportingFolder) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(Modifier.padding(32.dp)) {
                            Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    if (isImportingFolder) "Импорт папки..." else progressText,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            BottomStatusPanel(
                isLoading = isLoading,
                isEmpty = displayFiles.isEmpty() && !isLoading,
                isSearchMode = isSearchMode,
                selectionMode = selectionMode,
                selectedCount = selectedPaths.size,
                fileCount = displayFiles.size,
                filter = filter,
                importFolderMode = importFolderMode,
                folderPickerMode = folderPickerMode,
                importMode = importMode,
                isImporting = isImportingFolder
            )
        }
    }
}

@Composable
private fun BottomStatusPanel(
    isLoading: Boolean,
    isEmpty: Boolean,
    isSearchMode: Boolean,
    selectionMode: Boolean,
    selectedCount: Int,
    fileCount: Int,
    filter: FileFilter,
    importFolderMode: Boolean,
    folderPickerMode: Boolean,
    importMode: Boolean,
    isImporting: Boolean = false
) {
    val (icon, text, showProgress) = when {
        isImporting -> Triple(Icons.Default.Download, "Импортируется, пожалуйста, подождите...", true)
        isLoading -> Triple(Icons.Default.Search, "Загрузка...", true)
        isEmpty -> {
            val msg = when {
                importMode -> "Нет ZIP-файлов в этой папке и подпапках"
                isSearchMode -> "Ничего не найдено в этой папке и подпапках"
                else -> "Папка пуста"
            }
            Triple(Icons.Default.Info, msg, false)
        }
        selectionMode -> Triple(
            Icons.Default.Checklist,
            "Выбрано: $selectedCount • Нажмите ➕ для добавления",
            false
        )
        importFolderMode -> Triple(
            Icons.Default.Folder,
            "Файлов в папке: $fileCount • Нажмите 📥 для импорта",
            false
        )
        folderPickerMode -> Triple(
            Icons.Default.Folder,
            "Нажмите ✓, чтобы выбрать эту папку",
            false
        )
        isSearchMode -> {
            val label = when (filter) {
                FileFilter.IMAGES -> "фото"
                FileFilter.DOCUMENTS -> "документов"
                FileFilter.ZIP -> "архивов"
                FileFilter.ALL -> "файлов"
            }
            Triple(
                Icons.Default.Search,
                "Найдено: $fileCount $label • включая подпапки",
                false
            )
        }
        else -> Triple(Icons.Default.Folder, "Файлов: $fileCount", false)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (selectionMode || isImporting) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    icon, null,
                    modifier = Modifier.size(16.dp),
                    tint = if (selectionMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (selectionMode || isImporting) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun relativePath(basePath: String, filePath: String): String {
    val rel = filePath.removePrefix(basePath).trimStart('/')
    val dir = rel.substringBeforeLast('/', "")
    return if (dir.isEmpty()) "" else "📁 $dir"
}

@Composable
private fun ListItemCard(
    file: PickerFileItem,
    subtitle: String?,
    isSelected: Boolean,
    selectionMode: Boolean,
    photoLocationMode: Boolean,
    onClick: () -> Unit,
    onCheckboxChange: (Boolean) -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = onCheckboxChange)
                Spacer(Modifier.width(4.dp))
            }

            when {
                file.isDirectory -> Icon(
                    Icons.Default.Folder, null,
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp).padding(end = 12.dp)
                )
                file.mimeType.startsWith("image/") -> ThumbImage(
                    file.path,
                    Modifier.size(40.dp).padding(end = 12.dp)
                )
                isZipName(file.name) -> Icon(
                    Icons.Default.FolderZip, null,
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp).padding(end = 12.dp)
                )
                else -> {
                    val icon = when {
                        file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                        FileTypeUtils.isTextFile(file.name, file.mimeType) -> Icons.Default.Description
                        else -> Icons.Default.InsertDriveFile
                    }
                    Icon(icon, null,
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp).padding(end = 12.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (!file.isDirectory) {
                    val sizeText = formatSize(file.size)
                    Text(
                        if (subtitle.isNullOrEmpty()) sizeText else "$subtitle • $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (file.isDirectory && selectionMode) {
                    Text(
                        "Выбор папки = все файлы рекурсивно",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (photoLocationMode && !file.isDirectory && !selectionMode) {
                Icon(
                    Icons.Default.AddLocation, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryItem(
    file: PickerFileItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        when {
            file.isDirectory -> {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            file.mimeType.startsWith("image/") -> {
                ThumbImage(file.path, Modifier.fillMaxSize())
            }
            else -> {
                val icon = when {
                    isZipName(file.name) -> Icons.Default.FolderZip
                    file.mimeType.startsWith("video/") -> Icons.Default.Videocam
                    FileTypeUtils.isTextFile(file.name, file.mimeType) -> Icons.Default.Description
                    else -> Icons.Default.InsertDriveFile
                }
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        icon,
                        null,
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (selectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

private fun handleFileClick(
    file: PickerFileItem,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onNavigate: (String) -> Unit,
    photoLocationMode: Boolean,
    importMode: Boolean,
    context: Context,
    appContainer: AppContainer,
    folderId: String?,
    scope: CoroutineScope,
    onProcessingChange: (Boolean, String) -> Unit,
    onPhotoDraft: (PhotoDraft) -> Unit,
    onBack: () -> Unit
) {
    val isSelected = selectedPaths.contains(file.path)

    when {
        selectionMode -> onSelectionChange(
            if (isSelected) selectedPaths - file.path
            else selectedPaths + file.path
        )
        file.isDirectory -> onNavigate(file.path)
        importMode -> {
            if (isZipName(file.name)) {
                onProcessingChange(true, "Распаковка и шифрование...")
                scope.launch {
                    try {
                        val zipUri = Uri.fromFile(File(file.path))
                        val count = FolderExporter.importFolder(
                            context = context,
                            zipUri = zipUri,
                            targetFolderId = folderId,
                            vaultRepository = appContainer.vaultRepository,
                            folderRepository = appContainer.folderRepository
                        )
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "✅ Импортировано файлов: $count",
                                Toast.LENGTH_LONG
                            ).show()
                            appContainer.requestVaultRefresh()
                        }
                        onBack()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        onProcessingChange(false, "")
                    }
                }
            }
        }
        photoLocationMode -> {
            onProcessingChange(true, "Распознавание координат...")
            scope.launch {
                try {
                    val draft = withContext(Dispatchers.IO) {
                        buildPhotoDraft(context, file)
                    }
                    onPhotoDraft(draft)
                } finally {
                    onProcessingChange(false, "")
                }
            }
        }
        else -> {
            onProcessingChange(true, "Шифрование и сохранение...")
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
                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG)
                    }
                } finally {
                    onProcessingChange(false, "")
                }
            }
        }
    }
}

private suspend fun buildPhotoDraft(context: Context, file: PickerFileItem): PhotoDraft =
    withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(File(file.path))
        val preview = loadThumbnail(file.path, 800)
        val coords = CoordinateRecognizer.recognize(context, uri)
        PhotoDraft(
            uri = uri,
            fileName = file.name,
            preview = preview,
            latText = coords?.let { String.format(Locale.US, "%.6f", it.latitude) } ?: "",
            lonText = coords?.let { String.format(Locale.US, "%.6f", it.longitude) } ?: "",
            source = coords?.source
        )
    }

// ⭐ НОВАЯ ФУНКЦИЯ: быстрый поиск через MediaStore API
private fun loadFilesFromMediaStore(
    context: Context,
    filter: FileFilter,
    basePath: String
): List<PickerFileItem> {
    val results = mutableListOf<PickerFileItem>()
    val contentResolver = context.contentResolver
    
    // Определяем URI и фильтр в зависимости от типа
    val (uri, selection, selectionArgs) = when (filter) {
        FileFilter.IMAGES -> {
            Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null,
                null
            )
        }
        FileFilter.DOCUMENTS -> {
            Triple(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
                arrayOf("text/%", "application/pdf", "application/msword")
            )
        }
        FileFilter.ZIP -> {
            Triple(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%.zip")
            )
        }
        FileFilter.ALL -> {
            // Не должно вызываться для ALL
            return results
        }
    }
    
    val projection = arrayOf(
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE
    )
    
    val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"
    
    try {
        contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val path = cursor.getString(pathColumn)
                val mime = cursor.getString(mimeColumn) ?: "application/octet-stream"
                val size = cursor.getLong(sizeColumn)
                
                // Фильтруем по базовому пути (если указан)
                if (path.startsWith(basePath)) {
                    results.add(PickerFileItem(name, path, mime, false, size))
                }
            }
        }
    } catch (e: Exception) {
        // Если MediaStore недоступен, fallback на старый метод
        val out = mutableListOf<PickerFileItem>()
        collectFilesFiltered(File(basePath), filter, out)
        return out.sortedBy { it.name.lowercase() }
    }
    
    return results.sortedBy { it.name.lowercase() }
}

private fun collectFilesFiltered(
    dir: File,
    filter: FileFilter,
    out: MutableList<PickerFileItem>
) {
    dir.listFiles()?.forEach { f ->
        if (f.name.startsWith(".")) return@forEach
        if (f.isDirectory) {
            collectFilesFiltered(f, filter, out)
        } else {
            val mime = guessMime(f.name)
            val matches = when (filter) {
                FileFilter.IMAGES -> mime.startsWith("image/") || isImageName(f.name)
                FileFilter.DOCUMENTS -> FileTypeUtils.isTextFile(f.name, mime) || mime == "application/pdf"
                FileFilter.ZIP -> isZipName(f.name)
                FileFilter.ALL -> true
            }
            if (matches) {
                out.add(PickerFileItem(f.name, f.absolutePath, mime, false, f.length()))
            }
        }
    }
}

private suspend fun processBulkSelection(
    context: Context,
    paths: List<String>,
    appContainer: AppContainer,
    folderId: String?,
    photoLocationMode: Boolean,
    onSetProgress: (String) -> Unit,
    onPhotoDraft: (PhotoDraft, List<PickerFileItem>) -> Unit,
    onDone: () -> Unit
) = withContext(Dispatchers.IO) {
    val allFiles = mutableListOf<PickerFileItem>()
    paths.forEach { path ->
        val f = File(path)
        if (f.isDirectory) {
            collectFiles(f, photoLocationMode, allFiles)
        } else if (f.exists()) {
            allFiles.add(
                PickerFileItem(
                    name = f.name,
                    path = f.absolutePath,
                    mimeType = guessMime(f.name),
                    isDirectory = false,
                    size = f.length()
                )
            )
        }
    }

    if (allFiles.isEmpty()) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Нет файлов для добавления", Toast.LENGTH_SHORT).show()
        }
        onDone()
        return@withContext
    }

    if (photoLocationMode) {
        val first = allFiles.first()
        val rest = allFiles.drop(1)
        withContext(Dispatchers.Main) { onSetProgress("Распознавание координат...") }
        val draft = buildPhotoDraft(context, first)
        withContext(Dispatchers.Main) {
            onSetProgress("")
            onPhotoDraft(draft, rest)
        }
        return@withContext
    }

    var ok = 0
    var errors = 0
    allFiles.forEachIndexed { index, file ->
        withContext(Dispatchers.Main) {
            onSetProgress("Шифрование... ${index + 1}/${allFiles.size}")
        }
        val uri = Uri.fromFile(File(file.path))
        val result = appContainer.addFileUseCase(uri, file.name, file.mimeType, folderId)
        if (result.isSuccess) {
            handleOriginalFile(context, uri, appContainer)
            ok++
        } else {
            errors++
        }
    }

    appContainer.requestVaultRefresh()
    withContext(Dispatchers.Main) {
        onSetProgress("")
        Toast.makeText(
            context,
            if (errors == 0) "✅ Добавлено файлов: $ok"
            else "Добавлено: $ok, ошибок: $errors",
            Toast.LENGTH_LONG
        ).show()
        onDone()
    }
}

private fun collectFiles(
    dir: File,
    imagesOnly: Boolean,
    out: MutableList<PickerFileItem>
) {
    dir.listFiles()?.forEach { f ->
        if (f.name.startsWith(".")) return@forEach
        if (f.isDirectory) {
            collectFiles(f, imagesOnly, out)
        } else if (!imagesOnly || isImageName(f.name)) {
            out.add(
                PickerFileItem(
                    name = f.name,
                    path = f.absolutePath,
                    mimeType = guessMime(f.name),
                    isDirectory = false,
                    size = f.length()
                )
            )
        }
    }
}

@Composable
private fun PhotoLocationDialog(
    draft: PhotoDraft,
    onSave: (name: String, lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(draft.fileName) { mutableStateOf(draft.fileName.substringBeforeLast(".")) }
    var latText by remember(draft.fileName) { mutableStateOf(draft.latText) }
    var lonText by remember(draft.fileName) { mutableStateOf(draft.lonText) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая фото-точка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

private fun isZipName(name: String): Boolean =
    name.substringAfterLast('.', "").equals("zip", ignoreCase = true)

private suspend fun handleOriginalFile(
    context: Context,
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
    "bmp" -> "image/bmp"; "heic", "heif" -> "image/heic"
    "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"
    "pdf" -> "application/pdf"; "zip" -> "application/zip"
    "txt", "md" -> "text/plain"; "py" -> "text/x-python"; "json" -> "application/json"
    else -> "application/octet-stream"
}

private fun formatSize(size: Long): String =
    if (size < 1024) "$size Б" else if (size < 1024 * 1024) "${size / 1024} КБ" else "${size / (1024 * 1024)} МБ"