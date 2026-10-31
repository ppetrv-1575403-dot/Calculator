package com.google.android.calculator1.presentation.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.calculator1.R
import com.google.android.calculator1.di.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(appContainer: AppContainer, onNavigateToVault: () -> Unit) {
    val viewModel = remember {
        CalculatorViewModel(
            checkPinUseCase = appContainer.checkPinUseCase,
            onVaultOpened = onNavigateToVault,
            appContainer = appContainer
        )
    }

    // === Палитра в стиле Google Calculator ===
    val dark = androidx.compose.ui.platform.LocalContext.current.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) Color(0xFF161D26) else Color(0xFFEEF3F6)
    val digitBg = if (dark) Color(0xFF2A3441) else Color(0xFFE3E7EA)
    val funcBg = if (dark) Color(0xFF23404B) else Color(0xFFCDE7EC)
    val acBg = if (dark) Color(0xFF2E6B7A) else Color(0xFF9FE3F2)
    val eqBg = if (dark) Color(0xFF8CA3C7) else Color(0xFF4E5A70)
    val textMain = if (dark) Color(0xFFE6EDF3) else Color(0xFF1E2B33)
    val textMuted = if (dark) Color(0xFF9AA7B2) else Color(0xFF5F6B76)
    val textOnEq = if (dark) Color(0xFF101820) else Color.White

    var showHistoryDialog by remember { mutableStateOf(false) }
    var showScientific by remember { mutableStateOf(false) }

    // === Диалог истории ===
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("История") },
            text = {
                if (viewModel.historyEntries.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().height(120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.History, null, tint = textMuted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("В истории нет записей", color = textMuted, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(viewModel.historyEntries.reversed()) { entry ->
                            Text(
                                entry,
                                color = textMain,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.useHistoryEntry(entry)
                                        showHistoryDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                textAlign = TextAlign.End
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Очистить") }
            }
        )
    }

    // === Расчёт размера кнопок под доступную высоту экрана ===
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val spacing = 10.dp
        val hPad = 12.dp
        val sciRowHeight = 46.dp

        val cellW = (maxWidth - hPad * 2 - spacing * 3) / 4
        val sciRows = if (showScientific) 3 else 0
        val sciHeight = (sciRowHeight * sciRows) + (spacing * (sciRows + 1))
        val reserve = 250.dp + sciHeight
        val cellH = (maxHeight - reserve - spacing * 4) / 5
        val cell = maxOf(44.dp, minOf(cellW, cellH))

        Column(Modifier.fillMaxSize()) {
            // === Верхняя панель ===
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showHistoryDialog = true }) {
                    Icon(Icons.Default.History, "История", tint = textMain)
                }
                Spacer(Modifier.weight(1f))
            }

            // === Дисплей ===
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.End
            ) {
                if (viewModel.history.isNotEmpty()) {
                    Text(viewModel.history, color = textMuted, fontSize = 18.sp, maxLines = 1)
                }
                val shown = if (viewModel.display.isEmpty()) "" else viewModel.display.replace(".", ",")
                Text(
                    text = shown.ifEmpty { "0" },
                    color = if (viewModel.isError) MaterialTheme.colorScheme.error else textMain,
                    fontSize = when {
                        viewModel.display.length > 20 -> 28.sp
                        viewModel.display.length > 12 -> 40.sp
                        else -> 56.sp
                    },
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // === Шеврон научного режима ===
            Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                IconButton(onClick = { showScientific = !showScientific }) {
                    Icon(
                        if (showScientific) Icons.Default.KeyboardDoubleArrowDown else Icons.Default.KeyboardDoubleArrowUp,
                        "Научный режим",
                        tint = textMain
                    )
                }
            }

            // === Научные кнопки ===
            if (showScientific) {
                Column(Modifier.padding(horizontal = hPad), verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton("√", { viewModel.onSquareRootPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("π", { viewModel.onPiPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("^", { viewModel.onPowerPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("!", { viewModel.onFactorialPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton(if (viewModel.angleInDegrees) "Deg" else "Rad", { viewModel.toggleAngleMode() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton(if (viewModel.isInverseMode) "sin⁻¹" else "sin", { viewModel.onSinePressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton(if (viewModel.isInverseMode) "cos⁻¹" else "cos", { viewModel.onCosinePressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton(if (viewModel.isInverseMode) "tan⁻¹" else "tan", { viewModel.onTangentPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton("Inv", { viewModel.toggleInverseMode() }, if (viewModel.isInverseMode) acBg else funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("e", { viewModel.onEPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("ln", { viewModel.onLnPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                        SciButton("log", { viewModel.onLogPressed() }, funcBg, textMain, Modifier.weight(1f), sciRowHeight)
                    }
                }
                Spacer(Modifier.height(spacing))
            }

            // === Основная клавиатура ===
            Column(
                Modifier.padding(horizontal = hPad).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    RoundButton("AC", { viewModel.onClear() }, acBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("()", { viewModel.onParenthesisPressed() }, funcBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("%", { viewModel.onPercentPressed() }, funcBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("÷", { viewModel.onOperatorPressed("÷") }, funcBg, textMain, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    RoundButton("7", { viewModel.onDigitPressed("7") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("8", { viewModel.onDigitPressed("8") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("9", { viewModel.onDigitPressed("9") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("×", { viewModel.onOperatorPressed("×") }, funcBg, textMain, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    RoundButton("4", { viewModel.onDigitPressed("4") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("5", { viewModel.onDigitPressed("5") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("6", { viewModel.onDigitPressed("6") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("−", { viewModel.onOperatorPressed("-") }, funcBg, textMain, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    RoundButton("1", { viewModel.onDigitPressed("1") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("2", { viewModel.onDigitPressed("2") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("3", { viewModel.onDigitPressed("3") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton("+", { viewModel.onOperatorPressed("+") }, funcBg, textMain, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    RoundButton("0", { viewModel.onDigitPressed("0") }, digitBg, textMain, cell, Modifier.weight(1f))
                    RoundButton(",", { viewModel.onDecimalPressed() }, digitBg, textMain, cell, Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .size(cell)
                            .clip(CircleShape)
                            .background(digitBg)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { viewModel.onBackspacePressed() },
                                    onLongPress = { viewModel.onClear() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Backspace, "Удалить", tint = textMain, modifier = Modifier.size(26.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .size(cell)
                            .clip(RoundedCornerShape(50))
                            .background(eqBg)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { viewModel.onEqualsPressed() },
                                    onLongPress = { viewModel.onEqualsLongPressed() }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("=", color = textOnEq, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ⭐ ОВЕРЛЕЙ РАЗБЛОКИРОВКИ ХРАНИЛИЩА
        if (viewModel.isUnlocking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = bg,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.LockOpen,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "✅ PIN принят",
                            color = textMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Расшифровка хранилища…",
                            color = textMuted,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(18.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ⭐ ОВЕРЛЕЙ ОШИБКИ РАЗБЛОКИРОВКИ
        viewModel.unlockError?.let { error ->
            if (!viewModel.needsContainerReset) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = bg,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Error,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Ошибка разблокировки",
                                color = textMain,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 3
                            )
                            Spacer(Modifier.height(18.dp))
                            Button(
                                onClick = { viewModel.clearUnlockError() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Закрыть", color = textOnEq)
                            }
                        }
                    }
                }
            }
        }

        // ⭐ ОВЕРЛЕЙ ПРЕДЛОЖЕНИЯ СБРОСИТЬ КОНТЕЙНЕР (при несовместимости форматов)
        if (viewModel.needsContainerReset) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = bg,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Старый формат контейнера",
                            color = textMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Контейнер зашифрован старым методом.\n\n⚠️ Все данные в нём будут удалены.\nСоздать новый контейнер?",
                            color = textMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.cancelReset() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = textMain
                                )
                            ) {
                                Text("Отмена")
                            }
                            Button(
                                onClick = { viewModel.resetContainer() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Сбросить", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(text: String, onClick: () -> Unit, bg: Color, fg: Color, size: androidx.compose.ui.unit.Dp, mod: Modifier) {
    Box(
        modifier = mod
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SciButton(text: String, onClick: () -> Unit, bg: Color, fg: Color, mod: Modifier, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = mod
            .height(height)
            .clip(RoundedCornerShape(23.dp))
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}