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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.utils.FileTypeUtils
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(fileId: String, appContainer: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf("Загрузка...") }
    var mimeType by remember { mutableStateOf("") }
    var tempFile by remember { mutableStateOf<File?>(null) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(fileId) {
        val file = appContainer.getFilesUseCase().find { it.id == fileId }
        if (file != null) {
            fileName = file.originalName
            mimeType = file.mimeType
            val decrypted = appContainer.vaultRepository.decryptToTempFile(file.encryptedFileName)
            tempFile = decrypted
            if (decrypted != null && FileTypeUtils.isTextFile(file.originalName, file.mimeType)) {
                textContent = try { decrypted.readText(Charsets.UTF_8) } catch (e: Exception) {
                    try { decrypted.readText(Charsets.ISO_8859_1) } catch (e2: Exception) { null }
                }
            }
        }
        isLoading = false
    }

    val currentTempFile = tempFile
    val currentTextContent = textContent

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { @Suppress("DEPRECATION") Icon(Icons.Default.ArrowBack, "Назад") }
                }
            )
        }
    ) { padding ->
        Box(Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(padding)
           ) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                mimeType.startsWith("image/") && currentTempFile != null -> ImageViewer(currentTempFile)
                FileTypeUtils.isTextFile(fileName, mimeType) && currentTextContent != null ->
                    TextViewer(fileName, currentTextContent) { copyToClipboard(context, it) }
                else -> UnsupportedView()
            }
        }
    }

    DisposableEffect(Unit) { onDispose { tempFile?.delete() } }
}

@Composable
private fun ImageViewer(imageFile: File) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = imageFile, contentDescription = "Preview",
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _: Offset, pan: Offset, zoom: Float, _: Float ->
                        val newScale = (scale * zoom).coerceIn(1f, 10f)
                        if (newScale > 1f) {
                            val maxX = (newScale - 1) * size.width / 2
                            val maxY = (newScale - 1) * size.height / 2
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        } else offset = Offset.Zero
                        scale = newScale
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            alignment = Alignment.Center
        )
        if (scale > 1f) {
            FloatingActionButton(
                onClick = { scale = 1f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.ZoomOutMap, "Сбросить зум", tint = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shadowElevation = 4.dp) {
                Text("Щипок для зума • Перетаскивание для перемещения", Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TextViewer(fileName: String, text: String, onCopy: (String) -> Unit) {
    val lang = FileTypeUtils.getLanguageName(fileName.substringAfterLast(".", ""))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (lang != "Text") {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(bottom = 8.dp)) {
                Text(lang, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        SelectionContainer {
            Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(16.dp))
        Text("Символов: ${text.length} • Строк: ${text.count { it == '\n' } + 1}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp))
        Button(onClick = { onCopy(text) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            Icon(Icons.Default.ContentCopy, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Копировать весь текст")
        }
    }
}

@Composable
private fun UnsupportedView() {
    Column(Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Просмотр этого типа файла не поддерживается", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Но файл зашифрован и безопасно хранится", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("vault_text", text))
}