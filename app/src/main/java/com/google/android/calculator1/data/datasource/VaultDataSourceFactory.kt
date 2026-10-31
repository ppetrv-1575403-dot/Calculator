package com.google.android.calculator1.data.datasource

import androidx.media3.datasource.DataSource

class VaultDataSourceFactory(
    private val container: com.google.android.calculator1.data.container.VaultContainer
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return VaultMediaDataSource(container)
    }
}