// FolderRepository.kt
package com.google.android.calculator.domain.repository
import com.google.android.calculator.domain.model.VaultFolder
interface FolderRepository {
    suspend fun getFolders(): List<VaultFolder>
    suspend fun addFolder(name: String, parentId: String?): Result<Unit>
    suspend fun deleteFolder(id: String): Result<Unit>
    suspend fun reparentSubfolders(oldParentId: String, newParentId: String?): Result<Unit>
}