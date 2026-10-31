package com.google.android.calculator1.presentation.calculator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.calculator1.R
import com.google.android.calculator1.di.AppContainer
import kotlinx.coroutines.flow.collectLatest

// === Палитра в стиле Google Calculator 9.3 / Material 3 Expressive ===
private data class CalcPalette(
    val background: Color,
    val surface: Color,
    val displayBg: Color,
    val digitBg: Color,
    val digitText: Color,
    val funcBg: Color,
    val funcText: Color,
    val operatorBg: Color,
    val operatorText: Color,
    val equalsBg: Color,
    val equalsText: Color,
    val clearBg: Color,
    val clearText: Color,
    val textMain: Color,
    val textMuted: Color,
    val accent: Color,
    val error: Color
)

private fun lightPalette() = CalcPalette(
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    displayBg = Color(0xFFFFFFFF),
    digitBg = Color(0xFFFFFFFF),
    digitText = Color(0xFF1C1B1F),
    funcBg = Color(0xFFE8E8ED),
    funcText = Color(0xFF1C1B1F),
    operatorBg = Color(0xFFFF9500),
    operatorText = Color.White,
    equalsBg = Color(0xFF007AFF),
    equalsText = Color.White,
    clearBg = Color(0xFFD1D1D6),
    clearText = Color(0xFF1C1B1F),
    textMain = Color(0xFF1C1B1F),
    textMuted = Color(0xFF8E8E93),
    accent = Color(0xFF007AFF),
    error = Color(0xFFD70015)
)

private fun darkPalette() = CalcPalette(
    background = Color(0xFF000000),
    surface = Color(0xFF1C1B1F),
    displayBg = Color(0xFF000000),
    digitBg = Color(0xFF2C2C2E),
    digitText = Color.White,
    funcBg = Color(0xFF3A3A3C),
    funcText = Color.White,
    operatorBg = Color(0xFFFF9F0A),
    operatorText = Color.White,
    equalsBg = Color(0xFF0A84FF),
    equalsText = Color.White,
    clearBg = Color(0xFF48484A),
    clearText = Color.White,
    textMain = Color.White,
    textMuted = Color(0xFF8E8E93),
    accent = Color(0xFF0A84FF),
    error = Color(0xFFFF453A)
)

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

    val isDark = androidx.compose.ui.platform.LocalContext.current.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val palette = remember(isDark) { if (isDark) darkPalette() else lightPalette() }

    var showHistoryDialog by remember { mutableStateOf(false) }
    var showScientific by remember { mutableStateOf(false) }

    // === Диалог истории ===
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            containerColor = palette.surface,
            title = { Text("История", color = palette.textMain) },
            text = {
                if (viewModel.historyEntries.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().height(140.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.History, null, tint = palette.textMuted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("В истории нет записей", color = palette.textMuted, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(viewModel.historyEntries.reversed()) { entry ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.useHistoryEntry(entry)
                                        showHistoryDialog = false
                                    },
                                color = Color.Transparent
                            ) {
                                Text(
                                    entry,
                                    color = palette.textMain,
                                    fontSize = 17.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp, horizontal = 4.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                            HorizontalDivider(color = palette.funcBg)
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

    // === Основной экран ===
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val spacing = 10.dp
        val hPad = 14.dp
        val sciRowHeight = 50.dp

        val cellW = (maxWidth - hPad * 2 - spacing * 3) / 4
        val sciRows = if (showScientific) 3 else 0
        val sciHeight = (sciRowHeight * sciRows) + (spacing * (sciRows + 1))
        val reserve = 280.dp + sciHeight
        val cellH = (maxHeight - reserve - spacing * 4) / 5
        val cell = maxOf(48.dp, minOf(cellW, cellH))

        Column(Modifier.fillMaxSize()) {
            // === Верхняя панель ===
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showHistoryDialog = true }) {
                    Icon(Icons.Default.History, "История", tint = palette.textMain)
                }
                Spacer(Modifier.weight(1f))
                if (showScientific) {
                    Text(
                        if (viewModel.angleInDegrees) "DEG" else "RAD",
                        color = palette.textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }

            // === Дисплей ===
            // weight(1f) заставляет этот блок занимать всё свободное место над клавиатурой.
            // Arrangement.Bottom прижимает текст к низу этого пространства, 
            // поэтому при изменении размера шрифта строки не будут перекрываться.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,  // ⭐ ИЗМЕНЕНО: было Arrangement.Bottom
                horizontalAlignment = Alignment.End
            ) {
                // Верхняя строка (история/выражение)
                if (viewModel.history.isNotEmpty()) {
                    Text(
                        text = viewModel.history,
                        color = palette.textMuted,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Основная строка (текущий ввод/результат)
                val shown = if (viewModel.display.isEmpty()) "" else viewModel.display.replace(".", ",")
                val displayText = shown.ifEmpty { "0" }
                
                // Динамический размер шрифта с БОЛЬШИМ запасом.
                // Уменьшаем шрифт ЗАРАНЕЕ, чтобы текст никогда не касался края
                val fontSizeSp by animateFloatAsState(
                    targetValue = when {
                        displayText.length > 12 -> 30f
                        displayText.length > 9 -> 40f
                        displayText.length > 7 -> 50f
                        else -> 64f  // Базовый размер чуть меньше для запаса
                    },
                    animationSpec = tween(150),
                    label = "fontSize"
                )

                Text(
                    text = displayText,
                    color = if (viewModel.isError) palette.error else palette.textMain,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Default,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)  // ⭐ ВАЖНО: запас справа, чтобы текст не касался края
                        .wrapContentHeight()
                )
            }
            
            // === Переключатель научного режима ===
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showScientific = !showScientific }) {
                    Icon(
                        if (showScientific) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        "Научный режим",
                        tint = palette.textMuted
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Научный",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // === Научные кнопки ===
            if (showScientific) {
                Spacer(Modifier.height(spacing))
                Column(
                    Modifier.padding(horizontal = hPad),
                    verticalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton("√", { viewModel.onSquareRootPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton("π", { viewModel.onPiPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton("xʸ", { viewModel.onPowerPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton("x!", { viewModel.onFactorialPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton(if (viewModel.angleInDegrees) "Deg" else "Rad", { viewModel.toggleAngleMode() }, palette, Modifier.weight(1f), sciRowHeight, active = true)
                        SciButton(if (viewModel.isInverseMode) "sin⁻¹" else "sin", { viewModel.onSinePressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton(if (viewModel.isInverseMode) "cos⁻¹" else "cos", { viewModel.onCosinePressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton(if (viewModel.isInverseMode) "tan⁻¹" else "tan", { viewModel.onTangentPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                        SciButton("Inv", { viewModel.toggleInverseMode() }, palette, Modifier.weight(1f), sciRowHeight, active = viewModel.isInverseMode)
                        SciButton("e", { viewModel.onEPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton("ln", { viewModel.onLnPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                        SciButton("log", { viewModel.onLogPressed() }, palette, Modifier.weight(1f), sciRowHeight)
                    }
                }
                Spacer(Modifier.height(spacing))
            } else {
                Spacer(Modifier.height(6.dp))
            }

            // === Основная клавиатура ===
            Column(
                Modifier
                    .padding(horizontal = hPad)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    CalcButton("AC", { viewModel.onClear() }, CalcButtonType.Clear, palette, cell, Modifier.weight(1f))
                    CalcButton("()", { viewModel.onParenthesisPressed() }, CalcButtonType.Function, palette, cell, Modifier.weight(1f))
                    CalcButton("%", { viewModel.onPercentPressed() }, CalcButtonType.Function, palette, cell, Modifier.weight(1f))
                    CalcButton("÷", { viewModel.onOperatorPressed("÷") }, CalcButtonType.Operator, palette, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    CalcButton("7", { viewModel.onDigitPressed("7") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("8", { viewModel.onDigitPressed("8") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("9", { viewModel.onDigitPressed("9") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("×", { viewModel.onOperatorPressed("×") }, CalcButtonType.Operator, palette, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    CalcButton("4", { viewModel.onDigitPressed("4") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("5", { viewModel.onDigitPressed("5") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("6", { viewModel.onDigitPressed("6") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("−", { viewModel.onOperatorPressed("-") }, CalcButtonType.Operator, palette, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    CalcButton("1", { viewModel.onDigitPressed("1") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("2", { viewModel.onDigitPressed("2") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("3", { viewModel.onDigitPressed("3") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton("+", { viewModel.onOperatorPressed("+") }, CalcButtonType.Operator, palette, cell, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(spacing)) {
                    CalcButton("0", { viewModel.onDigitPressed("0") }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    CalcButton(",", { viewModel.onDecimalPressed() }, CalcButtonType.Digit, palette, cell, Modifier.weight(1f))
                    BackspaceButton(
                        onTap = { viewModel.onBackspacePressed() },
                        onLongPress = { viewModel.onClear() },
                        palette = palette,
                        size = cell,
                        modifier = Modifier.weight(1f)
                    )
                    EqualsButton(
                        onTap = { viewModel.onEqualsPressed() },
                        onLongPress = { viewModel.onEqualsLongPressed() },
                        palette = palette,
                        size = cell,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ⭐ ОВЕРЛЕЙ РАЗБЛОКИРОВКИ ХРАНИЛИЩА
        if (viewModel.isUnlocking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = palette.surface,
                    shadowElevation = 12.dp,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.LockOpen,
                            null,
                            tint = palette.accent,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "✅ PIN принят",
                            color = palette.textMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Расшифровка хранилища…",
                            color = palette.textMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(22.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 3.dp,
                            color = palette.accent
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
                        .background(Color.Black.copy(alpha = 0.6f))
                        .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = palette.surface,
                        shadowElevation = 12.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Error,
                                null,
                                tint = palette.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Ошибка разблокировки",
                                color = palette.textMain,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                error,
                                color = palette.error,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 4
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { viewModel.clearUnlockError() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("Закрыть", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ⭐ ОВЕРЛЕЙ ПРЕДЛОЖЕНИЯ СБРОСИТЬ КОНТЕЙНЕР
        if (viewModel.needsContainerReset) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { }, onPress = { }) },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = palette.surface,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = palette.error,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Старый формат контейнера",
                            color = palette.textMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Контейнер зашифрован старым методом.\n\n⚠️ Все данные в нём будут удалены.\nСоздать новый контейнер?",
                            color = palette.textMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.cancelReset() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textMain),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("Отмена")
                            }
                            Button(
                                onClick = { viewModel.resetContainer() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.error),
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
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

// === Типы кнопок ===
private enum class CalcButtonType {
    Digit, Function, Operator, Clear
}

// === Основная кнопка калькулятора (Material 3 Expressive) ===
@Composable
private fun CalcButton(
    text: String,
    onClick: () -> Unit,
    type: CalcButtonType,
    palette: CalcPalette,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val (bg, fg, fontSize) = when (type) {
        CalcButtonType.Digit -> Triple(palette.digitBg, palette.digitText, 26.sp)
        CalcButtonType.Function -> Triple(palette.funcBg, palette.funcText, 22.sp)
        CalcButtonType.Operator -> Triple(palette.operatorBg, palette.operatorText, 32.sp)
        CalcButtonType.Clear -> Triple(palette.clearBg, palette.clearText, 22.sp)
    }

    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isPressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
            }
        }
    }

    val scale by animateDpAsState(
        targetValue = if (isPressed) 0.94f.dp else 1f.dp,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "scale"
    )
    val animatedBg by animateColorAsState(
        targetValue = if (isPressed) bg.copy(alpha = 0.8f) else bg,
        animationSpec = tween(120),
        label = "bg"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(50))
            .background(animatedBg)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.tryEmit(press)
                        tryAwaitRelease()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium
        )
    }
}

// === Научная кнопка ===
@Composable
private fun SciButton(
    text: String,
    onClick: () -> Unit,
    palette: CalcPalette,
    modifier: Modifier = Modifier,
    height: Dp,
    active: Boolean = false
) {
    val bg = if (active) palette.accent else palette.funcBg
    val fg = if (active) Color.White else palette.funcText

    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isPressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
            }
        }
    }

    val scale by animateDpAsState(
        targetValue = if (isPressed) 0.95f.dp else 1f.dp,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 450f),
        label = "scale"
    )

    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.tryEmit(press)
                        tryAwaitRelease()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// === Кнопка Backspace с долгим нажатием ===
@Composable
private fun BackspaceButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    palette: CalcPalette,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isPressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
            }
        }
    }

    val scale by animateDpAsState(
        targetValue = if (isPressed) 0.94f.dp else 1f.dp,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "scale"
    )
    val animatedBg by animateColorAsState(
        targetValue = if (isPressed) palette.digitBg.copy(alpha = 0.8f) else palette.digitBg,
        animationSpec = tween(120),
        label = "bg"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(50))
            .background(animatedBg)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.tryEmit(press)
                        tryAwaitRelease()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Backspace,
            "Удалить",
            tint = palette.digitText,
            modifier = Modifier.size(28.dp)
        )
    }
}

// === Кнопка "=" (FAB-стиль) ===
@Composable
private fun EqualsButton(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    palette: CalcPalette,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isPressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> isPressed = false
            }
        }
    }

    val scale by animateDpAsState(
        targetValue = if (isPressed) 0.92f.dp else 1f.dp,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 350f),
        label = "scale"
    )
    val animatedBg by animateColorAsState(
        targetValue = if (isPressed) palette.equalsBg.copy(alpha = 0.85f) else palette.equalsBg,
        animationSpec = tween(120),
        label = "bg"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(50))
            .background(animatedBg)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        interactionSource.tryEmit(press)
                        tryAwaitRelease()
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "=",
            color = palette.equalsText,
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}