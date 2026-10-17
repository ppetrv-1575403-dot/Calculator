package com.google.android.calculator.presentation.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.calculator.data.datasource.VaultDataSourceFactory
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewScreen(
    fileId: String,
    appContainer: AppContainer,
    onBack: () -> Unit,
    folderId: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var allFiles by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    
    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? android.app.Activity
            if (activity != null) {
                val window = activity.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                try {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                    insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    window.decorView.requestLayout()
                } catch (e: Exception) {
                    FileLogger.e("PreviewScreen", "Error resetting window flags", e)
                }
            }
        }
    }
    
    LaunchedEffect(fileId, folderId) {
        isLoading = true
        try {
            val allVaultFiles = withContext(Dispatchers.IO) {
                appContainer.vaultRepository.getFiles()
            }
            
            val targetFile = allVaultFiles.firstOrNull { it.id == fileId }
            
            val files = if (targetFile != null) {
                val realFolderId = targetFile.folderId
                FileLogger.d("PreviewScreen", "Found file $fileId in folder $realFolderId (passed folderId was $folderId)")
                allVaultFiles
                    .filter { it.folderId == realFolderId }
                    .sortedBy { it.originalName.lowercase() }
            } else {
                FileLogger.w("PreviewScreen", "File $fileId not found, using passed folderId=$folderId")
                allVaultFiles
                    .filter { it.folderId == folderId }
                    .sortedBy { it.originalName.lowercase() }
            }
            
            allFiles = files
            currentIndex = files.indexOfFirst { it.id == fileId }.coerceAtLeast(0)
        } catch (e: Exception) {
            FileLogger.e("PreviewScreen", "Error loading files", e)
            allFiles = listOf(VaultFile(fileId, "Unknown", fileId, "video/mp4", folderId))
        } finally {
            isLoading = false
        }
    }
    
    if (isLoading) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }
    
    if (allFiles.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Файлы не найдены", color = Color.White)
        }
        return
    }
    
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { allFiles.size }
    )
    
    LaunchedEffect(pagerState.currentPage) {
        currentIndex = pagerState.currentPage
    }
    
    BackHandler {
        onBack()
    }
    
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondBoundsPageCount = 1
    ) { page ->
        val file = allFiles[page]
        FilePlayerView(
            file = file,
            appContainer = appContainer,
            onBack = onBack,
            onNext = {
                if (page < allFiles.size - 1) {
                    scope.launch { pagerState.animateScrollToPage(page + 1) }
                }
            },
            onPrevious = {
                if (page > 0) {
                    scope.launch { pagerState.animateScrollToPage(page - 1) }
                }
            }
        )
    }
}

@Composable
private fun FilePlayerView(
    file: VaultFile,
    appContainer: AppContainer,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val context = LocalContext.current
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(file.id) {
        isLoading = true
        errorMessage = null
        try {
            if (file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/")) {
                imageBytes = null
            } else if (file.mimeType.startsWith("image/")) {
                val bytes = withContext(Dispatchers.IO) {
                    appContainer.vaultRepository.decryptToBytes(file.encryptedFileName)
                }
                imageBytes = bytes
            } else {
                val tf = withContext(Dispatchers.IO) {
                    appContainer.vaultRepository.decryptToTempFile(file.encryptedFileName)
                }
                imageBytes = tf?.readBytes()
            }
        } catch (e: Exception) {
            FileLogger.e("PreviewScreen", "Error loading file", e)
            errorMessage = "Ошибка загрузки"
        } finally {
            isLoading = false
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            }
            errorMessage != null -> {
                Text(errorMessage!!, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            }
            file.mimeType.startsWith("video/") || file.mimeType.startsWith("audio/") -> {
                val container = appContainer.getActiveContainer()
                if (container != null) {
                    VideoPlayerView(
                        fileId = file.id,
                        fileName = file.originalName,
                        mimeType = file.mimeType,
                        container = container,
                        onBack = onBack,
                        onNext = onNext,
                        onPrevious = onPrevious
                    )
                } else {
                    Text("Контейнер не доступен", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            imageBytes != null && file.mimeType.startsWith("image/") -> {
                ImagePreview(
                    imageBytes = imageBytes!!,
                    fileName = file.originalName,
                    onBack = onBack,
                    onNext = onNext,
                    onPrevious = onPrevious
                )
            }
            imageBytes != null && isTextDocument(file.mimeType, file.originalName) -> {
                DocumentPreview(
                    documentBytes = imageBytes!!,
                    fileName = file.originalName,
                    onBack = onBack
                )
            }
            imageBytes != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Description,
                        null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        file.originalName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Формат ${file.mimeType} не поддерживается для просмотра.\nИспользуйте экспорт, чтобы открыть файл во внешнем приложении.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPlayerView(
    fileId: String,
    fileName: String,
    mimeType: String,
    container: com.google.android.calculator.data.container.VaultContainer,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isFullscreen by remember { mutableStateOf(false) }
    
    val exoPlayer = remember(fileId) {
        val dataSourceFactory = VaultDataSourceFactory(container)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            duration = this@apply.duration
                        }
                    }
                })
            }
    }
    
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            val dur = exoPlayer.duration
            if (dur > 0 && dur != C.TIME_UNSET) {
                duration = dur
            }
            delay(500)
        }
    }
    
    LaunchedEffect(fileId) {
        val uri = Uri.parse("vault://container/$fileId")
        val mediaItem = MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            hideSystemWindowControls(context, false)
            exoPlayer.release()
        }
    }
    
    LaunchedEffect(isFullscreen) {
        hideSystemWindowControls(context, isFullscreen)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                TopBar(fileName = fileName, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
                CenterControls(
                    isPlaying = isPlaying,
                    onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onPrevious = { exoPlayer.seekTo(0); onPrevious() },
                    onNext = { onNext() },
                    modifier = Modifier.align(Alignment.Center)
                )
                BottomControls(
                    currentPosition = currentPosition,
                    duration = duration,
                    onSeek = { position -> exoPlayer.seekTo(position); currentPosition = position },
                    isFullscreen = isFullscreen,
                    onFullscreenToggle = { isFullscreen = !isFullscreen },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    fileName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleTheme: (() -> Unit)? = null,
    isDarkTheme: Boolean = true,
    contentColor: Color = Color.White  // ⭐ Цвет иконок и текста (по умолчанию белый)
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, "Назад", tint = contentColor, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            fileName,
            color = contentColor.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        
        if (onToggleTheme != null) {
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    if (isDarkTheme) "Светлая тема" else "Тёмная тема",
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun CenterControls(isPlaying: Boolean, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(56.dp).clip(CircleShape)
        ) {
            Icon(Icons.Default.SkipPrevious, "Предыдущий", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
        ) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Пауза" else "Воспроизвести", tint = Color.White, modifier = Modifier.size(48.dp))
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(56.dp).clip(CircleShape)
        ) {
            Icon(Icons.Default.SkipNext, "Следующий", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun BottomControls(currentPosition: Long, duration: Long, onSeek: (Long) -> Unit, isFullscreen: Boolean, onFullscreenToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Slider(
            value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
            onValueChange = { ratio -> onSeek((ratio * duration).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${formatTime(currentPosition)} / ${formatTime(duration)}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            IconButton(onClick = onFullscreenToggle) {
                Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Полноэкранный режим", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun hideSystemWindowControls(context: Context, hide: Boolean) {
    val activity = context as? android.app.Activity
    if (activity != null) {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0 || ms == C.TIME_UNSET) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
}

@Composable
private fun ImagePreview(imageBytes: ByteArray, fileName: String, onBack: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showControls by remember { mutableStateOf(true) }
    
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    
    LaunchedEffect(imageBytes) {
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            
            var inSampleSize = 1
            if (options.outHeight > screenHeight || options.outWidth > screenWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= screenHeight && (halfWidth / inSampleSize) >= screenWidth) {
                    inSampleSize *= 2
                }
            }
            
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565
            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { showControls = !showControls })
            }
    ) {
        if (bitmap != null) {
            ZoomableImage(bitmap = bitmap!!)
        } else {
            Text("Загрузка...", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                TopBar(fileName = fileName, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
                
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 64.dp)
                        .size(56.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, "Предыдущий", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 64.dp)
                        .size(56.dp)
                ) {
                    Icon(Icons.Default.ChevronRight, "Следующий", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val zoomState = rememberZoomState(contentSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()))

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Preview",
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .zoomable(zoomState)
    )
}

private fun isTextDocument(mime: String, name: String): Boolean {
    if (mime.startsWith("text/")) return true
    val lower = name.lowercase()
    val textExtensions = listOf(
        ".txt", ".md", ".markdown", ".csv", ".json", ".xml", ".log", 
        ".html", ".htm", ".ini", ".conf", ".cfg", ".yml", ".yaml", 
        ".sh", ".bat", ".ps1", ".py", ".js", ".ts", ".jsx", ".tsx",
        ".java", ".kt", ".kts", ".c", ".cpp", ".h", ".hpp", ".cs",
        ".swift", ".go", ".rs", ".rb", ".php", ".css", ".scss", ".less",
        ".sql", ".properties", ".toml", ".gradle", ".gitignore", ".rtf"
    )
    if (textExtensions.any { lower.endsWith(it) }) return true
    
    return mime == "application/json" || mime == "application/xml" || 
           mime == "application/javascript" || mime == "application/x-sh"
}

@Composable
private fun DocumentPreview(
    documentBytes: ByteArray,
    fileName: String,
    onBack: () -> Unit
) {
    val text = remember(documentBytes) {
        String(documentBytes, Charsets.UTF_8)
    }
    var showControls by remember { mutableStateOf(true) }
    
    var isDarkTheme by rememberSaveable { mutableStateOf(true) }
    
    // ⭐ Цвета в зависимости от темы
    val backgroundColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkTheme) Color(0xFFDDDDDD) else Color(0xFF1A1A1A)
    // ⭐ Цвет элементов управления: белый на тёмном фоне, тёмный на светлом
    val controlsColor = if (isDarkTheme) Color.White else Color(0xFF1A1A1A)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { showControls = !showControls })
            }
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 64.dp, bottom = 24.dp)
        )
        
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TopBar(
                fileName = fileName,
                onBack = onBack,
                onToggleTheme = { isDarkTheme = !isDarkTheme },
                isDarkTheme = isDarkTheme,
                contentColor = controlsColor,  // ⭐ Передаём цвет, подходящий к фону
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}