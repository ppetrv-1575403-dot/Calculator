package com.google.android.calculator.presentation.preview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import java.io.File

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    fileId: String,
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var fileName by remember { mutableStateOf("Загрузка...") }
    var mimeType by remember { mutableStateOf("") }
    var tempFile by remember { mutableStateOf<File?>(null) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(fileId) {
        try {
            val files = appContainer.getFilesUseCase()
            val file = files.find { it.id == fileId }

            if (file != null) {
                fileName = file.originalName
                mimeType = file.mimeType

                val decrypted = appContainer.vaultRepository.decryptToTempFile(file.encryptedFileName)
                tempFile = decrypted

                if (decrypted != null && isTextLikeFile(file.originalName, file.mimeType)) {
                    textContent = try {
                        decrypted.readText(Charsets.UTF_8)
                    } catch (e: Exception) {
                        try {
                            decrypted.readText(Charsets.ISO_8859_1)
                        } catch (e2: Exception) {
                            null
                        }
                    }
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

    val currentTempFile = tempFile
    val currentText = textContent
    val isTextFile = isTextLikeFile(fileName, mimeType)
    val extension = fileName.substringAfterLast(".", "").lowercase()
    val languageName = getLanguageName(extension)
    val isCode = extension in CODE_EXTENSIONS || extension in WEB_EXTENSIONS || extension in SCRIPT_EXTENSIONS

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding() // <-- Исправляет вылезание за нижнюю панель
                .padding(paddingValues)
        ) {
            // ===== ФИКСИРОВАННАЯ ШАПКА =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Компактная кнопка копирования в верхнем углу (только для текста)
                if (isTextFile && currentText != null) {
                    IconButton(onClick = {
                        performClipboardCopy(context, currentText)
                        Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать")
                    }
                }
            }
            Divider(modifier = Modifier.padding(horizontal = 8.dp))

            // Бейдж языка (фиксированный)
            if (isTextFile && currentText != null && languageName != "Text") {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = languageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // ===== СКРОЛЛИМЫЙ КОНТЕНТ =====
            Box(modifier = Modifier.weight(1f)) {
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
                    isTextFile && currentText != null -> {
                        TextViewer(text = currentText, isCode = isCode)
                    }
                    else -> {
                        UnsupportedFileView()
                    }
                }
            }

            // ===== ФИКСИРОВАННАЯ ИНФОРМАЦИЯ О ФАЙЛЕ =====
            if (isTextFile && currentText != null) {
                Divider()
                Text(
                    text = "Символов: ${currentText.length} • Строк: ${currentText.count { it == '\n' } + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

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
                    detectTransformGestures { _centroid: Offset, pan: Offset, zoom: Float, _rotation: Float ->
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
                onClick = { scale = 1f; offsetX = 0f; offsetY = 0f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.ZoomOutMap, contentDescription = "Сбросить зум", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TextViewer(text: String, isCode: Boolean) {
    // Только скроллимый текст, всё остальное вынесено наружу
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface
            )
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

private fun getLanguageName(extension: String): String {
    return when (extension.lowercase()) {
        "py" -> "Python"
        "js" -> "JavaScript"
        "ts" -> "TypeScript"
        "java" -> "Java"
        "kt", "kts" -> "Kotlin"
        "cpp", "c", "h", "hpp" -> "C/C++"
        "cs" -> "C#"
        "php" -> "PHP"
        "rb" -> "Ruby"
        "go" -> "Go"
        "rs" -> "Rust"
        "swift" -> "Swift"
        "dart" -> "Dart"
        "lua" -> "Lua"
        "sql" -> "SQL"
        "html", "htm" -> "HTML"
        "css", "scss", "sass", "less" -> "CSS"
        "json" -> "JSON"
        "xml" -> "XML"
        "yaml", "yml" -> "YAML"
        "md", "markdown" -> "Markdown"
        "sh", "bash", "zsh" -> "Shell"
        else -> "Text"
    }
}

private fun performClipboardCopy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("vault_text", text)
    clipboard.setPrimaryClip(clip)
}