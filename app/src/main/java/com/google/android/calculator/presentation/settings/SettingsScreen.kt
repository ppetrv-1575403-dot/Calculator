package com.google.android.calculator.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenAbout: () -> Unit,
    onSeedDecoyData: () -> Unit,
    onSyncMainPin: (String) -> Unit
) {
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showDecoyDialog by remember { mutableStateOf(false) }

    val isDecoyMode = VaultManager.currentMode == VaultMode.DECOY

    if (showChangePinDialog) {
        ChangePinDialog(
            isDecoyMode = isDecoyMode,
            onSyncMainPin = onSyncMainPin,
            onDismiss = { showChangePinDialog = false }
        )
    }

    if (showDecoyDialog && !isDecoyMode) {
        DecoyManageDialog(
            onCreated = {
                showDecoyDialog = false
                onSeedDecoyData()
            },
            onDismiss = { showDecoyDialog = false }
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
            item {
                Text(
                    "Безопасность",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Сменить PIN-код") },
                    supportingContent = { Text("Изменить код доступа к хранилищу") },
                    leadingContent = {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { showChangePinDialog = true }
                )
            }

            if (!isDecoyMode) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                item {
                    Text(
                        "Фейковое хранилище",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                if (VaultManager.isDecoyEnabled())
                                    "Настроить фейковое хранилище"
                                else
                                    "Создать фейковое хранилище"
                            )
                        },
                        supportingContent = {
                            Text("Отдельный PIN и несекретные данные для отвода глаз")
                        },
                        leadingContent = {
                            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { showDecoyDialog = true }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            item {
                Text(
                    "Карты и навигация",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Оффлайн-карты") },
                    supportingContent = { Text("Загрузка карт и графов дорог") },
                    leadingContent = {
                        Icon(Icons.Default.Map, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onOpenOfflineMaps() }
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            item {
                Text(
                    "Приложение",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("О приложении") },
                    supportingContent = { Text("Версия, лицензия, контакты") },
                    leadingContent = {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { onOpenAbout() }
                )
            }
        }
    }
}

@Composable
private fun ChangePinDialog(
    isDecoyMode: Boolean,
    onSyncMainPin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сменить PIN-код") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = oldPin,
                    onValueChange = { oldPin = it; errorMessage = null },
                    label = { Text("Текущий PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it; errorMessage = null },
                    label = { Text("Новый PIN") },
                    supportingText = {
                        if (newPin.isNotEmpty() && newPin.length < 4) {
                            Text("Минимум 4 символа", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it; errorMessage = null },
                    label = { Text("Подтвердите новый PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                            val success = if (isDecoyMode) {
                                VaultManager.changeDecoyPin(oldPin, newPin)
                            } else {
                                VaultManager.changeMainPin(oldPin, newPin, onSyncMainPin)
                            }
                            if (success) onDismiss()
                            else errorMessage = "Неверный текущий PIN"
                        }
                    }
                }
            ) { Text("Сменить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private enum class DecoyView { MENU, CREATE, CHANGE, DISABLE }

@Composable
private fun DecoyManageDialog(
    onCreated: () -> Unit,
    onDismiss: () -> Unit
) {
    var view by remember { mutableStateOf(DecoyView.MENU) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val decoyEnabled = VaultManager.isDecoyEnabled()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (view) {
                    DecoyView.MENU -> "Фейковое хранилище"
                    DecoyView.CREATE -> "Создать фейковое хранилище"
                    DecoyView.CHANGE -> "Сменить фейковый PIN"
                    DecoyView.DISABLE -> "Отключить фейковое хранилище"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (view) {
                    DecoyView.MENU -> {
                        Text(
                            if (decoyEnabled)
                                "Фейковое хранилище включено. Ввод фейкового PIN на разблокировке откроет его вместо реального."
                            else
                                "Фейковое хранилище открывается отдельным PIN и содержит только несекретные данные. Реальное хранилище при этом полностью скрыто.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    DecoyView.CREATE -> {
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { newPin = it; errorMessage = null },
                            label = { Text("Фейковый PIN") },
                            supportingText = { Text("Минимум 4 символа, не совпадает с основным") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPin,
                            onValueChange = { confirmPin = it; errorMessage = null },
                            label = { Text("Подтвердите фейковый PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    DecoyView.CHANGE -> {
                        OutlinedTextField(
                            value = oldPin,
                            onValueChange = { oldPin = it; errorMessage = null },
                            label = { Text("Текущий фейковый PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = { newPin = it; errorMessage = null },
                            label = { Text("Новый фейковый PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPin,
                            onValueChange = { confirmPin = it; errorMessage = null },
                            label = { Text("Подтвердите новый PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    DecoyView.DISABLE -> {
                        Text(
                            "Для отключения введите ОСНОВНОЙ PIN. Фейковые данные будут удалены.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = oldPin,
                            onValueChange = { oldPin = it; errorMessage = null },
                            label = { Text("Основной PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            when (view) {
                DecoyView.MENU -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!decoyEnabled) {
                            TextButton(onClick = { view = DecoyView.CREATE; errorMessage = null }) {
                                Text("Создать")
                            }
                        } else {
                            TextButton(onClick = { view = DecoyView.CHANGE; errorMessage = null }) {
                                Text("Сменить PIN")
                            }
                            TextButton(onClick = { view = DecoyView.DISABLE; errorMessage = null }) {
                                Text("Отключить")
                            }
                        }
                    }
                }

                DecoyView.CREATE -> TextButton(
                    onClick = {
                        when {
                            newPin.length < 4 -> errorMessage = "PIN слишком короткий"
                            newPin != confirmPin -> errorMessage = "PIN-коды не совпадают"
                            VaultManager.authenticate(newPin) != null ->
                                errorMessage = "Этот PIN уже используется"
                            else -> {
                                VaultManager.enableDecoy(newPin)
                                onCreated()
                            }
                        }
                    }
                ) { Text("Создать") }

                DecoyView.CHANGE -> TextButton(
                    onClick = {
                        when {
                            newPin.length < 4 -> errorMessage = "PIN слишком короткий"
                            newPin != confirmPin -> errorMessage = "PIN-коды не совпадают"
                            else -> {
                                val ok = VaultManager.changeDecoyPin(oldPin, newPin)
                                if (ok) onDismiss()
                                else errorMessage = "Неверный текущий фейковый PIN"
                            }
                        }
                    }
                ) { Text("Сменить") }

                DecoyView.DISABLE -> TextButton(
                    onClick = {
                        val ok = VaultManager.disableDecoy(oldPin)
                        if (ok) onDismiss()
                        else errorMessage = "Неверный основной PIN"
                    }
                ) { Text("Отключить") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}