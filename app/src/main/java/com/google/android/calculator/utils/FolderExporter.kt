package com.google.android.calculator.utils

import android.content.Context
import android.net.Uri
import com.google.android.calculator.domain.model.VaultFile
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.domain.repository.FolderRepository
import com.google.android.calculator.domain.repository.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Экспорт и импорт папок хранилища с сохранением структуры.
 * Формат ZIP: каждая папка — директория, файлы — с расшифрованным содержимым.
 */
object FolderExporter {

    /**
     * Экспорт папки со всем содержимым в ZIP-файл.
     * @return Uri созданного файла в Downloads
     */
    suspend fun exportFolder(
        context: Context,
        folder: VaultFolder,
        vaultRepository: VaultRepository,
        folderRepository: FolderRepository
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val zipFile = File(downloadsDir, "${folder.name}_${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                exportFolderRecursive(
                    folder = folder,
                    parentPath = "",
                    zos = zos,
                    vaultRepository = vaultRepository,
                    folderRepository = folderRepository
                )
            }

            Uri.fromFile(zipFile)
        } catch (e: Exception) {
            FileLogger.e("FolderExporter", "Export failed", e)
            null
        }
    }

    private suspend fun exportFolderRecursive(
        folder: VaultFolder,
        parentPath: String,
        zos: ZipOutputStream,
        vaultRepository: VaultRepository,
        folderRepository: FolderRepository
    ) {
        val currentPath = if (parentPath.isEmpty()) folder.name else "$parentPath/${folder.name}"

        zos.putNextEntry(ZipEntry("$currentPath/"))
        zos.closeEntry()

        val files = vaultRepository.getFiles().filter { it.folderId == folder.id }
        files.forEach { file ->
            exportFile(file, currentPath, zos, vaultRepository)
        }

        val subfolders = folderRepository.getFolders().filter { it.parentId == folder.id }
        subfolders.forEach { subfolder ->
            exportFolderRecursive(subfolder, currentPath, zos, vaultRepository, folderRepository)
        }
    }

    private suspend fun exportFile(
        file: VaultFile,
        folderPath: String,
        zos: ZipOutputStream,
        vaultRepository: VaultRepository
    ) {
        val tempFile = vaultRepository.decryptToTempFile(file.encryptedFileName)
        if (tempFile != null && tempFile.exists()) {
            try {
                val entryPath = "$folderPath/${file.originalName}"
                zos.putNextEntry(ZipEntry(entryPath))
                FileInputStream(tempFile).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Импорт ZIP-архива в хранилище с воссозданием структуры папок.
     * @param targetFolderId ID папки, куда импортировать (null = корень)
     * @return количество импортированных файлов
     */
    suspend fun importFolder(
        context: Context,
        zipUri: Uri,
        targetFolderId: String?,
        vaultRepository: VaultRepository,
        folderRepository: FolderRepository
    ): Int = withContext(Dispatchers.IO) {
        var importedCount = 0
        val folderIdMap = mutableMapOf<String, String>()

        folderIdMap[""] = targetFolderId ?: ""

        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val path = entry.name
                            val fileName = path.substringAfterLast('/')
                            val folderPath = path.substringBeforeLast('/', "")

                            val parentFolderId = findOrCreateFolder(
                                folderPath,
                                folderIdMap,
                                folderRepository,
                                targetFolderId
                            )

                            val tempFile = File(context.cacheDir, "import_temp_${System.nanoTime()}")
                            try {
                                FileOutputStream(tempFile).use { fos ->
                                    zis.copyTo(fos)
                                }

                                val mimeType = guessMimeType(fileName)
                                val result = vaultRepository.addFile(
                                    Uri.fromFile(tempFile),
                                    fileName,
                                    mimeType,
                                    parentFolderId.takeIf { it.isNotEmpty() }
                                )

                                if (result.isSuccess) {
                                    importedCount++
                                }
                            } finally {
                                tempFile.delete()
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e("FolderExporter", "Import failed", e)
        }

        importedCount
    }

    /**
     * Импорт обычной папки с диска (без ZIP) с воссозданием структуры подпапок.
     * @param sourcePath абсолютный путь к папке на устройстве
     * @param targetFolderId ID папки в хранилище, куда импортировать (null = корень)
     * @return количество импортированных файлов
     */
    suspend fun importFolderFromPath(
        sourcePath: String,
        targetFolderId: String?,
        vaultRepository: VaultRepository,
        folderRepository: FolderRepository
    ): Int = withContext(Dispatchers.IO) {
        var importedCount = 0
        val folderIdMap = mutableMapOf<String, String>()
        folderIdMap[""] = targetFolderId ?: ""

        val sourceDir = File(sourcePath)
        if (!sourceDir.exists() || !sourceDir.isDirectory) return@withContext 0

        val allFiles = mutableListOf<Pair<File, String>>()
        collectDiskFiles(sourceDir, "", allFiles)

        allFiles.forEach { (file, relativeDir) ->
            val parentFolderId = findOrCreateFolder(
                relativeDir,
                folderIdMap,
                folderRepository,
                targetFolderId
            )

            val mimeType = guessMimeType(file.name)
            val result = vaultRepository.addFile(
                Uri.fromFile(file),
                file.name,
                mimeType,
                parentFolderId.takeIf { it.isNotEmpty() }
            )
            if (result.isSuccess) importedCount++
        }

        importedCount
    }

    private fun collectDiskFiles(
        dir: File,
        relativePath: String,
        out: MutableList<Pair<File, String>>
    ) {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(".")) return@forEach
            if (f.isDirectory) {
                val newRel = if (relativePath.isEmpty()) f.name else "$relativePath/${f.name}"
                collectDiskFiles(f, newRel, out)
            } else {
                out.add(f to relativePath)
            }
        }
    }

    private suspend fun findOrCreateFolder(
        path: String,
        folderIdMap: MutableMap<String, String>,
        folderRepository: FolderRepository,
        rootFolderId: String?
    ): String {
        if (path.isEmpty()) {
            return rootFolderId ?: ""
        }

        folderIdMap[path]?.let { return it }

        val parts = path.split('/')
        var currentParentId = rootFolderId ?: ""
        var currentPath = ""

        parts.forEach { part ->
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

            folderIdMap[currentPath]?.let {
                currentParentId = it
                return@forEach
            }

            val result = folderRepository.addFolder(part, currentParentId.takeIf { it.isNotEmpty() })
            result.onSuccess {
                val folders = folderRepository.getFolders()
                val created = folders.find {
                    it.name == part && it.parentId == currentParentId.takeIf { it.isNotEmpty() }
                }
                if (created != null) {
                    folderIdMap[currentPath] = created.id
                    currentParentId = created.id
                }
            }
        }

        return currentParentId
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md" -> "text/plain"
            "py" -> "text/x-python"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}