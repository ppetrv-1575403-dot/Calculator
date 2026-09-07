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
import androidx.compose.ui.unit.dp
import com.google.android.calculator.utils.OfflineMapInfo
import com.google.android.calculator.utils.OfflineMapUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit
) {
    var mapsInfo by remember { mutableStateOf<List<OfflineMapInfo>>(emptyList()) }
    var mapToDelete by remember { mutableStateOf<OfflineMapInfo?>(null) }

    fun refreshMaps() {
        mapsInfo = OfflineMapUtils.getOfflineMapsInfo()
    }

    LaunchedEffect(Unit) {
        refreshMaps()
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
        if (mapsInfo.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("Нет загруженных карт", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Откройте карту точки и нажмите «Скачать», чтобы сохранить видимый регион для оффлайн-использования.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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