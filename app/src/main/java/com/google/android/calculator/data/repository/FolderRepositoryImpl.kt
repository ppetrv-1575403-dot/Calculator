// data/repository/FolderRepositoryImpl.kt
package com.google.android.calculator.data.repository
import android.content.Context
import com.google.android.calculator.domain.model.VaultFolder
import com.google.android.calculator.domain.repository.FolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class FolderRepositoryImpl(context: Context) : FolderRepository {
    private val metaFile = File(context.filesDir, "folders_meta.txt")
    private val mutex = Mutex()

    private fun load(): MutableList<VaultFolder> {
        try {
            if (!metaFile.exists()) return mutableListOf()
            return metaFile.readLines().mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 2) VaultFolder(p[0], p[1], if (p.size >= 3) p[2].takeIf { it.isNotEmpty() } else null) else null
            }.toMutableList()
        } catch (e: Exception) { return mutableListOf() }
    }

    private fun save(list: List<VaultFolder>) {
        metaFile.writeText(list.joinToString("\n") { "${it.id}|||${it.name}|||${it.parentId ?: ""}" })
    }

    override suspend fun getFolders() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    override suspend fun addFolder(name: String, parentId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock { try { val l = load(); l.add(VaultFolder(System.currentTimeMillis().toString(), name, parentId)); save(l); Result.success(Unit) } catch (e: Exception) { Result.failure(e) } }
    }

    override suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { try { val l = load(); l.removeAll { it.id == id }; save(l); Result.success(Unit) } catch (e: Exception) { Result.failure(e) } }
    }

    override suspend fun reparentSubfolders(oldParentId: String, newParentId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val l = load()
                l.forEachIndexed { i, f -> if (f.parentId == oldParentId) l[i] = f.copy(parentId = newParentId) }
                save(l); Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }
}