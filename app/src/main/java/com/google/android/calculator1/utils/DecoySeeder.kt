package com.google.android.calculator1.utils

import com.google.android.calculator1.domain.model.SavedLocation
import com.google.android.calculator1.domain.repository.LocationRepository
import java.util.UUID

object DecoySeeder {
    suspend fun seedLocationRepository(repo: LocationRepository) {
        if (VaultManager.isDecoySeeded()) return
        
        val demos = listOf(
            Triple("Дом", 52.034512, 29.210518),
            Triple("Работа", 52.040215, 29.187002),
            Triple("Спортзал", 52.028933, 29.223307),
            Triple("Кафе", 52.033128, 29.217654)
        )

        demos.forEach { (name, lat, lon) ->
            val existing = repo.getLocations().find { it.name == name }
            if (existing == null) {
                repo.addLocation(
                    SavedLocation(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        description = "",
                        latitude = lat,
                        longitude = lon,
                        photoId = null,
                        folderId = null
                    )
                )
            }
        }
        VaultManager.markDecoySeeded()
    }
}