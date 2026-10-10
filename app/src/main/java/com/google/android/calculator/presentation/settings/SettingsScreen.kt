package com.google.android.calculator.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.secure.SecurePreferences
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.utils.OriginalFileAction
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode
import com.google.android.calculator.utils.VaultSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appContainer: AppContainer,
    securePreferences: SecurePreferences,
    onBack: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenAbout: () -> Unit,
    onSyncMainPin: (String) -> Unit,
    onSeedSlot: suspend (String) -> Unit,
    onNavigateToPickFolder: () -> Unit
) {
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showCreateVaultDialog by remember { mutableStateOf(false) }
    var slotToChangePin by remember { mutableStateOf<VaultSlot?>(null) }
    var slotToDelete by remember { mutableStateOf<VaultSlot?>(null) }
    var slotToEditContainer by remember { mutableStateOf<VaultSlot?>(null) }
    var showOriginalActionDialog by remember { mutableStateOf(false) }
    var originalAction by remember { mutableStateOf(securePreferences.originalFileAction) }
    var slotsVersion by remember { mutableStateOf(0) }

    val isDecoyMode = VaultManager.currentMode == VaultMode.DECOY
    val slots = remember(slotsVersion) { VaultManager.slots() }

    if (showChangePinDialog) {
        ChangePinDialog(
            slot = if (isDecoyMode) slots.firstOrNull { it.id == VaultManager.currentSlotId } ?: VaultManager.realSlot()
            else VaultManager.realSlot(),
            onSyncMainPin = onSyncMainPin,
            onDismiss = { showChangePinDialog = false }
        )
    }

    slotToChangePin?.let { slot ->
        ChangePinDialog(
            slot = slot,
            onSyncMainPin = onSyncMainPin,
            onDismiss = { slotToChangePin = null; slotsVersion++ }
        )
    }

    slotToDelete?.let { slot ->
        DeleteVaultDialog(
            slot = slot,
            onConfirm = { mainPin ->
                val ok = VaultManager.deleteSlot(slot.id, mainPin)
                slotToDelete = null
                slotsVersion++
                ok
            },
            onDismiss = { slotToDelete = null }
        )
    }

    if (showCreateVaultDialog) {
        CreateVaultDialog(
            onCreate = { label, pin, seed ->
                val result = VaultManager.createDecoySlot(pin, label)
                result.onSuccess { id ->
                    showCreateVaultDialog = false
                    slotsVersion++
                    if (seed) {
                        onSeedSlot(id)
                    }
                }
            },
            onDismiss = { showCreateVaultDialog = false }
        )
    }

    slotToEditContainer?.let { slot ->
        ContainerDialog(
            slot = slot,
            appContainer = appContainer,
            onPickFolder = { onNavigateToPickFolder() },
            onDismiss = { slotToEditContainer = null; slotsVersion++ }
        )
    }

    if (showOriginalActionDialog) {
        OriginalActionDialog(
            current = originalAction,
            onSave = { action ->
                securePreferences.originalFileAction = action
                originalAction = action
                showOriginalActionDialog = false
            },
            onDismiss = { showOriginalActionDialog = false }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
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
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { SectionTitle("Безопасность") }
            item {
                ListItem(
                    headlineContent = { Text("Сменить PIN-код") },
                    supportingContent = { Text("Код доступа текущего хранилища") },
                    leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showChangePinDialog = true }
                )
            }

            if (!isDecoyMode) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SectionTitle("Хранилища") }

                items(slots.size) { i ->
                    val slot = slots[i]
                    ListItem(
                        headlineContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(slot.label, style = MaterialTheme.typography.bodyLarge)
                                Row {
                                    IconButton(onClick = { slotToEditContainer = slot }) {
                                        Icon(Icons.Default.Storage, "Контейнер", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { slotToChangePin = slot }) {
                                        Icon(Icons.Default.Key, "Сменить PIN", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    }
                                    if (!slot.isReal) {
                                        IconButton(onClick = { slotToDelete = slot }) {
                                            Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(if (slot.isReal) "Основное • открывается основным PIN" else "Фейковое • открывается своим PIN")
                                    if (VaultManager.containerEnabled(slot.id)) {
                                        append(" • 📦 Контейнер")
                                        if (VaultManager.isContainerValid(slot.id)) append(" ✓")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (slot.isReal) Icons.Default.Shield else Icons.Default.Security,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }

                item {
                    ListItem(
                        headlineContent = { Text("Создать фейковое хранилище") },
                        supportingContent = { Text("Отдельный PIN и изолированные данные") },
                        leadingContent = { Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { showCreateVaultDialog = true }
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Файлы") }
            item {
                ListItem(
                    headlineContent = { Text("Оригиналы после переноса") },
                    supportingContent = {
                        Text(
                            when (originalAction) {
                                OriginalFileAction.KEEP -> "Оставлять на устройстве"
                                OriginalFileAction.DELETE -> "Удалять после переноса"
                                OriginalFileAction.SHRED -> "Затирать и удалять (безопасно)"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showOriginalActionDialog = true }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Карты и навигация") }
            item {
                ListItem(
                    headlineContent = { Text("Оффлайн-карты") },
                    supportingContent = { Text("Загрузка карт и графов дорог") },
                    leadingContent = { Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onOpenOfflineMaps() }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Приложение") }
            item {
                ListItem(
                    headlineContent = { Text("О приложении") },
                    supportingContent = { Text("Версия, лицензия, контакты") },
                    leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onOpenAbout() }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ContainerDialog(
    slot: VaultSlot,
    appContainer: AppContainer,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(VaultManager.containerEnabled(slot.id)) }
    var name by remember { mutableStateOf(VaultManager.containerName(slot.id)) }
    
    var currentPath by remember(slot.id) { mutableStateOf(VaultManager.containerPath(slot.id)) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val containerFile = VaultManager.containerFile(slot.id)
    val containerExists = VaultManager.isContainerValid(slot.id)
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
            VaultManager.setContainerPath(slot.id, pickerResult)
            currentPath = pickerResult
            appContainer.resetFolderPickerResult()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Контейнер: ${slot.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Файл-контейнер хранит все данные хранилища в одном замаскированном файле во внешней папке.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            VaultManager.setContainerEnabled(slot.id, it)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Использовать контейнер")
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        VaultManager.setContainerName(slot.id, it)
                    },
                    label = { Text("Имя файла") },
                    supportingText = { Text("Например: media_cache.bin, .cache_data") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ListItem(
                    headlineContent = { Text("Папка контейнера") },
                    supportingContent = { Text(currentPath ?: "Не выбрана") },
                    leadingContent = { Icon(Icons.Default.Folder, null) },
                    modifier = Modifier.clickable { onPickFolder() }
                )

                HorizontalDivider()

                if (currentPath != null && !containerExists) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val crypto = CryptoManager(context)
                                    val container = VaultContainer(containerFile!!, crypto)
                                    withContext(Dispatchers.IO) { container.create() }
                                    statusMessage = "✅ Контейнер создан"
                                } catch (e: Exception) {
                                    statusMessage = "❌ ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Создать контейнер")
                    }
                }

                if (containerExists) {
                    ListItem(
                        headlineContent = { Text("Размер") },
                        supportingContent = { Text(containerSize) },
                        leadingContent = { Icon(Icons.Default.Storage, null) }
                    )

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val crypto = CryptoManager(context)
                                    val container = VaultContainer(containerFile!!, crypto)
                                    withContext(Dispatchers.IO) {
                                        container.open()
                                        container.compact()
                                    }
                                    statusMessage = "✅ Упакован (освобождено место)"
                                } catch (e: Exception) {
                                    statusMessage = "❌ ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun ChangePinDialog(
    slot: VaultSlot,
    onSyncMainPin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сменить PIN: ${slot.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = oldPin, onValueChange = { oldPin = it; errorMessage = null },
                    label = { Text("Текущий PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, isError = errorMessage != null, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPin, onValueChange = { newPin = it; errorMessage = null },
                    label = { Text("Новый PIN") },
                    supportingText = { if (newPin.isNotEmpty() && newPin.length < 4) Text("Минимум 4 символа", color = MaterialTheme.colorScheme.error) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, isError = errorMessage != null, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin, onValueChange = { confirmPin = it; errorMessage = null },
                    label = { Text("Подтвердите новый PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, isError = errorMessage != null, modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        oldPin.isBlank() -> errorMessage = "Введите текущий PIN"
                        newPin.length < 4 -> errorMessage = "Новый PIN слишком короткий"
                        newPin != confirmPin -> errorMessage = "PIN-коды не совпадают"
                        else -> {
                            val success = VaultManager.changePin(slot.id, oldPin, newPin) { onSyncMainPin(it) }
                            if (success) onDismiss()
                            else errorMessage = "Неверный текущий PIN или PIN уже занят"
                        }
                    }
                }
            ) { Text("Сменить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CreateVaultDialog(
    onCreate: suspend (label: String, pin: String, seed: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("Фейковое ${VaultManager.decoySlots().size + 1}") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое фейковое хранилище") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it; errorMessage = null },
                    label = { Text("PIN хранилища") },
                    supportingText = { Text("Минимум 4 символа, не совпадает с другими") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin, onValueChange = { confirmPin = it; errorMessage = null },
                    label = { Text("Подтвердите PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = seed, onCheckedChange = { seed = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Наполнить демо-данными", style = MaterialTheme.typography.bodyMedium)
                }
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        label.isBlank() -> errorMessage = "Введите название"
                        pin.length < 4 -> errorMessage = "PIN слишком короткий"
                        pin != confirmPin -> errorMessage = "PIN-коды не совпадают"
                        else -> scope.launch {
                            onCreate(label.trim(), pin, seed)
                        }
                    }
                }
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun DeleteVaultDialog(
    slot: VaultSlot,
    onConfirm: (mainPin: String) -> Boolean,
    onDismiss: () -> Unit
) {
    var mainPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить «${slot.label}»?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Данные хранилища будут удалены безвозвратно. Введите ОСНОВНОЙ PIN для подтверждения.")
                OutlinedTextField(
                    value = mainPin, onValueChange = { mainPin = it; errorMessage = null },
                    label = { Text("Основной PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true, isError = errorMessage != null, modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onConfirm(mainPin)) errorMessage = "Неверный основной PIN"
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun OriginalActionDialog(
    current: OriginalFileAction,
    onSave: (OriginalFileAction) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оригиналы после переноса") },
        text = {
            Column {
                listOf(
                    OriginalFileAction.KEEP to "Оставлять на устройстве",
                    OriginalFileAction.DELETE to "Удалять после переноса в хранилище",
                    OriginalFileAction.SHRED to "Затирать (random + zeros) и удалять — безопасно"
                ).forEach { (action, label) ->
                    // ⭐ ИСПРАВЛЕНО: теперь используется обычный Row, который корректно импортирован через layout.*
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = action }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == action, onClick = { selected = action })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}