package com.google.android.calculator.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.utils.VaultManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerSettingsScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onNavigateToPickFolder: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentSlotId = VaultManager.currentSlotId
    val slot = VaultManager.slots().find { it.id == currentSlotId } ?: VaultManager.realSlot()

    var enabled by remember { mutableStateOf(VaultManager.containerEnabled(currentSlotId)) }
    var name by remember { mutableStateOf(VaultManager.containerName(currentSlotId)) }
    var currentPath by remember(currentSlotId) { mutableStateOf(VaultManager.containerPath(currentSlotId)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val containerFile = VaultManager.containerFile(currentSlotId)
    val containerExists = VaultManager.isContainerValid(currentSlotId)
    val containerSize = containerFile?.takeIf { it.exists() }?.let {
        val bytes = it.length()
        when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> "${bytes / (1024 * 1024)} МБ"
        }
    } ?: "—"

    val pickerResult by appContainer.folderPickerResult.collectAsState()
    LaunchedEffect(pickerResult) {
        if (pickerResult != null) {
            VaultManager.setContainerPath(currentSlotId, pickerResult)
            currentPath = pickerResult
            appContainer.resetFolderPickerResult()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Контейнер: ${slot.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ⚠️ ВАЖНОЕ ПРЕДУПРЕЖДЕНИЕ
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Column {
                        Text(
                            "Переносимый контейнер",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Ключ шифрования создаётся из PIN-кода. " +
                            "Контейнер можно переносить между устройствами — достаточно знать PIN. " +
                            "Но 4-значный PIN легко подбирается по украденному файлу. " +
                            "Рекомендуется PIN не менее 6 цифр.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        VaultManager.setContainerEnabled(currentSlotId, it)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text("Использовать переносимый контейнер")
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    VaultManager.setContainerName(currentSlotId, it)
                },
                label = { Text("Имя файла") },
                supportingText = { Text("Например: media_cache.bin, .cache_data, photos.db") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled
            )

            ListItem(
                headlineContent = { Text("Папка контейнера") },
                supportingContent = { Text(currentPath ?: "Не выбрана") },
                leadingContent = { Icon(Icons.Default.Folder, null) },
                modifier = Modifier.clickable(enabled = enabled) { onNavigateToPickFolder() }
            )

            HorizontalDivider()

            if (currentPath != null && !containerExists && enabled) {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val pin = VaultManager.activeSessionPin
                                if (pin.isNullOrEmpty()) {
                                    statusMessage = "❌ Сначала разблокируйте хранилище PIN-кодом"
                                    return@launch
                                }
                                val container = VaultContainer(containerFile!!, appContainer.cryptoManager)
                                container.create(pin)
                                statusMessage = "✅ Контейнер создан"
                            } catch (e: Exception) {
                                statusMessage = "❌ ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Создать контейнер")
                }
            }

            if (containerExists && enabled) {
                ListItem(
                    headlineContent = { Text("Размер") },
                    supportingContent = { Text(containerSize) },
                    leadingContent = { Icon(Icons.Default.Storage, null) }
                )

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                val pin = VaultManager.activeSessionPin
                                if (pin.isNullOrEmpty()) {
                                    statusMessage = "❌ Сначала разблокируйте хранилище"
                                    return@launch
                                }
                                val container = VaultContainer(containerFile!!, appContainer.cryptoManager)
                                container.open(pin)
                                container.compact() // ⭐ Убран аргумент pin
                                statusMessage = "✅ Упакован (освобождено место)"
                            } catch (e: Exception) {
                                statusMessage = "❌ ${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Упаковать (compact) — освободить место")
                }
            }

            if (statusMessage != null) {
                Text(
                    statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusMessage!!.startsWith("✅"))
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}