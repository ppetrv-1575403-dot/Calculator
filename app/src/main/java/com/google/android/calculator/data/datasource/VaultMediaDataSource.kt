package com.google.android.calculator.data.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.android.calculator.data.container.VaultContainer
import com.google.android.calculator.utils.FileLogger
import java.io.IOException

class VaultMediaDataSource(
    private val container: VaultContainer
) : DataSource {
    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var entryId: String? = null
    private var currentOffset: Long = 0
    
    // ⭐ Внутренний буфер для уменьшения количества вызовов read()
    private var buffer: ByteArray? = null
    private var bufferOffset: Int = 0
    private var bufferLength: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.currentOffset = dataSpec.position
        this.buffer = null
        this.bufferOffset = 0
        this.bufferLength = 0
        
        this.entryId = dataSpec.uri.lastPathSegment 
            ?: throw IOException("Invalid URI: missing entryId")
        
        FileLogger.d("VaultMediaDataSource", "Opening entry: $entryId, position: ${dataSpec.position}, length: ${dataSpec.length}")
        
        val entry = container.findEntry(entryId!!) ?: throw IOException("Entry not found: $entryId")
        val decryptedSize = container.getDecryptedSize(entry)
        
        FileLogger.d("VaultMediaDataSource", "Entry: ${entry.originalName}, decryptedSize: $decryptedSize, encryptedLength: ${entry.length}, mode: ${entry.encryptionMode}")
        
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            maxOf(0L, decryptedSize - dataSpec.position)
        }
        
        opened = true
        FileLogger.d("VaultMediaDataSource", "Opened, bytesRemaining: $bytesRemaining")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) throw IllegalStateException("DataSource not opened")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val entry = container.findEntry(entryId!!) ?: throw IOException("Entry not found")
        
        val lengthToRead = minOf(readLength.toLong(), bytesRemaining).toInt()
        
        try {
            FileLogger.d("VaultMediaDataSource", "Read request: offset=$currentOffset, requested=$lengthToRead")
            
            val decryptedBytes = container.readDecryptedRangeSync(entry, currentOffset, lengthToRead)
            val bytesRead = decryptedBytes.size
            
            if (bytesRead > 0) {
                System.arraycopy(decryptedBytes, 0, buffer, offset, bytesRead)
                currentOffset += bytesRead
                bytesRemaining -= bytesRead
            }
            
            FileLogger.d("VaultMediaDataSource", "Read $bytesRead bytes, remaining: $bytesRemaining")
            
            return if (bytesRead == 0) C.RESULT_END_OF_INPUT else bytesRead
        } catch (e: Exception) {
            FileLogger.e("VaultMediaDataSource", "Read failed at offset $currentOffset", e)
            throw IOException("Failed to read at offset $currentOffset", e)
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        opened = false
        buffer = null
        FileLogger.d("VaultMediaDataSource", "Closed")
    }

    override fun addTransferListener(transferListener: TransferListener) {}
}