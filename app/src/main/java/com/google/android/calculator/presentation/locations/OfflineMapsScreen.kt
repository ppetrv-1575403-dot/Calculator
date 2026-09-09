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
import androidx.compose.ui.unit.dp
import com.google.android.calculator.utils.MapDownloadManager
import com.google.android.calculator.utils.OfflineMapInfo
import com.google.android.calculator.utils.OfflineMapUtils
import com.google.android.calculator.utils.OfflineRegion
import com.google.android.calculator.utils.OfflineRegions
import com.google.android.calculator.utils.RegionListManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mapsInfo by remember { mutableStateOf<List<OfflineMapInfo>>(emptyList()) }
    var mapToDelete by remember { mutableStateOf<OfflineMapInfo?>(null) }
    var regionToDownload by remember { mutableStateOf<OfflineRegion?>(null) }

    val downloadState by MapDownloadManager.state.collectAsState()
    val regions by RegionListManager.regions.collectAsState()
    val isLoadingRegions by RegionListManager.isLoading.collectAsState()

    fun refreshMaps() {
        mapsInfo = OfflineMapUtils.getOfflineMapsInfo()
    }

    LaunchedEffect(Unit) {
        refreshMaps()
        RegionListManager.loadRegions(context)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (downloadState.isDownloading) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    val regionName = downloadState.regionId?.let {
                                        regions.find { r -> r.id == it }?.name ?: OfflineRegions.getRegionById(it)?.name
                                    } ?: "Регион"
                                    Text("Загрузка: $regionName", style = MaterialTheme.typography.bodyLarge)
                                    Text("${(downloadState.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { MapDownloadManager.cancelDownload() }) {
                                Text("Отменить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (downloadState.completed) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Карта загружена!", Modifier.weight(1f))
                            TextButton(onClick = { MapDownloadManager.resetState(); refreshMaps() }) { Text("OK") }
                        }
                    }
                }
            }

            downloadState.error?.let { error ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text("Ошибка: $error", Modifier.weight(1f))
                            TextButton(onClick = { MapDownloadManager.resetState() }) { Text("OK") }
                        }
                    }
                }
            }

            item {
                Text("Скачать карты по регионам", style = MaterialTheme.typography.titleMedium)
            }

            if (isLoadingRegions) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем список регионов...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(regions) { region ->
                val isDownloadingThis = downloadState.isDownloading && downloadState.regionId == region.id
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(region.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "~${region.approximateSizeMb} МБ • зум ${region.minZoom}-${region.maxZoom}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDownloadingThis) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        } else {
                            IconButton(
                                onClick = { regionToDownload = region },
                                enabled = !downloadState.isDownloading
                            ) {
                                Icon(Icons.Default.Download, "Скачать", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            item {
                Text("Загруженные карты", style = MaterialTheme.typography.titleMedium)
            }

            if (mapsInfo.isEmpty()) {
                item {
                    Text(
                        "Нет загруженных карт",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(mapsInfo) { map ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(map.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    OfflineMapUtils.formatFileSize(map.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { mapToDelete = map }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    regionToDownload?.let { region ->
        AlertDialog(
            onDismissRequest = { regionToDownload = null },
            title = { Text("Скачать карту?") },
            text = { Text("«${region.name}» (~${region.approximateSizeMb} МБ). Загрузка займёт время и требует интернет.") },
            confirmButton = {
                TextButton(onClick = {
                    MapDownloadManager.startDownload(context, region)
                    regionToDownload = null
                }) { Text("Скачать") }
            },
            dismissButton = {
                TextButton(onClick = { regionToDownload = null }) { Text("Отмена") }
            }
        )
    }

    mapToDelete?.let { map ->
        AlertDialog(
            onDismissRequest = { mapToDelete = null },
            title = { Text("Удалить карту?") },
            text = { Text("Карта «${map.name}» (${OfflineMapUtils.formatFileSize(map.sizeBytes)}) будет удалена безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        OfflineMapUtils.deleteOfflineMap(map.file)
                        mapToDelete = null
                        refreshMaps()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { mapToDelete = null }) { Text("Отмена") }
            }
        )
    }
}