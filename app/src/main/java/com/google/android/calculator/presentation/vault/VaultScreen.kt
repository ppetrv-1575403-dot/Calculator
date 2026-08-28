package com.google.android.calculator.presentation.vault

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.calculator.R
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.presentation.utils.AndroidTools

@Composable
fun VaultScreen(
    appContainer: AppContainer,
    onBack: () -> Unit,
    onPreviewFile: (String) -> Unit // Теперь принимает только fileId
) {
    val context = LocalContext.current
    val viewModel = remember {
        com.google.android.calculator.presentation.vault.VaultViewModel(
            appContainer.addFileUseCase,
            appContainer.getFilesUseCase,
            appContainer.vaultRepository
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name =AndroidTools.queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.addFile(uri, name, mime)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onBack) { Text(stringResource(R.string.vault_close)) }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vault_add_file))
            }
            Spacer(Modifier.height(16.dp))

            if (viewModel.files.isEmpty()) {
                Text(
                    stringResource(R.string.vault_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn {
                    items(viewModel.files) { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { 
                                // Просто передаем ID при клике
                                onPreviewFile(file.id)
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(file.originalName, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    file.mimeType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

