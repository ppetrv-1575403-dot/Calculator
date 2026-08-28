package com.google.android.calculator.domain.usecase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ClearCacheUseCase(private val context: Context) {
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        context.cacheDir?.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_dec_") || file.name.startsWith("temp_in_")) {
                file.delete()
            }
        }
    }
}