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
import com.google.android.calculator.utils.DownloadedRegionInfo
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.OfflineMapManager
import com.google.android.calculator.utils.OfflineRegion
import com.google.android.calculator.utils.OfflineRegions
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadedRegions by remember { mutableStateOf<List<DownloadedRegionInfo>>(emptyList()) }
    var regionToDownload by remember { mutableStateOf<OfflineRegion?>(null) }
    var regionToDelete by remember { mutableStateOf<DownloadedRegionInfo?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }

    var downloadingRegionId by remember { mutableStateOf<String?>(null) }
    var downloadPhase by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadCompleted by remember { mutableStateOf(false) }

    val regions = remember { OfflineRegions.getRegions() }

    fun refreshDownloaded() {
        scope.launch {
            downloadedRegions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                OfflineMapManager.getDownloadedRegions(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        downloadedRegions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            OfflineMapManager.getDownloadedRegions(context)
        }
    }

    fun startSmartDownload(region: OfflineRegion, needCards: Boolean, needGraph: Boolean) {
        val model = OfflineRegions.toOfflineRegionModel(region)
        downloadingRegionId = region.id
        downloadError = null
        downloadCompleted = false

        val totalTasks = (if (needCards) 1 else 0) + (if (needGraph) 1 else 0)
        var currentTask = 0
        val taskWeight = if (totalTasks > 0) 1f / totalTasks else 1f

        fun proceedToGraph() {
            if (!needGraph) {
                downloadingRegionId = null
                downloadPhase = null
                downloadCompleted = true
                refreshDownloaded()
                return
            }
            currentTask++
            downloadPhase = "graph"
            val baseProgress = (currentTask - 1) * taskWeight
            downloadProgress = baseProgress

            OfflineMapManager.downloadGraph(
                context = context,
                region = model,
                onProgress = { progress ->
                    downloadProgress = baseProgress + progress * taskWeight
                },
                onComplete = {
                    downloadingRegionId = null
                    downloadPhase = null
                    downloadCompleted = true
                    refreshDownloaded()
                },
                onError = { error ->
                    downloadingRegionId = null
                    downloadPhase = null
                    downloadError = "Ошибка загрузки графа: $error"
                    refreshDownloaded()
                }
            )
        }

        if (!needCards) {
            proceedToGraph()
        } else {
            currentTask++
            downloadPhase = "cards"
            downloadProgress = 0f

            OfflineMapManager.downloadRegion(
                context = context,
                region = model,
                onProgress = { progress ->
                    downloadProgress = (currentTask - 1) * taskWeight + progress * taskWeight
                },
                onComplete = {
                    proceedToGraph()
                },
                onError = { error ->
                    downloadingRegionId = null
                    downloadPhase = null
                    downloadError = "Ошибка загрузки карт: $error"
                }
            )
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
            if (downloadingRegionId != null) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    val regionName = regions.find { it.id == downloadingRegionId }?.name ?: downloadingRegionId
                                    val phaseText = when (downloadPhase) {
                                        "cards" -> "Загрузка карт..."
                                        "graph" -> "Загрузка графа дорог..."
                                        else -> "Подготовка..."
                                    }
                                    Text("«$regionName»", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "$phaseText ${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                downloadingRegionId = null
                                downloadPhase = null
                                downloadProgress = 0f
                            }) {
                                Text("Отменить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (downloadCompleted) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Загрузка завершена!", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                downloadCompleted = false
                                refreshDownloaded()
                            }) { Text("OK") }
                        }
                    }
                }
            }

            downloadError?.let { error ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                error,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { downloadError = null }) { Text("OK") }
                        }
                    }
                }
            }

            item {
                Text("Скачать карты по регионам", style = MaterialTheme.typography.titleSmall)
            }

            items(regions) { region ->
                val isDownloadingThis = downloadingRegionId == region.id
                val hasCards = downloadedRegions.any { it.regionId == region.id }
                val hasGraph = OfflineMapManager.isGraphDownloaded(context, region.id)
                
                val isComplete = hasCards && hasGraph
                val isPartial = hasCards != hasGraph

                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    region.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (hasCards) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "Карты загружены",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (hasGraph) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.AltRoute,
                                        "Граф дорог загружен",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                when {
                                    isComplete -> "Полностью загружен"
                                    isPartial -> "Загружено частично"
                                    else -> "Не загружен"
                                } + " • Зум ${region.minZoom}-${region.maxZoom}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isComplete -> MaterialTheme.colorScheme.primary
                                    isPartial -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        when {
                            isDownloadingThis -> {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            else -> {
                                IconButton(
                                    onClick = { regionToDownload = region },
                                    enabled = downloadingRegionId == null
                                ) {
                                    Icon(
                                        when {
                                            isComplete -> Icons.Default.Refresh
                                            isPartial -> Icons.Default.CloudDownload
                                            else -> Icons.Default.Download
                                        },
                                        when {
                                            isComplete -> "Обновить всё"
                                            isPartial -> "Дозагрузить"
                                            else -> "Скачать"
                                        },
                                        tint = when {
                                            isComplete -> MaterialTheme.colorScheme.primary
                                            isPartial -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("Загруженные карты", style = MaterialTheme.typography.titleSmall)
            }

            if (downloadedRegions.isEmpty() && regions.none { OfflineMapManager.isGraphDownloaded(context, it.id) }) {
                item {
                    Text(
                        "Нет загруженных регионов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val allRegions = regions.filter { region ->
                    downloadedRegions.any { it.regionId == region.id } || 
                    OfflineMapManager.isGraphDownloaded(context, region.id)
                }
                
                items(allRegions) { region ->
                    val cardsInfo = downloadedRegions.find { it.regionId == region.id }
                    val hasGraph = OfflineMapManager.isGraphDownloaded(context, region.id)
                    val graphSize = if (hasGraph) OfflineMapManager.getGraphSize(context, region.id) else 0L

                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Map, null,
                                tint = if (cardsInfo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(region.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                
                                val statusParts = mutableListOf<String>()
                                if (cardsInfo != null) {
                                    statusParts.add("Карты: ${cardsInfo.sizeBytes / 1024 / 1024} МБ")
                                } else {
                                    statusParts.add("Карты: нет")
                                }
                                if (hasGraph) {
                                    statusParts.add("Граф: ${graphSize / 1024 / 1024} МБ")
                                } else {
                                    statusParts.add("Граф: нет")
                                }
                                
                                Text(
                                    statusParts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { regionToDelete = DownloadedRegionInfo(region.id, region.name, 0) }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    regionToDownload?.let { region ->
        val hasCards = downloadedRegions.any { it.regionId == region.id }
        val hasGraph = OfflineMapManager.isGraphDownloaded(context, region.id)
        val isComplete = hasCards && hasGraph
        val isPartial = hasCards != hasGraph
        
        val needCards = !hasCards
        val needGraph = !hasGraph

        AlertDialog(
            onDismissRequest = { regionToDownload = null },
            title = { 
                Text(
                    when {
                        isComplete -> "Обновить регион?"
                        isPartial -> "Дозагрузить регион?"
                        else -> "Скачать регион?"
                    }
                ) 
            },
            text = {
                Column {
                    Text("«${region.name}» (зум ${region.minZoom}-${region.maxZoom})")
                    Spacer(Modifier.height(8.dp))
                    
                    if (isComplete) {
                        Text(
                            "Регион полностью загружен. Будут обновлены оба файла.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Состояние:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (hasCards) Icons.Default.CheckCircle else Icons.Default.Close,
                                null,
                                Modifier.size(16.dp),
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
                                null,
                                Modifier.size(16.dp),
                                tint = if (hasGraph) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Граф дорог" + if (hasGraph) " (есть)" else " (будет загружен)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Требуется интернет. Загрузка займёт время.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isComplete) {
                        startSmartDownload(region, needCards = true, needGraph = true)
                    } else {
                        startSmartDownload(region, needCards = needCards, needGraph = needGraph)
                    }
                    regionToDownload = null
                }) { 
                    Text(
                        when {
                            isComplete -> "Обновить всё"
                            isPartial -> "Дозагрузить"
                            else -> "Скачать"
                        }
                    ) 
                }
            },
            dismissButton = {
                TextButton(onClick = { regionToDownload = null }) { Text("Отмена") }
            }
        )
    }

    regionToDelete?.let { downloadedRegion ->
        AlertDialog(
            onDismissRequest = { regionToDelete = null },
            title = { Text("Удалить регион?") },
            text = {
                Column {
                    Text("«${downloadedRegion.name}» будет удалена.")
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
                        OfflineMapManager.deleteRegion(
                            context = context,
                            regionId = downloadedRegion.regionId,
                            onComplete = {
                                OfflineMapManager.deleteGraph(
                                    context = context,
                                    regionId = downloadedRegion.regionId,
                                    onComplete = {
                                        regionToDelete = null
                                        refreshDownloaded()
                                    },
                                    onError = {
                                        regionToDelete = null
                                        refreshDownloaded()
                                    }
                                )
                            },
                            onError = {
                                regionToDelete = null
                                refreshDownloaded()
                            }
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { regionToDelete = null }) { Text("Отмена") }
            }
        )
    }

    // Диалог с логами
    if (showLogsDialog) {
        val logContent = remember { FileLogger.getLogContent(context) }
        val crashContent = remember { FileLogger.getCrashContent(context) }
        val scrollState = rememberScrollState()
        
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Логи приложения") },
            text = {
                Column {
                    Text(
                        "Логи и краши:",
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
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
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