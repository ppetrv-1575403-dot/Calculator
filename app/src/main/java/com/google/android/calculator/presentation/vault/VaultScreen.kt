package com.google.android.calculator.presentation.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.calculator.BuildConfig
import com.google.android.calculator.R
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.FolderExporter
import com.google.android.calculator.utils.PermissionUtils
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onNavigateToPicker: (String) -> Unit,
    onAddLocation: (String) -> Unit,
    onOpenMap: (String) -> Unit,
    onPreviewFile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onEditLocation: (String) -> Unit,
    onAddPhotoLocation: (String) -> Unit,
    onImportZip: (String) -> Unit,
    onImportFolder: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember {
        VaultViewModel(appContainer.addFileUseCase, appContainer.getFilesUseCase, appContainer.vaultRepository, appContainer)
    }
    val scope = rememberCoroutineScope()
    var fabExpanded by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var showLogsDialog by remember { mutableStateOf(false) }
    
    var fileToRename by remember { mutableStateOf<VaultFile?>(null) }
    var newFileName by remember { mutableStateOf("") }
    var folderToRename by remember { mutableStateOf<VaultFolder?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var locationToEdit by remember { mutableStateOf<SavedLocation?>(null) }
    
    var hasStoragePermission by remember { 
        mutableStateOf(PermissionUtils.hasManageExternalStorage()) 
    }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = PermissionUtils.hasManageExternalStorage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    var fileToDelete by remember { mutableStateOf<VaultFile?>(null) }
    var locationToDelete by remember { mutableStateOf<SavedLocation?>(null) }
    var folderToDelete by remember { mutableStateOf<VaultFolder?>(null) }

    var showExportDialog by remember { mutableStateOf<VaultFolder?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val currentFolderId = viewModel.currentFolder?.id ?: ""

    val permissionSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appContainer.setFileOperationInProgress(false)
        hasStoragePermission = PermissionUtils.hasManageExternalStorage()
    }

    val isDecoyMode = VaultManager.currentMode == VaultMode.DECOY
    
    LaunchedEffect(Unit) { appContainer.vaultRefresh.collect { viewModel.refresh() } }

    val currentSlotId = VaultManager.currentSlotId
    val isContainerEnabled = remember { VaultManager.containerEnabled(currentSlotId) }
    val isContainerValid = remember(isContainerEnabled, currentSlotId) {
        if (isContainerEnabled) VaultManager.isContainerValid(currentSlotId) else true
    }

    var showMissingContainerDialog by remember { mutableStateOf(!isContainerValid) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        appContainer.setFileOperationInProgress(false)
        if (uri != null && viewModel.fileToExport != null) {
            scope.launch {
                val tempFile = appContainer.vaultRepository.decryptToTempFile(viewModel.fileToExport!!.encryptedFileName)
                if (tempFile != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                    tempFile.delete()
                }
                viewModel.clearExportState()
            }
        } else viewModel.clearExportState()
    }

    LaunchedEffect(viewModel.fileToExport) {
        val fileToExport = viewModel.fileToExport
        if (fileToExport != null) {
            if (hasStoragePermission) {
                scope.launch {
                    appContainer.setFileOperationInProgress(true)
                    try {
                        val tempFile = appContainer.vaultRepository.decryptToTempFile(fileToExport.encryptedFileName)
                        if (tempFile != null && tempFile.exists()) {
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) downloadDir.mkdirs()
                            var destFile = File(downloadDir, fileToExport.originalName)
                            var counter = 1
                            while (destFile.exists()) {
                                destFile = File(downloadDir, "${destFile.nameWithoutExtension}_($counter).${destFile.extension}")
                                counter++
                            }
                            withContext(Dispatchers.IO) { tempFile.inputStream().use { i -> destFile.outputStream().use { o -> i.copyTo(o) } } }
                            tempFile.delete()
                            withContext(Dispatchers.Main) { Toast.makeText(context, "✅ Сохранено в Загрузки", Toast.LENGTH_LONG).show() }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "❌ ${e.message}", Toast.LENGTH_LONG).show() }
                    } finally {
                        viewModel.clearExportState()
                        appContainer.setFileOperationInProgress(false)
                    }
                }
            } else {
                appContainer.setFileOperationInProgress(true)
                exportLauncher.launch(fileToExport.originalName)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                if (fabExpanded) {
                    MiniFab(Icons.Default.Add, "Файл") {
                        fabExpanded = false
                        if (hasStoragePermission) {
                            onNavigateToPicker(currentFolderId)
                        } else {
                            appContainer.setFileOperationInProgress(true)
                            permissionSettingsLauncher.launch(
                                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    }

                    if (!isDecoyMode) {
                        MiniFab(Icons.Default.AddLocation, "Точка") { fabExpanded = false; onAddLocation(currentFolderId) }
                        Spacer(Modifier.height(8.dp))
                    
                        MiniFab(Icons.Default.AddAPhoto, "Фото-точка") { fabExpanded = false; onAddPhotoLocation(currentFolderId) }
                        Spacer(Modifier.height(8.dp))
                    }
                    
                    MiniFab(Icons.Default.CreateNewFolder, "Папка") { fabExpanded = false; folderName = ""; showCreateFolderDialog = true }
                    Spacer(Modifier.height(8.dp))
                    
                    MiniFab(Icons.Default.Download, "Импорт ZIP") { fabExpanded = false; onImportZip(currentFolderId) }
                    Spacer(Modifier.height(8.dp))
                    
                    MiniFab(Icons.Default.Folder, "Импорт папки") { fabExpanded = false; onImportFolder() }
                    Spacer(Modifier.height(8.dp))
                }
                
                FloatingActionButton(
                    onClick = { fabExpanded = !fabExpanded },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(if (fabExpanded) Icons.Default.Close else Icons.Default.Add, "Добавить")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(paddingValues).padding(16.dp)) {

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (viewModel.itemToMove != null) {
                        viewModel.cancelMove()
                    } else if (!viewModel.navigateUpFolder()) {
                        onBack()
                    }
                }) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.ArrowBack, "Назад")
                }

                if (viewModel.currentFolder != null && viewModel.itemToMove == null) {
                    IconButton(onClick = { viewModel.navigateToRoot() }) {
                        Icon(Icons.Default.Home, "В корень хранилища")
                    }
                }
                
                Text(
                    viewModel.itemToMove?.let { "Выберите папку для перемещения" }
                        ?: (viewModel.currentFolder?.name ?: stringResource(R.string.vault_title)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (viewModel.itemToMove != null) {
                    TextButton(onClick = { viewModel.cancelMove() }) { Text("Отмена") }
                } else {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = { showLogsDialog = true }) {
                            Icon(Icons.Default.Description, "Логи")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                }
            }

            when {
                !hasStoragePermission -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Нужен доступ ко всем файлам", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = {
                                appContainer.setFileOperationInProgress(true)
                                permissionSettingsLauncher.launch(
                                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }) { Text("Открыть настройки") }
                        }
                    }
                }
                viewModel.items.isEmpty() && viewModel.itemToMove == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (viewModel.isLoading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                stringResource(R.string.vault_empty), 
                                color = MaterialTheme.colorScheme.onSurfaceVariant, 
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.navigationBarsPadding().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(viewModel.items) { item ->
                            when (item) {
                                is VaultItem.FolderItem -> FolderListItem(
                                    folder = item.folder,
                                    isMoveTarget = viewModel.itemToMove != null,
                                    onOpen = {
                                        if (viewModel.itemToMove != null) {
                                            viewModel.moveToFolder(item.folder)
                                        } else {
                                            viewModel.openFolder(item.folder)
                                        }
                                    },
                                    onDelete = { folderToDelete = item.folder },
                                    onExport = { showExportDialog = item.folder },
                                    onRename = {
                                        folderToRename = item.folder
                                        newFolderName = item.folder.name
                                    }
                                )
                                is VaultItem.LocationItem -> LocationListItem(
                                    location = item.location,
                                    isSelectedForMove = viewModel.itemToMove == item,
                                    moveMode = viewModel.itemToMove != null,
                                    onOpenMap = { if (viewModel.itemToMove == null) onOpenMap(item.location.id) },
                                    onMove = { viewModel.startMove(item) },
                                    onDelete = { locationToDelete = item.location },
                                    onEdit = { locationToEdit = item.location }
                                )
                                is VaultItem.FileItem -> FileListItem(
                                    file = item.file,
                                    isSelectedForMove = viewModel.itemToMove == item,
                                    moveMode = viewModel.itemToMove != null,
                                    onPreview = { if (viewModel.itemToMove == null) onPreviewFile(item.file.id) },
                                    onExport = { viewModel.prepareExport(item.file) },
                                    onMove = { viewModel.startMove(item) },
                                    onDelete = { fileToDelete = item.file },
                                    onRename = {
                                        fileToRename = item.file
                                        newFileName = item.file.originalName
                                    }
                                )
                            }
                        }
                    }

                    if (viewModel.itemToMove != null && viewModel.currentFolder != null) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.moveToParentFolder() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Icon(Icons.Default.ArrowUpward, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Переместить на уровень выше")
                            }
                            if (viewModel.currentFolder?.parentId != null) {
                                Button(
                                    onClick = { viewModel.moveToFolder(null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Icon(Icons.Default.Home, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Переместить в корень хранилища")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMissingContainerDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Контейнер не найден") },
            text = { 
                Text("Файл хранилища был перемещен или удален.\nПожалуйста, укажите новый путь к файлу или отключите использование контейнера в настройках.") 
            },
            confirmButton = {
                TextButton(onClick = { 
                    showMissingContainerDialog = false
                    onOpenSettings() 
                }) { 
                    Text("В настройки") 
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    VaultManager.setContainerEnabled(currentSlotId, false)
                    showMissingContainerDialog = false
                    appContainer.invalidateCacheForSlot(currentSlotId)
                }) { 
                    Text("Отключить контейнер") 
                }
            }
        )
    }

    showExportDialog?.let { folder ->
        AlertDialog(
            onDismissRequest = { showExportDialog = null },
            title = { Text("Экспорт папки") },
            text = { Text("Экспортировать папку «${folder.name}» со всем содержимым в ZIP-архив?\n\nФайл будет сохранён в Downloads.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = null
                        isExporting = true
                        scope.launch {
                            val uri = FolderExporter.exportFolder(
                                context = context,
                                folder = folder,
                                vaultRepository = appContainer.vaultRepository,
                                folderRepository = appContainer.folderRepository
                            )
                            withContext(Dispatchers.Main) {
                                if (uri != null) {
                                    Toast.makeText(context, "✅ Экспортировано в Downloads", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "❌ Ошибка экспорта", Toast.LENGTH_LONG).show()
                                }
                            }
                            isExporting = false
                        }
                    }
                ) { Text("Экспортировать") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = null }) { Text("Отмена") } }
        )
    }

    if (isExporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Экспорт папки") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Экспортируется, пожалуйста, подождите...\nЭто может занять некоторое время для больших файлов.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Новая папка") },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Название папки") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) viewModel.createFolder(folderName.trim())
                    showCreateFolderDialog = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Отмена") } }
        )
    }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Удалить файл?") },
            text = { Text("Файл \"${fileToDelete!!.originalName}\" будет удалён безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(fileToDelete!!)
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Отмена") } }
        )
    }

    if (locationToDelete != null) {
        AlertDialog(
            onDismissRequest = { locationToDelete = null },
            title = { Text("Удалить точку?") },
            text = { Text("Точка \"${locationToDelete!!.name}\" будет удалена безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLocation(locationToDelete!!.id)
                        locationToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { locationToDelete = null }) { Text("Отмена") } }
        )
    }

    if (folderToDelete != null) {
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Удалить папку?") },
            text = { Text("Папка \"${folderToDelete!!.name}\" и всё её содержимое будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folderToDelete!!)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("Отмена") } }
        )
    }

    // === Диалог переименования файла ===
    if (fileToRename != null) {
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Переименовать файл") },
            text = { 
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Новое имя") },
                    singleLine = true
                ) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            viewModel.renameFile(fileToRename!!, newFileName.trim())
                            fileToRename = null
                            newFileName = ""
                        }
                    }
                ) { Text("Переименовать") }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    fileToRename = null
                    newFileName = ""
                }) { Text("Отмена") } 
            }
        )
    }

    // === Диалог переименования папки ===
    if (folderToRename != null) {
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text("Переименовать папку") },
            text = { 
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Новое имя") },
                    singleLine = true
                ) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.renameFolder(folderToRename!!, newFolderName.trim())
                            folderToRename = null
                            newFolderName = ""
                        }
                    }
                ) { Text("Переименовать") }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    folderToRename = null
                    newFolderName = ""
                }) { Text("Отмена") } 
            }
        )
    }

    // === Диалог редактирования точки ===
    if (locationToEdit != null) {
        var editName by remember(locationToEdit) { mutableStateOf(locationToEdit!!.name) }
        var editDescription by remember(locationToEdit) { mutableStateOf(locationToEdit!!.description) }
        var editLat by remember(locationToEdit) { mutableStateOf(String.format(Locale.US, "%.6f", locationToEdit!!.latitude)) }
        var editLon by remember(locationToEdit) { mutableStateOf(String.format(Locale.US, "%.6f", locationToEdit!!.longitude)) }
        var editError by remember { mutableStateOf<String?>(null) }
        
        AlertDialog(
            onDismissRequest = { locationToEdit = null },
            title = { Text("Редактировать точку") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it; editError = null },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text("Описание") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = editLat,
                            onValueChange = { editLat = it; editError = null },
                            label = { Text("Широта") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editLon,
                            onValueChange = { editLon = it; editError = null },
                            label = { Text("Долгота") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (editError != null) {
                        Text(editError!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val lat = editLat.replace(',', '.').toDoubleOrNull()
                        val lon = editLon.replace(',', '.').toDoubleOrNull()
                        when {
                            editName.isBlank() -> editError = "Введите название"
                            lat == null || lon == null -> editError = "Некорректные координаты"
                            lat !in -90.0..90.0 || lon !in -180.0..180.0 -> editError = "Координаты вне диапазона"
                            else -> {
                                val updated = locationToEdit!!.copy(
                                    name = editName.trim(),
                                    description = editDescription.trim(),
                                    latitude = lat,
                                    longitude = lon
                                )
                                viewModel.updateLocation(updated)
                                locationToEdit = null
                            }
                        }
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = { 
                TextButton(onClick = { locationToEdit = null }) { Text("Отмена") } 
            }
        )
    }

    if (BuildConfig.DEBUG && showLogsDialog) {
        val logContent = remember { FileLogger.getLogContent(context) }
        val crashContent = remember { FileLogger.getCrashContent(context) }
        val scrollState = rememberScrollState()
        
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Логи приложения") },
            text = {
                Column {
                    Text(
                        "Путь: ${FileLogger.getLogFilePath(context)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                            .verticalScroll(scrollState)
                    ) {
                        if (crashContent.isNotEmpty() && crashContent != "Крашей не записано") {
                            Text(
                                "=== КРАШИ ===\n$crashContent\n",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            logContent,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText(
                        "Логи",
                        "=== КРАШИ ===\n$crashContent\n\n=== ЛОГИ ===\n$logContent"
                    )
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Логи скопированы", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
            }
        )
    }
}

@Composable
private fun MiniFab(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.small) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        SmallFloatingActionButton(onClick = onClick, shape = CircleShape) { Icon(icon, label, Modifier.size(20.dp)) }
    }
}

@Composable
private fun FolderListItem(
    folder: VaultFolder,
    isMoveTarget: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (isMoveTarget) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).padding(end = 12.dp))
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isMoveTarget) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Экспортировать") },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                            leadingIcon = { Icon(Icons.Default.Upload, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationListItem(
    location: SavedLocation,
    isSelectedForMove: Boolean,
    moveMode: Boolean,
    onOpenMap: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        onClick = onOpenMap,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelectedForMove) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(location.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.6f, %.6f".format(location.latitude, location.longitude), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (location.photoId != null) Icon(Icons.Default.Image, "Фото", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            if (!moveMode) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Редактировать") },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Переместить") },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: VaultFile,
    isSelectedForMove: Boolean,
    moveMode: Boolean,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        onClick = onPreview,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelectedForMove) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                file.mimeType.startsWith("image/") -> Icons.Default.Image
                file.mimeType.startsWith("text/") -> Icons.Default.Description
                file.mimeType.startsWith("video/") -> Icons.Default.VideoLibrary
                else -> Icons.Default.InsertDriveFile
            }
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.originalName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(file.mimeType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!moveMode) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Переместить") },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Экспорт") },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}