package com.google.android.calculator.presentation.vault

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.calculator.R
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onNavigateToPicker: () -> Unit,
    onPreviewFile: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember {
        VaultViewModel(
            appContainer.addFileUseCase,
            appContainer.getFilesUseCase,
            appContainer.vaultRepository,
            appContainer
        )
    }
    val scope = rememberCoroutineScope()

    // Проверяем наличие полного доступа к файлам
    val hasStoragePermission = PermissionUtils.hasManageExternalStorage()

    // Лаунчер для экспорта (используется только как fallback, если разрешение не выдано)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        appContainer.setFileOperationInProgress(false)
        if (uri != null && viewModel.fileToExport != null) {
            scope.launch {
                val tempFile = appContainer.vaultRepository.decryptToTempFile(
                    viewModel.fileToExport!!.encryptedFileName
                )
                if (tempFile != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    tempFile.delete()
                }
                viewModel.clearExportState()
            }
        } else {
            viewModel.clearExportState()
        }
    }

    // Обработка запроса на экспорт
    LaunchedEffect(viewModel.fileToExport) {
        val fileToExport = viewModel.fileToExport
        if (fileToExport != null) {
            if (PermissionUtils.hasManageExternalStorage()) {
                scope.launch {
                    appContainer.setFileOperationInProgress(true)
                    try {
                        // 1. Расшифровываем файл
                        val tempFile = appContainer.vaultRepository.decryptToTempFile(fileToExport.encryptedFileName)

                        if (tempFile != null && tempFile.exists()) {
                            // 2. Получаем папку Загрузки и ГАРАНТИРОВАННО создаем её, если её нет
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) {
                                downloadDir.mkdirs()
                            }

                            // 3. Формируем имя файла (с защитой от перезаписи)
                            var destFile = File(downloadDir, fileToExport.originalName)
                            var counter = 1
                            while (destFile.exists()) {
                                val nameWithoutExt = destFile.nameWithoutExtension
                                val ext = destFile.extension
                                destFile = File(downloadDir, "${nameWithoutExt}_($counter).$ext")
                                counter++
                            }

                            // 4. Копируем данные
                            withContext(Dispatchers.IO) {
                                tempFile.inputStream().use { input ->
                                    destFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }

                            // 5. Удаляем временный файл
                            tempFile.delete()

                            // 6. Уведомляем об успехе
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "✅ Файл сохранен в Загрузки", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "❌ Ошибка: не удалось расшифровать файл", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "❌ Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        // 7. Очищаем состояние в любом случае
                        viewModel.clearExportState()
                        appContainer.setFileOperationInProgress(false)
                    }
                }
            } else {
                // Fallback: если разрешение вдруг отозвали, используем системный диалог
                appContainer.setFileOperationInProgress(true)
                exportLauncher.launch(fileToExport.originalName)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = true,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        if (hasStoragePermission) {
                            onNavigateToPicker()
                        } else {
                            PermissionUtils.launchManageStorageSettings(context)
                        }
                    },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (hasStoragePermission) Icons.Default.Add else Icons.Default.FolderOpen,
                        contentDescription = if (hasStoragePermission) "Добавить файл" else "Запросить доступ"
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Верхняя панель
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = stringResource(R.string.vault_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Основное содержимое экрана
            when {
                !hasStoragePermission -> {
                    // Экран запроса разрешений
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Для работы с файлами необходимо разрешить приложению доступ ко всем файлам на устройстве.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { PermissionUtils.launchManageStorageSettings(context) }) {
                                Text("Открыть настройки")
                            }
                        }
                    }
                }
                viewModel.files.isEmpty() -> {
                    // Пустое хранилище
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.vault_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                else -> {
                    // Список файлов
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(viewModel.files) { file ->
                            FileListItem(
                                file = file,
                                onPreview = { onPreviewFile(file.id) },
                                onExport = { viewModel.prepareExport(file) },
                                onDelete = { viewModel.deleteFile(file) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: VaultFile,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onPreview,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                file.mimeType.startsWith("image/") -> Icons.Default.Image
                file.mimeType.startsWith("text/") -> Icons.Default.Description
                file.mimeType.startsWith("video/") -> Icons.Default.VideoLibrary
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
                    text = file.originalName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onExport) {
                Icon(
                    Icons.Default.Share,
                    "Экспорт",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    "Удалить",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить файл?") },
            text = { Text("Файл \"${file.originalName}\" будет удалён из хранилища безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}