package com.google.android.calculator.presentation.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.calculator.R
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.presentation.theme.LocalCalculatorColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(appContainer: AppContainer, onNavigateToVault: () -> Unit) {
    val viewModel = remember {
        CalculatorViewModel(appContainer.checkPinUseCase, onNavigateToVault)
    }
    val colors = LocalCalculatorColors.current

    Column(
        Modifier.fillMaxSize()
            .background(colors.displayBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Калькулятор", color = colors.displayText, style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.displayBackground)
        )

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (historyNotEmpty(viewModel.history)) {
                Text(viewModel.history, color = colors.historyText, fontSize = 18.sp, modifier = Modifier.padding(end = 16.dp))
            }
            Text(
                text = if (viewModel.display.isEmpty()) "0" else viewModel.display,
                color = if (viewModel.isError) MaterialTheme.colorScheme.error else colors.displayText,
                fontSize = if (viewModel.display.length > 12) 36.sp else 56.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (viewModel.pinHintVisible) {
            Text(
                stringResource(R.string.calc_hint_long_press),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                CalcButton("AC", { viewModel.onClear() }, isFunction = true, colors = colors, mod = Modifier.weight(1f))
                CalcButton("(", { viewModel.onParenthesisPressed("(") }, isFunction = true, colors = colors, mod = Modifier.weight(1f))
                CalcButton(")", { viewModel.onParenthesisPressed(")") }, isFunction = true, colors = colors, mod = Modifier.weight(1f))
                CalcButton("÷", { viewModel.onOperatorPressed("÷") }, isOperator = true, colors = colors, mod = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                CalcButton("7", { viewModel.onDigitPressed("7") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("8", { viewModel.onDigitPressed("8") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("9", { viewModel.onDigitPressed("9") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("×", { viewModel.onOperatorPressed("×") }, isOperator = true, colors = colors, mod = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                CalcButton("4", { viewModel.onDigitPressed("4") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("5", { viewModel.onDigitPressed("5") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("6", { viewModel.onDigitPressed("6") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("-", { viewModel.onOperatorPressed("-") }, isOperator = true, colors = colors, mod = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                CalcButton("1", { viewModel.onDigitPressed("1") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("2", { viewModel.onDigitPressed("2") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("3", { viewModel.onDigitPressed("3") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("+", { viewModel.onOperatorPressed("+") }, isOperator = true, colors = colors, mod = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                CalcButton("%", { viewModel.onPercentPressed() }, isFunction = true, colors = colors, mod = Modifier.weight(1f))
                CalcButton("0", { viewModel.onDigitPressed("0") }, colors = colors, mod = Modifier.weight(1f))
                CalcButton(".", { viewModel.onDecimalPressed() }, colors = colors, mod = Modifier.weight(1f))
                CalcButton("=", { viewModel.onEqualsPressed() }, onLongClick = { viewModel.onEqualsLongPressed() }, isOperator = true, colors = colors, mod = Modifier.weight(1f))
            }
        }
    }
}

private fun historyNotEmpty(h: String) = h.isNotEmpty()

@Composable
private fun CalcButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isOperator: Boolean = false,
    isFunction: Boolean = false,
    colors: com.google.android.calculator.presentation.theme.CalculatorColors,
    mod: Modifier
) {
    val bg = when {
        isOperator -> colors.operatorButton
        isFunction -> colors.functionButton
        else -> colors.digitButton
    }
    Box(
        modifier = mod.height(72.dp).clip(RoundedCornerShape(50)).background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { if (onLongClick != null) onLongClick() else onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.buttonText, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    }
}