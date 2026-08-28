package com.google.android.calculator.presentation.calculator

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.android.calculator.R
import com.google.android.calculator.data.parser.SimpleExpressionParser
import com.google.android.calculator.domain.usecase.CheckPinUseCase

class CalculatorViewModel(
    private val checkPinUseCase: CheckPinUseCase,
    private val context: Context,
    private val onVaultOpened: () -> Unit
) : ViewModel() {

    var display by mutableStateOf("")
        private set
    var history by mutableStateOf("")
        private set
    var isError by mutableStateOf(false)
        private set
    var pinHintVisible by mutableStateOf(false)
        private set

    private val parser = SimpleExpressionParser()

    fun onDigitPressed(digit: String) {
        if (isError) onClear()
        pinHintVisible = false
        display += digit
    }

    fun onOperatorPressed(op: String) {
        if (isError) return
        pinHintVisible = false
        if (display.isNotEmpty() && display.last() !in "+-×÷*/") {
            display += op
        }
    }

    fun onDecimalPressed() {
        if (isError) return
        val lastNumber = Regex("[\\d.]+$").find(display)?.value ?: ""
        if (!lastNumber.contains(".")) display += "."
    }

    fun onParenthesisPressed(paren: String) {
        if (isError) return        
        display += paren
    }

    fun onClear() {
        display = ""
        history = ""
        isError = false
        pinHintVisible = false
    }

    fun onBackspace() {
        if (display.isNotEmpty()) display = display.dropLast(1)
    }

    fun onPercentPressed() {
        if (isError || display.isEmpty()) return
        try {
            val current = parser.evaluate(display)
            display = formatResult(current / 100)
        } catch (e: Exception) {
            isError = true
            display = context.getString(R.string.calc_error)
        }
    }

    fun onEqualsPressed() {
        if (display.isEmpty()) return
        pinHintVisible = false
        try {
            val result = parser.evaluate(display)
            history = "$display ="
            display = formatResult(result)
            isError = false
        } catch (e: Exception) {
            isError = true
            display = context.getString(R.string.calc_error)
        }
    }

    fun onEqualsLongPressed() {
        if (display.isEmpty()) {
            pinHintVisible = true
            return
        }
        if (checkPinUseCase(display)) {
            onVaultOpened()
            onClear()
        } else {
            pinHintVisible = true
            isError = true            
            display = context.getString(R.string.calc_invalid_pin)
        }
    }

    private fun formatResult(result: Double): String =
        if (result.isNaN() || result.isInfinite()) context.getString(R.string.calc_error)
        else if (result == result.toLong().toDouble()) result.toLong().toString()
        else "%.8f".format(result).trimEnd('0').trimEnd('.')
}