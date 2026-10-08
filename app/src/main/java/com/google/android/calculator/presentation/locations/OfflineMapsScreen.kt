package com.google.android.calculator.presentation.locations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.MapDownloadManager
import com.google.android.calculator.utils.MapStorage
import com.google.android.calculator.utils.OfflineMapManager
import com.google.android.calculator.utils.OfflineRegion
import com.google.android.calculator.utils.OfflineRegions
import com.google.android.calculator.utils.DownloadedRegionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regionToDownload by remember { mutableStateOf<OfflineRegion?>(null) }
    var regionToDelete by remember { mutableStateOf<DownloadedRegionInfo?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var isRefreshingRegions by remember { mutableStateOf(false) }

    // ⭐ Реактивный список регионов: Compose перерисует при любом изменении
    val regions = remember {
        mutableStateListOf<OfflineRegion>().apply {
            addAll(OfflineRegions.getRegions())
        }
    }

    val downloaded = remember { mutableStateListOf<DownloadedRegionInfo>() }

    fun refreshDownloaded() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                OfflineMapManager.getDownloadedRegions(context)
            }
            downloaded.clear()
            downloaded.addAll(list)
        }
    }

    LaunchedEffect(Unit) {
        refreshDownloaded()
    }

    // ⭐ Автообновление списка при COMPLETED / ERROR
    val allStatuses = regions.map { region ->
        MapDownloadManager.getState(region.id).collectAsState().value.status
    }
    LaunchedEffect(allStatuses) {
        if (allStatuses.any {
                it == MapDownloadManager.Status.COMPLETED || it == MapDownloadManager.Status.ERROR
            }) {
            refreshDownloaded()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Оффлайн-карты") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    // ⭐ Кнопка обновления списка регионов с сервера
                    IconButton(
                        onClick = {
                            if (isRefreshingRegions) return@IconButton
                            isRefreshingRegions = true
                            scope.launch {
                                val result = OfflineRegions.refreshFromServer()
                                if (result.isSuccess) {
                                    val updatedRegions = result.getOrNull() ?: emptyList()
                                    // Обновляем реактивный список — UI перерисуется мгновенно
                                    regions.clear()
                                    regions.addAll(updatedRegions)

                                    android.widget.Toast.makeText(
                                        context,
                                        "✅ Список обновлён: ${updatedRegions.size} регионов",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    FileLogger.i("OfflineMapsScreen", "Regions refreshed: ${updatedRegions.size}")
                                } else {
                                    val err = result.exceptionOrNull()
                                    android.widget.Toast.makeText(
                                        context,
                                        "❌ Ошибка обновления: ${err?.message ?: "неизвестно"}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    FileLogger.e("OfflineMapsScreen", "Refresh failed", err ?: Exception())
                                }
                                isRefreshingRegions = false
                            }
                        },
                        enabled = !isRefreshingRegions
                    ) {
                        if (isRefreshingRegions) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, "Обновить список регионов")
                        }
                    }
                    IconButton(onClick = { showLogsDialog = true }) {
                        Icon(Icons.Default.Description, "Логи")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("Регионы", style = MaterialTheme.typography.titleSmall) }

            if (regions.isEmpty()) {
                item {
                    Text(
                        "Нет доступных регионов. Нажмите 🔄 для загрузки списка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(regions, key = { it.id }) { region ->
                    RegionRow(
                        region = region,
                        context = context,
                        onDownloadClick = { regionToDownload = region }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Загруженные карты", style = MaterialTheme.typography.titleSmall)
            }

            if (downloaded.isEmpty()) {
                item {
                    Text(
                        "Нет загруженных регионов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(downloaded, key = { "d_${it.regionId}" }) { info ->
                    DownloadedRegionRow(
                        info = info,
                        onDelete = { regionToDelete = info }
                    )
                }
            }
        }
    }

    regionToDownload?.let { region ->
        DownloadConfirmDialog(
            region = region,
            onDismiss = { regionToDownload = null },
            onStart = { forceAll ->
                val model = OfflineRegions.toOfflineRegionModel(region)
                MapDownloadManager.start(context, model, forceAll = forceAll)
                regionToDownload = null
            }
        )
    }

    regionToDelete?.let { info ->
        DeleteConfirmDialog(
            info = info,
            context = context,
            onDismiss = { regionToDelete = null },
            onDeleted = {
                regionToDelete = null
                refreshDownloaded()
            }
        )
    }

    if (showLogsDialog) {
        val logContent = remember { FileLogger.getLogContent(context) }
        val crashContent = remember { FileLogger.getCrashContent(context) }
        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Логи приложения") },
            text = {
                Column {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(scrollState)
                    ) {
                        if (crashContent.isNotEmpty() && crashContent != "Крашей не записано") {
                            Text("=== КРАШИ ===\n$crashContent\n",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text(logContent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLogsDialog = false }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Логи",
                        "=== КРАШИ ===\n$crashContent\n\n=== ЛОГИ ===\n$logContent")
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "Логи скопированы", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Копировать") }
            }
        )
    }
}

@Composable
private fun RegionRow(
    region: OfflineRegion,
    context: android.content.Context,
    onDownloadClick: () -> Unit
) {
    val stateFlow = remember(region.id) { MapDownloadManager.getState(region.id) }
    val state by stateFlow.collectAsState()

    val hasCards = MapStorage.hasMap(region.id)
    val hasGraph = MapStorage.hasGraph(region.id)
    val isComplete = hasCards && hasGraph

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(region.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (hasCards) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.CheckCircle, "Карты", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        if (hasGraph) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.AltRoute, "Граф", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        when (state.status) {
                            MapDownloadManager.Status.DOWNLOADING -> {
                                val phase = if (state.phase == MapDownloadManager.Phase.MAPS) "Карты" else "Граф"
                                "$phase • ${(state.progress * 100).toInt()}%"
                            }
                            MapDownloadManager.Status.PAUSED -> "На паузе • ${(state.progress * 100).toInt()}%"
                            MapDownloadManager.Status.ERROR -> "Ошибка"
                            MapDownloadManager.Status.COMPLETED -> if (isComplete) "Полностью загружен" else "Загружено частично"
                            MapDownloadManager.Status.IDLE -> when {
                                isComplete -> "Полностью загружен"
                                hasCards || hasGraph -> "Загружено частично"
                                else -> "Не загружен"
                            }
                        } + " • Зум ${region.minZoom}-${region.maxZoom}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.status) {
                            MapDownloadManager.Status.ERROR -> MaterialTheme.colorScheme.error
                            MapDownloadManager.Status.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                when (state.status) {
                    MapDownloadManager.Status.DOWNLOADING -> {
                        IconButton(onClick = { MapDownloadManager.pause(region.id) }) {
                            Icon(Icons.Default.Pause, "Пауза", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    MapDownloadManager.Status.PAUSED -> {
                        IconButton(onClick = {
                            val model = OfflineRegions.toOfflineRegionModel(region)
                            MapDownloadManager.start(context, model, forceAll = false)
                        }) {
                            Icon(Icons.Default.PlayArrow, "Продолжить", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    MapDownloadManager.Status.ERROR -> {
                        IconButton(onClick = {
                            MapDownloadManager.resetError(region.id)
                            val model = OfflineRegions.toOfflineRegionModel(region)
                            MapDownloadManager.start(context, model, forceAll = false)
                        }) {
                            Icon(Icons.Default.Refresh, "Повторить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                        }
                    }
                    else -> {
                        IconButton(onClick = onDownloadClick) {
                            Icon(
                                when {
                                    isComplete -> Icons.Default.Refresh
                                    hasCards || hasGraph -> Icons.Default.CloudDownload
                                    else -> Icons.Default.Download
                                },
                                when {
                                    isComplete -> "Обновить всё"
                                    hasCards || hasGraph -> "Дозагрузить"
                                    else -> "Скачать"
                                },
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            if (state.status == MapDownloadManager.Status.DOWNLOADING ||
                state.status == MapDownloadManager.Status.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.status == MapDownloadManager.Status.DOWNLOADING && state.totalBytes > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.status == MapDownloadManager.Status.ERROR && state.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DownloadedRegionRow(
    info: DownloadedRegionInfo,
    onDelete: () -> Unit
) {
    val hasGraph = MapStorage.hasGraph(info.regionId)
    val graphSize = MapStorage.getGraphSize(info.regionId)

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Map, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

                val parts = mutableListOf<String>()
                parts.add("Карты: ${info.sizeBytes / 1024 / 1024} МБ")
                parts.add(if (hasGraph) "Граф: ${graphSize / 1024 / 1024} МБ" else "Граф: нет")
                Text(
                    parts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DownloadConfirmDialog(
    region: OfflineRegion,
    onDismiss: () -> Unit,
    onStart: (forceAll: Boolean) -> Unit
) {
    val hasCards = MapStorage.hasMap(region.id)
    val hasGraph = MapStorage.hasGraph(region.id)
    val isComplete = hasCards && hasGraph

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    isComplete -> "Обновить регион?"
                    hasCards || hasGraph -> "Дозагрузить регион?"
                    else -> "Скачать регион?"
                }
            )
        },
        text = {
            Column {
                Text("«${region.name}» (зум ${region.minZoom}-${region.maxZoom})")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasCards) Icons.Default.CheckCircle else Icons.Default.Close,
                        null, Modifier.size(16.dp),
                        tint = if (hasCards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Карты" + if (hasCards) " (есть)" else " (будут загружены)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasGraph) Icons.Default.CheckCircle else Icons.Default.Close,
                        null, Modifier.size(16.dp),
                        tint = if (hasGraph) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Граф дорог" + if (hasGraph) " (есть)" else " (будет загружен)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isComplete) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Отметьте «Перекачать всё», чтобы скачать заново.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isComplete) {
                    TextButton(onClick = { onStart(true) }) { Text("Перекачать всё") }
                } else {
                    TextButton(onClick = { onStart(false) }) {
                        Text(
                            when {
                                hasCards || hasGraph -> "Дозагрузить"
                                else -> "Скачать"
                            }
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun DeleteConfirmDialog(
    info: DownloadedRegionInfo,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить регион?") },
        text = {
            Column {
                Text("«${info.name}» будет удалён.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Удалятся карты и граф дорог (если есть).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    OfflineMapManager.deleteRegion(context, info.regionId,
                        onComplete = {
                            OfflineMapManager.deleteGraph(context, info.regionId,
                                onComplete = onDeleted,
                                onError = { onDeleted() })
                        },
                        onError = { onDeleted() })
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
}