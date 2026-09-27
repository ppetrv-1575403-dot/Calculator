package com.google.android.calculator.utils

/**
 * Действие с оригиналом файла после успешного переноса в хранилище.
 */
enum class OriginalFileAction {
    /** Оставить оригинал на устройстве */
    KEEP,
    /** Просто удалить оригинал */
    DELETE,
    /** Безопасно затереть (random + zeros) и удалить */
    SHRED
}