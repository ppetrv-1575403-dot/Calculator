package com.google.android.calculator.presentation.locations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    var downloadingRegionId by remember { mutableStateOf<String?>(null) }
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
                                    Text("Загрузка: $regionName", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${(downloadProgress * 100).toInt()}% загружено",
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
                            Text("Карта загружена!", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
                val isDownloaded = downloadedRegions.any { it.regionId == region.id }

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
                                if (isDownloaded) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        "Загружено",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                "Зум ${region.minZoom}-${region.maxZoom}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        if (isDownloaded) Icons.Default.Refresh else Icons.Default.Download,
                                        if (isDownloaded) "Обновить" else "Скачать",
                                        tint = MaterialTheme.colorScheme.primary,
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

            if (downloadedRegions.isEmpty()) {
                item {
                    Text(
                        "Нет загруженных карт",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(downloadedRegions) { downloadedRegion ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(downloadedRegion.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${downloadedRegion.sizeBytes / 1024 / 1024} МБ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { regionToDelete = downloadedRegion }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    regionToDownload?.let { region ->
        val isUpdate = downloadedRegions.any { it.regionId == region.id }
        AlertDialog(
            onDismissRequest = { regionToDownload = null },
            title = { Text(if (isUpdate) "Обновить карту?" else "Скачать карту?") },
            text = {
                Text(
                    "«${region.name}» (зум ${region.minZoom}-${region.maxZoom}). " +
                        if (isUpdate) "Текущая версия будет заменена." else "Загрузка займёт время и требует интернет."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val model = OfflineRegions.toOfflineRegionModel(region)
                    downloadingRegionId = region.id
                    downloadProgress = 0f
                    downloadError = null
                    downloadCompleted = false

                    OfflineMapManager.downloadRegion(
                        context = context,
                        region = model,
                        onProgress = { progress ->
                            downloadProgress = progress
                        },
                        onComplete = {
                            downloadingRegionId = null
                            downloadCompleted = true
                            refreshDownloaded()
                        },
                        onError = { error ->
                            downloadingRegionId = null
                            downloadError = error
                        }
                    )
                    regionToDownload = null
                }) { Text(if (isUpdate) "Обновить" else "Скачать") }
            },
            dismissButton = {
                TextButton(onClick = { regionToDownload = null }) { Text("Отмена") }
            }
        )
    }

    regionToDelete?.let { downloadedRegion ->
        AlertDialog(
            onDismissRequest = { regionToDelete = null },
            title = { Text("Удалить карту?") },
            text = { Text("Карта «${downloadedRegion.name}» будет удалена безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        OfflineMapManager.deleteRegion(
                            context = context,
                            regionId = downloadedRegion.regionId,
                            onComplete = {
                                regionToDelete = null
                                refreshDownloaded()
                            },
                            onError = { error ->
                                regionToDelete = null
                                downloadError = "Ошибка удаления: $error"
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
}