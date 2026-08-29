package com.google.android.calculator.presentation.vault

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.scan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onPreviewFile: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember {
        VaultViewModel(
            appContainer.addFileUseCase,
            appContainer.getFilesUseCase,
            appContainer.vaultRepository
        )
    }
    val scope = rememberCoroutineScope()
    // 1. Состояние скролла и видимости кнопки
    val listState = rememberLazyListState()
    var isFabVisible by remember { mutableStateOf(true) }

    // 1. Поток, который следит ТОЛЬКО за остановкой скролла
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { isScrolling ->
                if (!isScrolling) {
                    isFabVisible = true // Как только скролл остановился — показываем
                }
            }
    }

    // 2. Поток, который следит ТОЛЬКО за направлением скролла
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset 
        }.collect { (currentIndex, currentOffset) ->
            val scrollingDown = currentIndex > previousIndex || 
                (currentIndex == previousIndex && currentOffset > previousOffset)

            if (scrollingDown) {
                isFabVisible = false // Скроллим вниз — скрываем
            } else {
                isFabVisible = true  // Скроллим вверх — показываем
            }

            previousIndex = currentIndex
            previousOffset = currentOffset
        }
    }

    val addFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.addFile(uri, name, mime)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null && viewModel.fileToExport != null) {
            scope.launch {
                val tempFile = appContainer.vaultRepository.decryptToTempFile(viewModel.fileToExport!!.encryptedFileName)
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

    LaunchedEffect(viewModel.fileToExport) {
        if (viewModel.fileToExport != null) {
            exportLauncher.launch("${viewModel.fileToExport!!.originalName}")
        }
    }

    Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),        floatingActionButton = {
            // 3. Анимация появления и исчезновения
            AnimatedVisibility(
                visible = isFabVisible,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = { addFileLauncher.launch("*/*") },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape // 4. Идеально круглая форма
                ) {
                    Icon(Icons.Default.Add, "Добавить файл")
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

            if (viewModel.files.isEmpty()) {
                Box(modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.vault_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    state = listState, // Передаем состояние скролла
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
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

@Composable
private fun FileListItem(
    file: VaultFile,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onPreview,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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

            Column(modifier = Modifier.weight(1f)) {                Text(
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
                Icon(Icons.Default.Share, "Экспорт", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return c.getString(idx)
        }
    }
    return null
}