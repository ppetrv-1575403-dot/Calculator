package com.google.android.calculator1.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        // Используем внешнюю папку приложения (доступна через файловый менеджер)
        val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
        dir.mkdirs()
        logFile = File(dir, LOG_FILE)
        
        logFile?.let { file ->
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                file.delete()
            }
        }
        
        i("FileLogger", "=== Логгер инициализирован ===")
        i("FileLogger", "Путь к логам: ${logFile?.absolutePath}")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeToFile("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeToFile("ERROR", tag, message)
        throwable?.let { t ->
            writeToFile("ERROR", tag, "Exception: ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(20).forEach { element ->
                writeToFile("ERROR", tag, "  at $element")
            }
        }
    }

    private fun writeToFile(level: String, tag: String, message: String) {
        try {
            val file = logFile ?: return
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp $level/$tag: $message\n"
            
            FileWriter(file, true).use { writer ->
                writer.append(logLine)
            }
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to write to file", e)
        }
    }

    fun getLogContent(context: Context): String {
        return try {
            val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
            val file = File(dir, LOG_FILE)
            if (file.exists()) {
                val content = file.readText()
                if (content.length > 100_000) {
                    "..." + content.substring(content.length - 100_000)
                } else {
                    content
                }
            } else {
                "Файл логов не найден"
            }
        } catch (e: Exception) {
            "Ошибка чтения логов: ${e.message}"
        }
    }

    fun getCrashContent(context: Context): String {
        return try {
            val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
            val file = File(dir, "crash.log")
            if (file.exists()) {
                file.readText()
            } else {
                "Крашей не записано"
            }
        } catch (e: Exception) {
            "Ошибка чтения: ${e.message}"
        }
    }

    fun getLogFilePath(context: Context): String {
        val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
        return File(dir, LOG_FILE).absolutePath
    }

    fun clearLogs(context: Context) {
        try {
            val dir = context.getExternalFilesDir(LOG_DIR) ?: File(context.filesDir, LOG_DIR)
            dir.listFiles()?.forEach { it.delete() }
            i("FileLogger", "Логи очищены")
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to clear logs", e)
        }
    }
}