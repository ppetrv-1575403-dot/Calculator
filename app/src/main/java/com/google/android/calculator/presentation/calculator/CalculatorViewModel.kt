package com.google.android.calculator.presentation.calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.android.calculator.domain.usecase.CheckPinUseCase
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode

class CalculatorViewModel(
    private val checkPinUseCase: CheckPinUseCase,
    private val onVaultOpened: () -> Unit
) : ViewModel() {

    var display by mutableStateOf(""); private set
    var history by mutableStateOf(""); private set
    var isError by mutableStateOf(false); private set
    var pinHintVisible by mutableStateOf(false); private set

    fun onDigitPressed(d: String) {
        if (isError) onClear()
        pinHintVisible = false
        display += d
    }

    fun onDecimalPressed() {
        if (isError) onClear()
        val last = display.split('+', '-', '×', '÷', '(', ')').last()
        if (!last.contains('.')) display += if (last.isEmpty()) "0." else "."
    }

    fun onOperatorPressed(op: String) {
        if (isError) return
        pinHintVisible = false
        if (display.isNotEmpty()) display += op
    }

    fun onParenthesisPressed(p: String) {
        if (isError) onClear()
        val open = display.count { it == '(' }
        val close = display.count { it == ')' }
        if (p == "(") display += p
        else if (open > close) display += p
    }

    fun onPercentPressed() {
        if (isError) return
        val lastNum = display.split('+', '-', '×', '÷', '(', ')').last()
        val v = lastNum.toDoubleOrNull() ?: return
        display = display.removeSuffix(lastNum) + formatResult(v / 100)
    }

    fun onClear() {
        display = ""; history = ""; isError = false; pinHintVisible = false
    }

    fun onEqualsPressed() {
        if (display.isEmpty()) return
        try {
            val result = Parser(display).parse()
            history = "$display ="
            display = formatResult(result)
            isError = false
        } catch (e: Exception) {
            isError = true
        }
    }

    /**
     * Долгое нажатие "=" — ввод PIN.
     * Проверяем через VaultManager (знает оба PIN: основной и фейковый).
     */
    fun onEqualsLongPressed() {
        if (display.isEmpty()) { pinHintVisible = true; return }
        val entered = display.trim()

        // 1) Основная проверка: VaultManager знает оба PIN
        val mode = VaultManager.authenticate(entered)
        if (mode != null) {
            FileLogger.i("Vault", "Unlock via VaultManager: mode=$mode")
            VaultManager.enterMode(mode)
            onVaultOpened()
            onClear()
            return
        }

        // 2) Fallback для старых установок: PIN есть только в PinRepository.
        //    При успешном входе мигрируем PIN в VaultManager.
        if (checkPinUseCase(entered)) {
            if (!VaultManager.hasMainPin()) {
                VaultManager.setMainPin(entered)
                FileLogger.i("Vault", "Main PIN migrated from PinRepository")
            }
            VaultManager.enterMode(VaultMode.REAL)
            onVaultOpened()
            onClear()
        } else {
            pinHintVisible = true
        }
    }

    private fun formatResult(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException()
        return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double {
            val v = parseExpr()
            if (i < s.length) throw Exception("Parse error")
            return v
        }
        private fun parseExpr(): Double {
            var v = parseTerm()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun parseTerm(): Double {
            var v = parseFactor()
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val op = s[i++]; val r = parseFactor()
                v = if (op == '×') v * r else v / r
            }
            return v
        }
        private fun parseFactor(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = parseExpr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw Exception("Parse error")
            return s.substring(start, i).toDouble()
        }
    }
}