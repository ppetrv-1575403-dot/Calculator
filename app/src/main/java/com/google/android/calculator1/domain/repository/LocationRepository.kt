package com.google.android.calculator1.domain.repository

import com.google.android.calculator1.domain.model.SavedLocation

interface LocationRepository {
    suspend fun getLocations(): List<SavedLocation>
    suspend fun addLocation(location: SavedLocation): Result<Unit>
    suspend fun updateLocation(location: SavedLocation): Result<Unit>
    suspend fun deleteLocation(id: String): Result<Unit>
    suspend fun getLocationById(id: String): SavedLocation?
    suspend fun moveLocationsToFolder(fromFolderId: String, toFolderId: String?): Result<Unit>
    suspend fun moveLocationToFolder(locationId: String, targetFolderId: String?): Result<Unit>
}