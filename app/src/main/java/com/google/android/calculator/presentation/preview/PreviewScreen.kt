package com.google.android.calculator.presentation.preview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.android.calculator.di.AppContainer
import kotlinx.coroutines.launch
import java.io.File
import com.google.android.calculator.presentation.utils.AndroidTools
import com.google.android.calculator.domain.model.VaultFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    fileId: String,
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("Загрузка...") }
    var mimeType by remember { mutableStateOf("") }
    var tempFile by remember { mutableStateOf<File?>(null) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    // Загружаем и расшифровываем файл при открытии экрана
    LaunchedEffect(fileId) {
        scope.launch {
            try {
                val files = appContainer.getFilesUseCase()
                val file = files.find { it.id == fileId }
                
                if (file != null) {
                    fileName = file.originalName
                    mimeType = file.mimeType
                    
                    val decrypted = appContainer.vaultRepository.decryptToTempFile(file.encryptedFileName)
                    tempFile = decrypted
                    
                    if (decrypted != null && file.mimeType.startsWith("text/")) {
                        textContent = try { decrypted.readText() } catch (e: Exception) { null }
                    }
                } else {
                    error = true
                }
            } catch (e: Exception) {
                error = true
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }
    
    val currentTempFile = tempFile
    val currentTextContent = textContent
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = fileName, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error -> {
                    Text("Ошибка загрузки файла", modifier = Modifier.align(Alignment.Center))
                }
                mimeType.startsWith("image/") && currentTempFile != null -> {
                    ImageViewer(imageFile = currentTempFile)
                }
                mimeType.startsWith("text/") && currentTextContent != null -> {
                    TextViewer(
                        text = currentTextContent, 
                        onCopy = { textToCopy -> AndroidTools.performClipboardCopy(context, textToCopy) }
                    )
                }
                else -> {
                    UnsupportedFileView()
                }
            }
        }
    }
    
    // Очищаем временный файл при закрытии экрана
    DisposableEffect(Unit) {
        onDispose {
            tempFile?.delete()
        }
    }
}

@Composable
private fun ImageViewer(imageFile: File) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = "Preview",
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid: Offset, pan: Offset, zoom: Float, rotation: Float ->
                        val newScale = (scale * zoom).coerceIn(1f, 10f)
                        
                        if (newScale > 1f) {
                            val maxX = (newScale - 1) * size.width / 2
                            val maxY = (newScale - 1) * size.height / 2
                            
                            val newOffsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            val newOffsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            
                            scale = newScale
                            offsetX = newOffsetX
                            offsetY = newOffsetY
                            offset = Offset(newOffsetX, newOffsetY)
                        } else {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            offset = Offset.Zero
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            alignment = Alignment.Center
        )

        if (scale == 1f) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shadowElevation = 4.dp
            ) {
                Text(
                    text = "Щипок для зума • Перетаскивание для перемещения",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (scale > 1f) {
            FloatingActionButton(
                onClick = {
                    scale = 1f; offsetX = 0f; offsetY = 0f; offset = Offset.Zero
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Сбросить зум", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TextViewer(text: String, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Символов: ${text.length} • Строк: ${text.count { it == '\n' } + 1}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Button(
            onClick = { onCopy(text) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Копировать весь текст")
        }
    }
}

@Composable
private fun UnsupportedFileView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Просмотр этого типа файла не поддерживается",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Но файл зашифрован и безопасно хранится",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
