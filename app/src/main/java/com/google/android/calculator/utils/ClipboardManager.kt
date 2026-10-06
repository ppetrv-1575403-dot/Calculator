package com.google.android.calculator.utils

import android.content.ClipData
import android.content.Context
import java.util.regex.Pattern

object ClipboardManager {
    private const val TAG = "ClipboardManager"
    
    // Паттерны для определения геоданных
    private val GEO_PATTERNS = listOf(
        Pattern.compile("-?\\d{1,3}\\.\\d+\\s*,\\s*-?\\d{1,3}\\.\\d+"),
        Pattern.compile("-?\\d+\\.\\d+\\s*[NSEW]"),
        Pattern.compile("Точка:.*\\d+\\.\\d+.*\\d+\\.\\d+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Широта:.*\\d+\\.\\d+.*Долгота:.*\\d+\\.\\d+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\"latitude\"\\s*:\\s*-?\\d+\\.\\d+.*\"longitude\"\\s*:\\s*-?\\d+\\.\\d+"),
        Pattern.compile("geo:-?\\d+\\.\\d+,-?\\d+\\.\\d+"),
        Pattern.compile("-?\\d{1,3}\\.\\d+\\s+-?\\d{1,3}\\.\\d+")
    )

    /**
     * Проверяет буфер обмена напрямую и очищает, если там геоданные.
     * Вызывать в onPause() Activity, пока приложение ещё на переднем плане.
     */
    fun checkAndClearIfGeoData(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            
            if (clip == null || clip.itemCount == 0) {
                FileLogger.d(TAG, "Буфер обмена пуст (нет ClipData)")
                return
            }
            
            val item = clip.getItemAt(0)
            val text = item.text?.toString() 
                ?: item.coerceToText(context)?.toString()
                ?: ""
            
            FileLogger.d(TAG, "=== Буфер обмена ===")
            FileLogger.d(TAG, "  text: '${text.take(200)}'")
            FileLogger.d(TAG, "  uri: '${item.uri}'")
            FileLogger.d(TAG, "===================")
            
            if (text.isBlank()) {
                FileLogger.d(TAG, "Буфер пуст или не содержит текст")
                return
            }
            
            for (pattern in GEO_PATTERNS) {
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    FileLogger.i(TAG, "✅ Обнаружены геоданные: '${matcher.group()}'")
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    FileLogger.i(TAG, "✅ Буфер обмена очищен")
                    return
                }
            }
            
            FileLogger.d(TAG, "Геоданные не обнаружены")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Ошибка проверки буфера: ${e.message}", e)
        }
    }

    /**
     * Совместимость со старым кодом.
     */
    fun clearClipboardIfGeoData(context: Context) {
        checkAndClearIfGeoData(context)
    }
}