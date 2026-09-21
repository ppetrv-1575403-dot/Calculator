package com.google.android.calculator.data.secure

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
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
    fileName: String,
    private val legacyPlaintextFileName: String? = null
) {
    private val masterKey by lazy {
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private val file = File(context.filesDir, fileName)
    private val backupFile = File(context.filesDir, "$fileName.bak")

    fun readLines(): List<String> {
        migrateLegacyIfNeeded()
        if (!file.exists()) return emptyList()
        return try {
            decryptLines(file)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun writeLines(lines: List<String>) {
        val payload = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

        // Бэкапим текущие (уже зашифрованные) байты как есть — простое копирование,
        // без пересборки через EncryptedFile, поэтому путь файла для расшифровки не меняется.
        if (file.exists()) {
            file.copyTo(backupFile, overwrite = true)
        }

        try {
            // ВАЖНО: пишем СРАЗУ в целевой файл, а не во временный с последующим
            // переименованием. EncryptedFile привязывает зашифрованные данные к
            // конкретному пути файла — переименование после шифрования ломает
            // расшифровку (данные технически на диске, но не читаются).
            if (file.exists()) file.delete()
            val encryptedFile = EncryptedFile.Builder(
                context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encryptedFile.openFileOutput().use {
                it.write(payload)
            }
            backupFile.delete()
        } catch (e: Exception) {
            // Что-то пошло не так посреди записи — восстанавливаем предыдущую версию из бэкапа,
            // чтобы не потерять уже сохранённые данные.
            if (backupFile.exists()) {
                if (file.exists()) file.delete()
                backupFile.copyTo(file, overwrite = true)
                backupFile.delete()
            }
            throw e
        }
    }

    private fun decryptLines(target: File): List<String> {
        val encryptedFile = EncryptedFile.Builder(
            context, target, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        val bytes = encryptedFile.openFileInput().use { it.readBytes() }
        return bytes.toString(Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
    }

    /** Одноразовая миграция со старого незашифрованного файла прошлых версий приложения. */
    private fun migrateLegacyIfNeeded() {
        if (legacyPlaintextFileName == null) return
        if (file.exists()) return // уже мигрировали (или это новый профиль)
        val legacy = File(context.filesDir, legacyPlaintextFileName)
        if (!legacy.exists()) return
        try {
            val lines = legacy.readLines().filter { it.isNotEmpty() }
            writeLines(lines)
            legacy.delete()
        } catch (e: Exception) {
            // Не получилось — оставляем старый файл как есть, попробуем ещё раз при следующем запуске.
        }
    }
}
