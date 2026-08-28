package com.google.android.calculator.presentation.setup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.android.calculator.R
import com.google.android.calculator.domain.usecase.SavePinUseCase

const val MIN_PIN_LENGTH = 4
const val MAX_PIN_LENGTH = 8
class SetupPinViewModel(
    private val savePinUseCase: SavePinUseCase,
    private val context: Context
) : ViewModel() {
    var pin by mutableStateOf("")
        private set
    var confirmPin by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun onPinChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= MAX_PIN_LENGTH) {
            pin = value
            errorMessage = null
        }
    }

    fun onConfirmChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= MAX_PIN_LENGTH) {
            confirmPin = value
            errorMessage = null
        }
    }

    fun save(onSuccess: () -> Unit) {
        when {
            pin.length < MIN_PIN_LENGTH -> errorMessage = context.getString(R.string.setup_error_min_length)
            pin != confirmPin -> errorMessage = context.getString(R.string.setup_error_mismatch)
            else -> {
                savePinUseCase(pin)
                onSuccess()
            }
        }
    }
}