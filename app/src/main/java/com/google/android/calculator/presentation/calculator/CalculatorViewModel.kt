package com.google.android.calculator.presentation.calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.usecase.CheckPinUseCase
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CalculatorViewModel(
    private val checkPinUseCase: CheckPinUseCase,
    private val onVaultOpened: () -> Unit,
    private val appContainer: AppContainer
) : ViewModel() {

    var display by mutableStateOf(""); private set
    var history by mutableStateOf(""); private set
    var historyEntries by mutableStateOf<List<String>>(emptyList()); private set
    var isError by mutableStateOf(false); private set
    var pinHintVisible by mutableStateOf(false); private set
    var angleInDegrees by mutableStateOf(true); private set
    var isInverseMode by mutableStateOf(false); private set
    var isUnlocking by mutableStateOf(false); private set
    var unlockError by mutableStateOf<String?>(null); private set
    var needsContainerReset by mutableStateOf(false); private set

    fun onDigitPressed(d: String) {
        if (isError) onClear()
        pinHintVisible = false
        display += d
    }

    fun onDecimalPressed() {
        if (isError) onClear()
        val last = getLastNumber()
        if (!last.contains('.')) display += if (last.isEmpty()) "0." else "."
    }

    fun onOperatorPressed(op: String) {
        if (isError) return
        pinHintVisible = false
        if (display.isNotEmpty()) display += op
    }

    fun onParenthesisPressed() {
        if (isError) onClear()
        val open = display.count { it == '(' }
        val close = display.count { it == ')' }
        val last = display.lastOrNull()
        val shouldClose = open > close && last != null &&
                (last.isDigit() || last == ')' || last == '²' || last == '!' || last == 'π' || last == 'e')
        display += if (shouldClose) ")" else "("
    }

    fun onPercentPressed() {
        if (isError) return
        val lastNum = getLastNumber()
        val v = lastNum.toDoubleOrNull() ?: return
        display = display.removeSuffix(lastNum) + formatResult(v / 100)
    }

    fun onBackspacePressed() {
        if (isError) { onClear(); return }
        if (display.isNotEmpty()) display = display.dropLast(1)
    }

    fun onClear() {
        display = ""; history = ""; isError = false; pinHintVisible = false
    }

    fun onEqualsPressed() {
        if (display.isEmpty() || isUnlocking) return
        try {
            val expr = display
            val result = Parser(expr, angleInDegrees).parse()
            val formatted = formatResult(result)
            history = "$expr ="
            historyEntries = (historyEntries + "$expr = $formatted").takeLast(50)
            display = formatted
            isError = false
        } catch (e: Exception) {
            isError = true
        }
    }

    fun onEqualsLongPressed() {
        if (isUnlocking) return
        if (display.isEmpty()) { pinHintVisible = true; return }
        val entered = display.trim()

        val slot = VaultManager.authenticateSlot(entered)
        if (slot != null) {
            startUnlock(slot.id)
            return
        }

        if (checkPinUseCase(entered)) {
            if (!VaultManager.hasMainPin()) {
                VaultManager.setMainPin(entered)
                FileLogger.i("Vault", "Main PIN migrated from PinRepository")
            }
            VaultManager.setSessionPin(entered)
            startUnlock(VaultManager.REAL_ID)
        } else {
            pinHintVisible = true
        }
    }

    private fun startUnlock(slotId: String) {
        FileLogger.i("Vault", "Unlock accepted: slotId=$slotId")
        isUnlocking = true
        unlockError = null
        pinHintVisible = false
        VaultManager.enterSlot(slotId)
        appContainer.invalidateCacheForSlot(slotId)

        viewModelScope.launch {
            val start = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    FileLogger.d("Vault", "Warming up repositories for slot $slotId")
                    appContainer.vaultRepositoryForSlot(slotId)
                    appContainer.locationRepositoryForSlot(slotId)
                    appContainer.folderRepositoryForSlot(slotId)
                    FileLogger.d("Vault", "Repositories warmed up successfully")
                }
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 250) delay(250 - elapsed)

                FileLogger.i("Vault", "Unlock complete in ${System.currentTimeMillis() - start} ms")
                onVaultOpened()
            } catch (e: Exception) {
                FileLogger.e("Calculator", "Unlock failed: ${e.message}", e)
                
                // ⭐ Проверяем, это ошибка совместимости контейнера
                if (e.message?.contains("AEADBadTagException") == true || 
                    e.message?.contains("BAD_DECRYPT") == true) {
                    unlockError = "Контейнер зашифрован старым методом"
                    needsContainerReset = true
                } else {
                    unlockError = when {
                        e.message?.contains("Container not unlocked") == true -> 
                            "Ошибка: PIN сессии не установлен"
                        e.message?.contains("Файл не является контейнером") == true -> 
                            "Ошибка: файл контейнера повреждён"
                        e.message?.contains("Неподдерживаемая версия") == true -> 
                            "Ошибка: несовместимая версия контейнера"
                        e.message?.contains("No active session PIN") == true -> 
                            "Ошибка: PIN не сохранён в сессии"
                        else -> "Ошибка: ${e.message ?: "неизвестная ошибка"}"
                    }
                    needsContainerReset = false
                }
            } finally {
                if (unlockError == null) {
                    isUnlocking = false
                    onClear()
                } else {
                    isUnlocking = false
                }
            }
        }
    }

    // ⭐ Сброс контейнера и создание нового
    fun resetContainer() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val slotId = VaultManager.currentSlotId
                    val containerFile = VaultManager.containerFile(slotId)
                    if (containerFile != null && containerFile.exists()) {
                        FileLogger.w("Vault", "Deleting old container: ${containerFile.absolutePath}")
                        containerFile.delete()
                    }
                    // Пересоздаём репозитории с новым контейнером
                    appContainer.invalidateCacheForSlot(slotId)
                    appContainer.vaultRepositoryForSlot(slotId)
                    appContainer.locationRepositoryForSlot(slotId)
                    appContainer.folderRepositoryForSlot(slotId)
                    FileLogger.i("Vault", "New container created successfully")
                }
                needsContainerReset = false
                unlockError = null
                onVaultOpened()
            } catch (e: Exception) {
                FileLogger.e("Vault", "Failed to reset container", e)
                unlockError = "Не удалось создать новый контейнер: ${e.message}"
                needsContainerReset = false
            }
        }
    }

    fun cancelReset() {
        needsContainerReset = false
        unlockError = null
    }

    fun clearUnlockError() {
        unlockError = null
        needsContainerReset = false
    }

    fun useHistoryEntry(entry: String) {
        val result = entry.substringAfterLast('=').trim()
        if (result.isNotEmpty()) display = result
    }

    fun clearHistory() {
        historyEntries = emptyList()
        history = ""
    }

    fun toggleAngleMode() { angleInDegrees = !angleInDegrees }
    fun toggleInverseMode() { isInverseMode = !isInverseMode }

    fun onSinePressed() {
        if (isError) onClear()
        display += if (isInverseMode) "asin(" else "sin("
    }

    fun onCosinePressed() {
        if (isError) onClear()
        display += if (isInverseMode) "acos(" else "cos("
    }

    fun onTangentPressed() {
        if (isError) onClear()
        display += if (isInverseMode) "atan(" else "tan("
    }

    fun onLogPressed() {
        if (isError) onClear()
        display += "log("
    }

    fun onLnPressed() {
        if (isError) onClear()
        display += "ln("
    }

    fun onSquareRootPressed() {
        if (isError) onClear()
        display += "√("
    }

    fun onSquarePressed() {
        if (isError) return
        display += "²"
    }

    fun onPowerPressed() {
        if (isError) return
        display += "^"
    }

    fun onFactorialPressed() {
        if (isError) return
        display += "!"
    }

    fun onPiPressed() {
        if (isError) onClear()
        display += "π"
    }

    fun onEPressed() {
        if (isError) onClear()
        display += "e"
    }

    private fun getLastNumber(): String {
        return display.split('+', '-', '×', '÷', '(', ')', '^').last()
    }

    private fun formatResult(v: Double): String {
        if (v.isNaN() || v.isInfinite()) throw ArithmeticException()
        return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    }

    private class Parser(private val s: String, private val deg: Boolean) {
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
            var v = parsePower()
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val op = s[i++]; val r = parsePower()
                v = if (op == '×') v * r else v / r
            }
            return v
        }

        private fun parsePower(): Double {
            var v = parsePostfix()
            while (i < s.length && s[i] == '^') {
                i++
                val r = parsePostfix()
                v = Math.pow(v, r)
            }
            return v
        }

        private fun parsePostfix(): Double {
            var v = parseFactor()
            while (i < s.length && (s[i] == '²' || s[i] == '!')) {
                if (s[i] == '²') v = v * v else v = factorial(v)
                i++
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

            if (i < s.length) {
                val rest = s.substring(i)
                when {
                    rest.startsWith("asin(") -> { i += 5; val v = parseExpr(); skipClose(); return fromRad(Math.asin(v)) }
                    rest.startsWith("acos(") -> { i += 5; val v = parseExpr(); skipClose(); return fromRad(Math.acos(v)) }
                    rest.startsWith("atan(") -> { i += 5; val v = parseExpr(); skipClose(); return fromRad(Math.atan(v)) }
                    rest.startsWith("sin(") -> { i += 4; val v = parseExpr(); skipClose(); return Math.sin(toRad(v)) }
                    rest.startsWith("cos(") -> { i += 4; val v = parseExpr(); skipClose(); return Math.cos(toRad(v)) }
                    rest.startsWith("tan(") -> { i += 4; val v = parseExpr(); skipClose(); return Math.tan(toRad(v)) }
                    rest.startsWith("log(") -> { i += 4; val v = parseExpr(); skipClose(); return Math.log10(v) }
                    rest.startsWith("ln(") -> { i += 3; val v = parseExpr(); skipClose(); return Math.log(v) }
                    rest.startsWith("√(") -> { i += 2; val v = parseExpr(); skipClose(); return Math.sqrt(v) }
                }
            }

            if (i < s.length && s[i] == 'π') { i++; return Math.PI }
            if (i < s.length && s[i] == 'e') { i++; return Math.E }

            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw Exception("Parse error")
            return s.substring(start, i).toDouble()
        }

        private fun skipClose() {
            if (i < s.length && s[i] == ')') i++
        }

        private fun toRad(v: Double) = if (deg) Math.toRadians(v) else v
        private fun fromRad(v: Double) = if (deg) Math.toDegrees(v) else v

        private fun factorial(n: Double): Double {
            if (n < 0 || n % 1.0 != 0.0) throw ArithmeticException()
            if (n > 170) return Double.POSITIVE_INFINITY
            var r = 1.0
            var k = 2.0
            while (k <= n) { r *= k; k += 1.0 }
            return r
        }
    }
}