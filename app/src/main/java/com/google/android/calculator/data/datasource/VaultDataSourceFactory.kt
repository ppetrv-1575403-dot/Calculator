package com.google.android.calculator.data.datasource

import androidx.media3.datasource.DataSource

class VaultDataSourceFactory(
    private val container: com.google.android.calculator.data.container.VaultContainer
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return VaultMediaDataSource(container)
    }
}