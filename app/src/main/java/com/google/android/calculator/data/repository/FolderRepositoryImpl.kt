package com.google.android.calculator.data.repository

import android.content.Context
import com.google.android.calculator.data.secure.EncryptedMetaStore
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.domain.repository.FolderRepository
import com.google.android.calculator.utils.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.android.calculator.utils.FileLogger

class FolderRepositoryImpl(
    context: Context,
    fileName: String = "folders_meta.enc",
    legacyFileName: String = "folders_meta.txt"
) : FolderRepository {
    
    private val metaStore = EncryptedMetaStore(
        context,
        fileName = fileName,
        legacyPlaintextFileName = legacyFileName
    )
    private val mutex = Mutex()

    private fun getPassword(): String {
        return VaultManager.activeSessionPin 
            ?: throw IllegalStateException("Хранилище заблокировано. Введите PIN.")
    }

    private fun load(): MutableList<VaultFolder> {
        return try {
            metaStore.readLines(getPassword()).mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 2) VaultFolder(p[0], p[1], if (p.size >= 3) p[2].takeIf { it.isNotEmpty() } else null) else null
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(list: List<VaultFolder>) {
        metaStore.writeLines(list.map { "${it.id}|||${it.name}|||${it.parentId ?: ""}" }, getPassword())
    }

    override suspend fun getFolders() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    override suspend fun addFolder(name: String, parentId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock { 
            try { 
                val l = load()
                l.add(VaultFolder(System.currentTimeMillis().toString(), name, parentId))
                save(l)
                Result.success(Unit) 
            } catch (e: Exception) { Result.failure(e) } 
        }
    }

    override suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { 
            try { 
                val l = load()
                l.removeAll { it.id == id }
                save(l)
                Result.success(Unit) 
            } catch (e: Exception) { Result.failure(e) } 
        }
    }

    override suspend fun reparentSubfolders(oldParentId: String, newParentId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val l = load()
                l.forEachIndexed { i, f -> if (f.parentId == oldParentId) l[i] = f.copy(parentId = newParentId) }
                save(l)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun renameFolder(id: String, newName: String): Result<Unit> {
        return try {
            val folders = getFolders().toMutableList()
            val index = folders.indexOfFirst { it.id == id }
            if (index >= 0) {
                folders[index] = folders[index].copy(name = newName)
                save(folders)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("FolderRepositoryImpl", "renameFolder failed", e)
            Result.failure(e)
        }
    }
}