package com.google.android.calculator1.presentation.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.calculator1.R
import com.google.android.calculator1.di.AppContainer
import com.google.android.calculator1.utils.VaultManager
import com.google.android.calculator1.utils.VaultMode

@Composable
fun SetupPinScreen(appContainer: AppContainer, onSuccess: () -> Unit) {
    val viewModel = remember { SetupPinViewModel(appContainer.savePinUseCase) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = viewModel.pin, onValueChange = viewModel::onPinChange,
                label = { Text(stringResource(R.string.setup_pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = viewModel.confirmPin, onValueChange = viewModel::onConfirmChange,
                label = { Text(stringResource(R.string.setup_confirm_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )
            viewModel.errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    viewModel.save {
                        VaultManager.setMainPin(viewModel.pin)
                        VaultManager.enterMode(VaultMode.REAL)
                        onSuccess()
                    }
                },
                Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setup_save))
            }
        }
    }
}