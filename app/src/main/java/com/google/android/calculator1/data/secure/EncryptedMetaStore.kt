package com.google.android.calculator1.data.secure

import android.content.Context
import com.google.android.calculator1.data.crypto.CryptoManager
import java.io.File

/**
 * Хранит текстовые метаданные (список строк) в зашифрованном виде на диске —
 * тот же принцип, что и EncryptedSharedPreferences: ключ живёт в AndroidKeystore,
 * а не в самом файле.
 *
 * Файл целиком перезаписывается при каждом writeLines() (как и раньше с обычным
 * File.writeText()) — это ок для метаданных хранилища (списки файлов/папок/точек),
 * но не годится для больших/часто обновляемых данных.
 *
 * При первом обращении автоматически мигрирует данные из старого НЕзашифрованного
 * файла (если он остался от предыдущей версии приложения), чтобы не потерять
 * данные у уже существующих пользователей.
 */
class EncryptedMetaStore(
    private val context: Context,
    private val fileName: String,
    private val legacyPlaintextFileName: String? = null
) {
    private val encryptedFile = File(context.filesDir, fileName)
    private val legacyFile = legacyPlaintextFileName?.let { File(context.filesDir, it) }

    fun readLines(password: String): List<String> {
        return try {
            if (encryptedFile.exists()) {
                val tempOut = File.createTempFile("meta_dec_", ".txt", context.cacheDir)
                try {
                    CryptoManager.decrypt(encryptedFile, tempOut, password)
                    tempOut.readLines()
                } finally {
                    tempOut.delete()
                }
            } else if (legacyFile?.exists() == true) {
                // Миграция со старого открытого формата
                val lines = legacyFile.readLines()
                writeLines(lines, password)
                legacyFile.delete()
                lines
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeLines(lines: List<String>, password: String) {
        try {
            val tempIn = File.createTempFile("meta_enc_", ".txt", context.cacheDir)
            try {
                tempIn.writeText(lines.joinToString("\n"))
                val tempOut = File.createTempFile("meta_out_", ".enc", context.cacheDir)
                try {
                    CryptoManager.encrypt(tempIn, tempOut, password)
                    tempOut.copyTo(encryptedFile, overwrite = true)
                } finally {
                    tempOut.delete()
                }
            } finally {
                tempIn.delete()
            }
        } catch (e: Exception) {
            throw e
        }
    }
}