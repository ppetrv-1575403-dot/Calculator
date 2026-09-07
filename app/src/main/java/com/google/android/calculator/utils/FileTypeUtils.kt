package com.google.android.calculator.utils

object FileTypeUtils {
    // Расширения текстовых файлов
    private val TEXT_EXTENSIONS = setOf(
        // Языки программирования
        "py", "js", "ts", "java", "kt", "kts", "cpp", "c", "h", "hpp", "cs", "php", "rb", "go", "rs", "swift",
        // Веб
        "html", "htm", "css", "scss", "sass", "less", "xml", "json", "yaml", "yml", "toml",
        // Скрипты
        "sh", "bash", "zsh", "ps1", "bat", "cmd",
        // Документы
        "txt", "md", "markdown", "rst", "log", "csv", "ini", "cfg", "conf",
        // Другие
        "sql", "graphql", "proto", "dart", "lua", "pl", "pm"
    )

    fun isTextFile(fileName: String, mimeType: String): Boolean {
        // Если MIME-тип явно текстовый
        if (mimeType.startsWith("text/")) return true
        
        // Если MIME-тип application и похож на текст
        if (mimeType in listOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-sh",
            "application/x-python",
            "application/x-yaml"
        )) return true
        
        // Проверяем расширение
        val extension = fileName.substringAfterLast(".", "").lowercase()
        
        // Если нет расширения — предполагаем, что это текстовый файл
        if (extension.isEmpty()) return true
        
        return extension in TEXT_EXTENSIONS
    }

    fun getLanguageName(extension: String): String {
        return when (extension.lowercase()) {
                "py" -> "Python"
                "js" -> "JavaScript"
                "ts" -> "TypeScript"
                "java" -> "Java"
                "kt", "kts" -> "Kotlin"
                "cpp", "c", "h", "hpp" -> "C/C++"
                "cs" -> "C#"
                "php" -> "PHP"
                "rb" -> "Ruby"
                "go" -> "Go"
                "rs" -> "Rust"
                "swift" -> "Swift"
                "html", "htm" -> "HTML"
                "css" -> "CSS"
                "scss", "sass" -> "Sass"
                "json" -> "JSON"
                "xml" -> "XML"
                "yaml", "yml" -> "YAML"
                "md", "markdown" -> "Markdown"
                "sql" -> "SQL"
                "sh", "bash" -> "Shell"
                else -> "Text"
        }
    }

    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp" -> "image/$extension"
            "mp4", "mkv", "avi" -> "video/$extension"
            "txt", "md", "json" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    // Вспомогательная функция для определения MIME-типа
    fun getMimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            else -> "application/octet-stream"
        }
    }

    fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return "${size / 1024} KB"
        return "${size / (1024 * 1024)} MB"
    }
}