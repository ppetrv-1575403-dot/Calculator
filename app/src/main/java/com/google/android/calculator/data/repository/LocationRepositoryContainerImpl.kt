package com.google.android.calculator.data.repository

import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.domain.repository.LocationRepository
import com.google.android.calculator.utils.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LocationRepository поверх VaultContainer.
 * Метаданные точек хранятся в записи "__meta_locations" внутри контейнера.
 */
class LocationRepositoryContainerImpl(
    private val container: VaultContainer
) : LocationRepository {

    private val mutex = Mutex()
    private val META_KEY = "locations"

    private suspend fun load(): MutableList<SavedLocation> {
        return try {
            container.getMetaLines(META_KEY).mapNotNull { line ->
                val p = line.split("|||")
                if (p.size >= 6) SavedLocation(
                    p[0], p[1], p[2],
                    p[3].toDoubleOrNull() ?: 0.0,
                    p[4].toDoubleOrNull() ?: 0.0,
                    p[5].takeIf { it.isNotEmpty() },
                    if (p.size >= 7) p[6].takeIf { it.isNotEmpty() } else null
                ) else null
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private suspend fun save(list: List<SavedLocation>) {
        container.putMetaLines(META_KEY, list.map {
            "${it.id}|||${it.name}|||${it.description.replace("\n", " ")}|||${it.latitude}|||${it.longitude}|||${it.photoId ?: ""}|||${it.folderId ?: ""}"
        })
    }

    override suspend fun getLocations(): List<SavedLocation> = mutex.withLock { load() }

    override suspend fun addLocation(location: SavedLocation) = mutex.withLock {
        try {
            val l = load()
            l.add(location)
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("LocationContainerRepo", "addLocation failed", e)
            Result.failure(e)
        }
    }

    override suspend fun updateLocation(location: SavedLocation) = mutex.withLock {
        try {
            val list = load()
            val index = list.indexOfFirst { it.id == location.id }
            if (index >= 0) list[index] = location
            save(list)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("LocationContainerRepo", "updateLocation failed", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteLocation(id: String) = mutex.withLock {
        try {
            val l = load()
            l.removeAll { it.id == id }
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("LocationContainerRepo", "deleteLocation failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getLocationById(id: String): SavedLocation? = mutex.withLock {
        load().find { it.id == id }
    }

    override suspend fun moveLocationsToFolder(fromFolderId: String, toFolderId: String?) = mutex.withLock {
        try {
            val l = load()
            l.forEachIndexed { i, s -> if (s.folderId == fromFolderId) l[i] = s.copy(folderId = toFolderId) }
            save(l)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("LocationContainerRepo", "moveLocationsToFolder failed", e)
            Result.failure(e)
        }
    }

    override suspend fun moveLocationToFolder(locationId: String, targetFolderId: String?) = mutex.withLock {
        try {
            val list = load()
            val index = list.indexOfFirst { it.id == locationId }
            if (index < 0) return@withLock Result.failure<Unit>(Exception("Location not found"))
            list[index] = list[index].copy(folderId = targetFolderId)
            save(list)
            Result.success(Unit)
        } catch (e: Exception) {
            FileLogger.e("LocationContainerRepo", "moveLocationToFolder failed", e)
            Result.failure(e)
        }
    }
}