package com.google.android.calculator1.utils

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    companion object {
        private const val CRASH_FILE = "crash.log"
        
        fun init(context: Context) {
            val handler = CrashHandler(context)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            dir.mkdirs()
            val crashFile = File(dir, CRASH_FILE)
            
            val timestamp = dateFormat.format(Date())
            val crashInfo = buildString {
                append("=== КРАШ ===\n")
                append("Время: $timestamp\n")
                append("Поток: ${thread.name}\n")
                append("Исключение: ${throwable.javaClass.name}\n")
                append("Сообщение: ${throwable.message}\n\n")
                append("Стектрейс:\n")
                throwable.stackTrace.forEach { element ->
                    append("  at $element\n")
                }
                
                throwable.cause?.let { cause ->
                    append("\nПричина: ${cause.javaClass.name}: ${cause.message}\n")
                    cause.stackTrace.take(10).forEach { element ->
                        append("  at $element\n")
                    }
                }
                append("\n")
            }
            
            FileWriter(crashFile, true).use { writer ->
                writer.append(crashInfo)
            }
        } catch (e: Exception) {
            // Тихо игнорируем
        }
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (defaultHandler != this) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}