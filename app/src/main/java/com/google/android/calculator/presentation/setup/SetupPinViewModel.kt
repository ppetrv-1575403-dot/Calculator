package com.google.android.calculator.presentation.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.android.calculator.domain.usecase.SavePinUseCase

class SetupPinViewModel(private val savePinUseCase: SavePinUseCase) : ViewModel() {
    var pin by mutableStateOf(""); private set
    var confirmPin by mutableStateOf(""); private set
    var errorMessage by mutableStateOf<String?>(null); private set

    fun onPinChange(v: String) { pin = v.filter { it.isDigit() } }
    fun onConfirmChange(v: String) { confirmPin = v.filter { it.isDigit() } }

    fun save(onSuccess: () -> Unit) {
        when {
            pin.length < 4 -> errorMessage = "Минимум 4 цифры"
            pin != confirmPin -> errorMessage = "PIN-коды не совпадают"
            else -> {
                val ok: Boolean = savePinUseCase(pin)
                if (ok) onSuccess() else errorMessage = "Ошибка сохранения"
            }
        }
    }
}