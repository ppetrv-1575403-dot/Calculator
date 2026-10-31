package com.google.android.calculator1.domain.usecase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ClearCacheUseCase(private val context: Context) {
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        context.cacheDir?.listFiles()?.forEach { file ->
            // Реальные временные файлы хранилища создаются с префиксом "vault_temp_"
            // (см. VaultRepositoryImpl: vault_temp_in_*, vault_temp_out_*).
            if (file.name.startsWith("vault_temp_")) {
                file.delete()
            }
        }
    }
}