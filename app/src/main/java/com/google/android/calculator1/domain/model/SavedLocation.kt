package com.google.android.calculator1.domain.model

data class SavedLocation(
    val id: String,
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val photoId: String?,
    val folderId: String? = null
)