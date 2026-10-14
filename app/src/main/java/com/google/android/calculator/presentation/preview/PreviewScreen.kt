package com.google.android.calculator.presentation.preview

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp // ⭐ Исправление 1: добавлен импорт dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.calculator.data.datasource.VaultDataSourceFactory
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    fileId: String,
    appContainer: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var file by remember { mutableStateOf<com.google.android.calculator.domain.model.VaultFile?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fileId) {
        isLoading = true
        errorMessage = null
        try {
            val f = withContext(Dispatchers.IO) {
                appContainer.vaultRepository.getFiles().find { it.id == fileId }
            }
            file = f
            if (f != null) {
                if (f.mimeType.startsWith("video/") || f.mimeType.startsWith("audio/")) {
                    // Для видео используем прямой стриминг из контейнера, tempFile не нужен
                    tempFile = null
                } else {
                    // Для фото/документов расшифровываем во временный файл
                    val tf = withContext(Dispatchers.IO) {
                        appContainer.vaultRepository.decryptToTempFile(f.encryptedFileName)
                    }
                    tempFile = tf
                }
            } else {
                errorMessage = "Файл не найден"
            }
        } catch (e: Exception) {
            FileLogger.e("PreviewScreen", "Error loading file", e)
            errorMessage = "Ошибка загрузки: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tempFile?.delete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file?.originalName ?: "Просмотр", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator()
                errorMessage != null -> Text(
                    errorMessage!!, 
                    color = MaterialTheme.colorScheme.error, 
                    modifier = Modifier.padding(16.dp) // ⭐ Здесь использовался dp
                )
                file == null -> Text("Файл не найден")
                file!!.mimeType.startsWith("video/") || file!!.mimeType.startsWith("audio/") -> {
                    val container = appContainer.getActiveContainer()
                    if (container != null) {
                        VideoPlayerView(
                            fileId = file!!.id,
                            mimeType = file!!.mimeType,
                            container = container
                        )
                    } else {
                        Text(
                            "Контейнер не доступен. Включите его в настройках.", 
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                tempFile != null && file!!.mimeType.startsWith("image/") -> {
                    ImagePreview(tempFile = tempFile!!)
                }
                tempFile != null -> {
                    Text(
                        "Просмотр файлов типа ${file!!.mimeType} пока не поддерживается.\nФайл успешно расшифрован во временную память.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerView(
    fileId: String, 
    mimeType: String, 
    container: com.google.android.calculator.data.container.VaultContainer
) {
    val context = LocalContext.current
    
    // ⭐ Исправление 2: MediaSourceFactory передается в Builder, а не в экземпляр плеера
    val exoPlayer = remember {
        val dataSourceFactory = VaultDataSourceFactory(container)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory) // <-- Правильное место
            .build().apply {
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    LaunchedEffect(fileId) {
        // Используем кастомную схему URI, которую перехватит наш VaultMediaDataSource
        val uri = Uri.parse("vault://container/$fileId")
        
        // ⭐ Создаем MediaItem с явным указанием MIME типа
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType) // <-- Важно!
            .build()
        
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        
        FileLogger.d("PreviewScreen", "ExoPlayer prepared for file: $fileId, mimeType: $mimeType")
    }
    
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ImagePreview(tempFile: File) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(tempFile) {
        bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
    }
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Image Preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    } else {
        Text("Не удалось загрузить изображение", color = MaterialTheme.colorScheme.error)
    }
}