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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.calculator.R
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.presentation.theme.CalculatorColors
import com.google.android.calculator.presentation.theme.LocalCalculatorColors

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(
    appContainer: AppContainer,
    onNavigateToVault: () -> Unit
) {

    val context = LocalContext.current
    val viewModel = remember {
        CalculatorViewModel(appContainer.checkPinUseCase, context, onNavigateToVault)
    }
    val colors = LocalCalculatorColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(colors.displayBackground)
            .padding(16.dp)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name),
                        color = colors.displayText) },
            colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.displayBackground)
        )

        CalculatorDisplay(
            display = viewModel.display,
            history = viewModel.history,
            isError = viewModel.isError,
            colors = colors
        )

        /*if (viewModel.pinHintVisible) {
            Text(
                stringResource(R.string.calc_hint_long_press),                
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }*/

        Spacer(modifier = Modifier.weight(1f))

        CalculatorKeypad(viewModel, colors)
    }
}

@Composable
private fun CalculatorDisplay(
    display: String,
    history: String,
    isError: Boolean,
    colors: CalculatorColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (history.isNotEmpty()) {
            Text(
                text = history,
                color = colors.historyText,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        Text(
            text = if (display.isEmpty()) "0" else display,
            color = if (isError) MaterialTheme.colorScheme.error else colors.displayText,
            fontSize = if (display.length > 12) 36.sp else 56.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun CalculatorKeypad(viewModel: CalculatorViewModel, colors: CalculatorColors) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
            CalcButton("%", viewModel::onPercentPressed, isFunction = true, colors = colors, mod = Modifier.weight(1f))
            CalcButton("0", { viewModel.onDigitPressed("0") }, colors = colors, mod = Modifier.weight(1f))
            CalcButton(".", viewModel::onDecimalPressed, colors = colors, mod = Modifier.weight(1f))
            CalcButton(
                text = "=",
                onClick = viewModel::onEqualsPressed,
                onLongClick = viewModel::onEqualsLongPressed,
                isOperator = true,
                colors = colors,
                mod = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CalcButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isOperator: Boolean = false,
    isFunction: Boolean = false,
    colors: CalculatorColors,
    mod: Modifier
) {
    val bg = when {
        isOperator -> colors.operatorButton
        isFunction -> colors.functionButton
        else -> colors.digitButton
    }

    // Используем Box с pointerInput для надежного перехвата и короткого, и долгого нажатия
    Box(
        modifier = mod
            .height(72.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { 
                        if (onLongClick != null) {
                            onLongClick()
                        } else {
                            onClick()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = colors.buttonText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium
        )
    }
}