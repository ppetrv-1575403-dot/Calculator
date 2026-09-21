package com.google.android.calculator.data.repository

import android.content.Context
import com.google.android.calculator.data.secure.EncryptedMetaStore
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.domain.repository.LocationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocationRepositoryImpl(context: Context) : LocationRepository {

    private val metaStore = EncryptedMetaStore(
        context,
        fileName = "locations_meta.enc",
        legacyPlaintextFileName = "locations_meta.txt"
    )
    private val mutex = Mutex()

    private fun load(): MutableList<SavedLocation> {
        return try {
            metaStore.readLines().mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 6) SavedLocation(
                    p[0], p[1], p[2],
                    p[3].toDoubleOrNull() ?: 0.0,
                    p[4].toDoubleOrNull() ?: 0.0,
                    p[5].takeIf { it.isNotEmpty() },
                    if (p.size >= 7) p[6].takeIf { it.isNotEmpty() } else null
                ) else null
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(list: List<SavedLocation>) {
        metaStore.writeLines(list.map {
            "${it.id}|||${it.name}|||${it.description.replace("\n", " ")}|||${it.latitude}|||${it.longitude}|||${it.photoId ?: ""}|||${it.folderId ?: ""}"
        })
    }

    override suspend fun getLocations() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    override suspend fun addLocation(location: SavedLocation) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val l = load()
                l.add(location)
                save(l)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun updateLocation(location: SavedLocation) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val list = load()
                val index = list.indexOfFirst { it.id == location.id }
                if (index >= 0) list[index] = location
                save(list)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun deleteLocation(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val l = load()
                l.removeAll { it.id == id }
                save(l)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun getLocationById(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { load().find { it.id == id } }
    }

    override suspend fun moveLocationsToFolder(fromFolderId: String, toFolderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val l = load()
                l.forEachIndexed { i, s -> if (s.folderId == fromFolderId) l[i] = s.copy(folderId = toFolderId) }
                save(l)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    override suspend fun moveLocationToFolder(locationId: String, targetFolderId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val list = load()
                val index = list.indexOfFirst { it.id == locationId }
                if (index < 0) return@withLock Result.failure<Unit>(Exception("Location not found"))
                list[index] = list[index].copy(folderId = targetFolderId)
                save(list)
                Result.success(Unit)
            } catch (e: Exception) { Result.failure(e) }
        }
    }
}