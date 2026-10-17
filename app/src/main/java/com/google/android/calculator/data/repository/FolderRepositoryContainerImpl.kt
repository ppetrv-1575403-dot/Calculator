package com.google.android.calculator.data.repository

import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.domain.repository.FolderRepository
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FolderRepositoryContainerImpl(
    private val container: VaultContainer
) : FolderRepository {

    private val mutex = Mutex()
    private val META_KEY = "folders"

    private suspend fun load(): MutableList<VaultFolder> {
        return try {
            container.getMetaLines(META_KEY).mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 2) VaultFolder(
                    p[0],
                    p[1],
                    if (p.size >= 3) p[2].takeIf { it.isNotEmpty() } else null
                ) else null
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private suspend fun save(list: List<VaultFolder>) {
        container.putMetaLines(META_KEY, list.map { "${it.id}|||${it.name}|||${it.parentId ?: ""}" })
    }

    override suspend fun getFolders(): List<VaultFolder> = mutex.withLock { load() }

    override suspend fun addFolder(name: String, parentId: String?) = mutex.withLock {
        try {
            val l = load()
            l.add(VaultFolder(System.currentTimeMillis().toString(), name, parentId))
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("FolderContainerRepo", "addFolder failed", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteFolder(id: String) = mutex.withLock {
        try {
            val l = load()
            l.removeAll { it.id == id }
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("FolderContainerRepo", "deleteFolder failed", e)
            Result.failure(e)
        }
    }

    override suspend fun reparentSubfolders(oldParentId: String, newParentId: String?) = mutex.withLock {
        try {
            val l = load()
            l.forEachIndexed { i, f -> if (f.parentId == oldParentId) l[i] = f.copy(parentId = newParentId) }
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("FolderContainerRepo", "reparentSubfolders failed", e)
            Result.failure(e)
        }
    }

    override suspend fun renameFolder(id: String, newName: String) = mutex.withLock {
        try {
            val l = load()
            val index = l.indexOfFirst { it.id == id }
            if (index >= 0) {
                l[index] = l[index].copy(name = newName)
                save(l)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("FolderContainerRepo", "renameFolder failed", e)
            Result.failure(e)
        }
    }
}