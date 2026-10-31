package com.google.android.calculator1.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

object CoordinateRecognizer {

    data class RecognizedCoords(val latitude: Double, val longitude: Double, val source: String)

    suspend fun recognize(context: Context, uri: Uri): RecognizedCoords? {
        fromExif(context, uri)?.let {
            FileLogger.i("OCR", "✅ Координаты найдены в EXIF: lat=${it.first}, lon=${it.second}")
            return RecognizedCoords(it.first, it.second, "EXIF")
        }
        val text = ocrText(context, uri) ?: return null
        FileLogger.i("OCR", "=== Распознанный текст (${text.length} символов) ===\n$text\n========================")
        return fromText(text)?.let { RecognizedCoords(it.first, it.second, "OCR") }
    }

    // === EXIF ===

    private fun fromExif(context: Context, uri: Uri): Pair<Double, Double>? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).latLong?.let { Pair(it[0], it[1]) }
        }
    } catch (e: Exception) { null }

    // === OCR с предобработкой ===

    private suspend fun runRecognizer(image: InputImage, recognizer: TextRecognizer): String? =
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (e: Exception) { null }

    private suspend fun ocrText(context: Context, uri: Uri): String? {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            // 1) Оригинал
            val originalImage = InputImage.fromFilePath(context, uri)
            val original = runRecognizer(originalImage, recognizer)

            val origNumbers = GPS_NUMBER.findAll(original.orEmpty()).map { it.value }.toList()
            FileLogger.d("OCR", "Оригинал: найдено GPS-подобных чисел: ${origNumbers.size} — $origNumbers")

            if (!original.isNullOrBlank() && origNumbers.size >= 2) {
                return original
            }

            // 2) Upscale 3x + усиление контраста + поворот по EXIF
            val processed = loadAndProcess(context, uri)
            if (processed != null) {
                val image2 = InputImage.fromBitmap(processed, 0)
                val result2 = runRecognizer(image2, recognizer)
                processed.recycle()

                val procNumbers = GPS_NUMBER.findAll(result2.orEmpty()).map { it.value }.toList()
                FileLogger.d("OCR", "После обработки: найдено GPS-подобных чисел: ${procNumbers.size} — $procNumbers")

                if (!result2.isNullOrBlank()) return result2
            }

            original
        } catch (e: Exception) {
            FileLogger.e("OCR", "OCR failed", e)
            null
        }
    }

    /**
     * Загружает фото с учётом EXIF-поворота, увеличивает в 3 раза,
     * усиливает контраст (белый штамп на светлом фоне читается гораздо лучше).
     */
    private fun loadAndProcess(context: Context, uri: Uri): Bitmap? {
        return try {
            // 1) Читаем EXIF rotation
            val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            // 2) Загружаем оригинал
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // 3) Поворачиваем по EXIF
            val rotated = if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                r
            } else bmp

            // 4) Upscale 3x
            val scale = 3f
            val w = (rotated.width * scale).toInt().coerceAtMost(4096)
            val h = (rotated.height * scale).toInt().coerceAtMost(4096)
            val scaled = Bitmap.createScaledBitmap(rotated, w, h, true)
            if (scaled !== rotated) rotated.recycle()

            // 5) Усиление контраста: делаем белые области ещё белее, тёмные — ещё темнее
            val enhanced = Bitmap.createBitmap(scaled.width, scaled.height, scaled.config)
            val canvas = android.graphics.Canvas(enhanced)
            val paint = Paint().apply {
                val cm = ColorMatrix(floatArrayOf(
                    1.5f, 0f, 0f, 0f, -40f,
                    0f, 1.5f, 0f, 0f, -40f,
                    0f, 0f, 1.5f, 0f, -40f,
                    0f, 0f, 0f, 1f, 0f
                ))
                colorFilter = ColorMatrixColorFilter(cm)
                isAntiAlias = true
            }
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            scaled.recycle()

            enhanced
        } catch (e: Exception) {
            FileLogger.e("OCR", "Image processing failed", e)
            null
        }
    }

    // === Парсинг координат ===

    /**
     * Ищем числа, похожие на GPS-координаты:
     * - 1-3 цифры целой части + точка + минимум 4 знака после точки
     * Это отрезает дату (2024), время (14:32:05), короткие числа,
     * и оставляет только настоящие координаты типа 52.034512
     */
    private val GPS_NUMBER = Regex("""\b(\d{1,3}\.\d{4,8})\b""")

    private val DMS_PATTERN = Regex(
        """(\d{1,2})\s*[°º]\s*(\d{1,2})\s*[′'`]\s*(\d{1,2}(?:\.\d+)?)\s*[″"]?\s*([NSns])\s*[,;]?\s*(\d{1,3})\s*[°º]\s*(\d{1,2})\s*[′'`]\s*(\d{1,2}(?:\.\d+)?)\s*[″"]?\s*([EWew])"""
    )
    private val HEMI_BEFORE = Regex("""([NSns])\s*(\d{1,2}\.\d+)\s*[,;]?\s*([EWew])\s*(\d{1,3}\.\d+)""")
    private val HEMI_AFTER = Regex("""(\d{1,2}\.\d+)\s*°?\s*([NSns])\s*[,;]?\s*(\d{1,3}\.\d+)\s*°?\s*([EWew])""")

    private val LABELED = Regex(
        """(?:широта|latitude|lat)\s*[:：]?\s*(-?\d{1,2}(?:\.\d+)?)[\s\S]{0,80}?(?:долгота|longitude|lon|lng)\s*[:：]?\s*(-?\d{1,3}(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    private val LABELED_REVERSE = Regex(
        """(?:долгота|longitude|lon|lng)\s*[:：]?\s*(-?\d{1,3}(?:\.\d+)?)[\s\S]{0,80}?(?:широта|latitude|lat)\s*[:：]?\s*(-?\d{1,2}(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    fun fromText(rawText: String): Pair<Double, Double>? {
        val t = rawText.replace(',', '.')

        // 1) Формальные форматы
        DMS_PATTERN.find(t)?.let { m ->
            val g = m.groupValues
            val lat = dmsToDec(g[1].toDouble(), g[2].toDouble(), g[3].toDouble(), g[4])
            val lon = dmsToDec(g[5].toDouble(), g[6].toDouble(), g[7].toDouble(), g[8])
            if (isValid(lat, lon)) return Pair(lat, lon)
        }
        HEMI_BEFORE.find(t)?.let { m ->
            val g = m.groupValues
            if (isValid(g[2].toDouble(), g[4].toDouble()))
                return Pair(applyHemi(g[2].toDouble(), g[1]), applyHemi(g[4].toDouble(), g[3]))
        }
        HEMI_AFTER.find(t)?.let { m ->
            val g = m.groupValues
            if (isValid(g[1].toDouble(), g[3].toDouble()))
                return Pair(applyHemi(g[1].toDouble(), g[2]), applyHemi(g[3].toDouble(), g[4]))
        }
        LABELED.find(t)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isValid(lat, lon)) return Pair(lat, lon)
        }
        LABELED_REVERSE.find(t)?.let { m ->
            val lon = m.groupValues[1].toDoubleOrNull()
            val lat = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && isValid(lat, lon)) return Pair(lat, lon)
        }

        // 2) ⭐ ИСПРАВЛЕНО: берём числа В ПОРЯДКЕ ПОЯВЛЕНИЯ в тексте
        val gpsNumbers = GPS_NUMBER.findAll(t)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()

        FileLogger.d("OCR", "GPS-числа в порядке появления: $gpsNumbers")

        if (gpsNumbers.size >= 2) {
            // Берём ПЕРВЫЕ два числа — в NoteCam первое = широта, второе = долгота
            val first = gpsNumbers[0]
            val second = gpsNumbers[1]

            FileLogger.d("OCR", "Первое число: $first, второе: $second")

            // Проверка: если первое явно не широта (> 90), меняем местами
            return when {
                isValid(first, second) -> Pair(first, second)
                isValid(second, first) -> Pair(second, first)
                else -> null
            }
        }

        return null
    }
    
    private fun dmsToDec(deg: Double, min: Double, sec: Double, hemi: String): Double =
        applyHemi(deg + min / 60.0 + sec / 3600.0, hemi)

    private fun applyHemi(value: Double, hemi: String): Double =
        if (hemi.equals("S", true) || hemi.equals("W", true) || hemi.equals("Ю", true) || hemi.equals("З", true)) -value else value

    private fun isValid(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0 && (abs(lat) > 0.0 || abs(lon) > 0.0)
}