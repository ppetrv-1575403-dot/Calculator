package com.google.android.calculator.data.crypto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION_GCM = "AES/GCM/NoPadding"
    private const val CIPHER_TRANSFORMATION_CTR = "AES/CTR/NoPadding"
    private const val ITERATIONS = 100_000
    private const val KEY_SIZE = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12  // Для GCM
    const val IV_SIZE_CTR = 16  // Для CTR (полный блок AES)
    private const val TAG_LENGTH_BIT = 128

    // ⭐ Пул Cipher объектов для параллельных запросов (потокобезопасный через ThreadLocal)
    private val cipherPool = ThreadLocal.withInitial {
        Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun generateIV(size: Int): ByteArray {
        val iv = ByteArray(size)
        SecureRandom().nextBytes(iv)
        return iv
    }

    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // === GCM методы (для файлов, документов, фото) ===

    fun encrypt(input: File, output: File, password: String) {
        val salt = generateSalt()
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        output.outputStream().use { outs ->
            outs.write(salt)
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { cipherOut ->
                input.inputStream().use { it.copyTo(cipherOut) }
            }
        }
    }

    fun decrypt(input: File, output: File, password: String) {
        input.inputStream().use { ins ->
            val salt = ByteArray(SALT_SIZE)
            if (ins.read(salt) != SALT_SIZE) throw IllegalStateException("Invalid file: no salt")
            
            val iv = ByteArray(IV_SIZE)
            if (ins.read(iv) != IV_SIZE) throw IllegalStateException("Invalid file: no IV")

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))

            CipherOutputStream(output.outputStream(), cipher).use { cipherOut ->
                ins.copyTo(cipherOut)
            }
        }
    }

    fun encryptStream(baseStream: OutputStream, password: String, salt: ByteArray): OutputStream {
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        baseStream.write(cipher.iv)
        return CipherOutputStream(baseStream, cipher)
    }

    fun decryptStream(baseStream: InputStream, password: String, salt: ByteArray): InputStream {
        val iv = ByteArray(IV_SIZE)
        var read = 0
        while (read < IV_SIZE) {
            val n = baseStream.read(iv, read, IV_SIZE - read)
            if (n < 0) throw IllegalStateException("Invalid encrypted stream: no IV")
            read += n
        }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return CipherInputStream(baseStream, cipher)
    }

    fun encryptBytes(bytes: ByteArray, output: File, password: String, salt: ByteArray) {
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.outputStream().use { outs ->
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { it.write(bytes) }
        }
    }

    fun decryptToBytes(bytes: ByteArray, password: String, salt: ByteArray): ByteArray {
        bytes.inputStream().use { ins ->
            val iv = ByteArray(IV_SIZE)
            if (ins.read(iv) != IV_SIZE) throw IllegalStateException("Invalid encrypted data")
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            return CipherInputStream(ins, cipher).use { it.readBytes() }
        }
    }

    // === CTR методы (для видео/аудио со случайным доступом) ===

    fun encryptCTR(input: File, output: File, password: String) {
        val salt = generateSalt()
        val key = deriveKey(password, salt)
        val iv = generateIV(IV_SIZE_CTR)
        
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

        output.outputStream().use { outs ->
            outs.write(salt)
            outs.write(iv)
            CipherOutputStream(outs, cipher).use { cipherOut ->
                input.inputStream().use { it.copyTo(cipherOut) }
            }
        }
    }

    fun decryptCTR(input: File, output: File, password: String) {
        input.inputStream().use { ins ->
            val salt = ByteArray(SALT_SIZE)
            if (ins.read(salt) != SALT_SIZE) throw IllegalStateException("Invalid file: no salt")
            
            val iv = ByteArray(IV_SIZE_CTR)
            if (ins.read(iv) != IV_SIZE_CTR) throw IllegalStateException("Invalid file: no IV")

            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

            CipherOutputStream(output.outputStream(), cipher).use { cipherOut ->
                ins.copyTo(cipherOut)
            }
        }
    }

    /**
     * Расшифровка диапазона байт из CTR-зашифрованных данных.
     * Формат данных: [SALT (16)] [IV (16)] [зашифрованный payload]
     */
    fun decryptCTRRange(encryptedData: ByteArray, password: String, offset: Long, length: Int): ByteArray {
        if (encryptedData.size < SALT_SIZE + IV_SIZE_CTR) {
            throw IllegalStateException("Invalid encrypted data: too small")
        }
        
        val salt = encryptedData.copyOfRange(0, SALT_SIZE)
        val iv = encryptedData.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE_CTR)
        val encryptedPayload = encryptedData.copyOfRange(SALT_SIZE + IV_SIZE_CTR, encryptedData.size)
        
        val key = deriveKey(password, salt)
        
        val blockSize = 16
        val startBlock = offset / blockSize
        val byteOffsetInBlock = (offset % blockSize).toInt()
        
        val blockIV = iv.copyOf()
        val blockNumber = startBlock
        for (i in 0 until 8) {
            blockIV[IV_SIZE_CTR - 1 - i] = ((blockNumber shr (i * 8)) and 0xFF).toByte()
        }
        
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(blockIV))
        
        val endPos = offset + length
        val startByteInPayload = (startBlock * blockSize).toInt()
        val endBlock = (endPos + blockSize - 1) / blockSize
        val endByteInPayload = (endBlock * blockSize).toInt()
        
        val chunkStart = startByteInPayload
        val chunkEnd = minOf(endByteInPayload, encryptedPayload.size)
        
        if (chunkStart >= encryptedPayload.size) {
            return ByteArray(0)
        }
        
        val chunkToDecrypt = encryptedPayload.copyOfRange(chunkStart, chunkEnd)
        val decrypted = cipher.doFinal(chunkToDecrypt)
        
        val resultStart = byteOffsetInBlock
        val resultEnd = minOf(resultStart + length, decrypted.size)
        
        if (resultStart >= decrypted.size) return ByteArray(0)
        return decrypted.copyOfRange(resultStart, resultEnd)
    }

    fun getCTRDecryptedSize(encryptedData: ByteArray): Long {
        return maxOf(0L, encryptedData.size.toLong() - SALT_SIZE - IV_SIZE_CTR)
    }

    // === Быстрые методы с кэшированным ключом ===

    fun encryptBytesWithKey(bytes: ByteArray, output: File, key: SecretKey, salt: ByteArray) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.outputStream().use { outs ->
            outs.write(salt)
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { it.write(bytes) }
        }
    }

    fun decryptToBytesWithKey(bytes: ByteArray, key: SecretKey): ByteArray {
        bytes.inputStream().use { ins ->
            ins.read(ByteArray(SALT_SIZE))
            val iv = ByteArray(IV_SIZE)
            if (ins.read(iv) != IV_SIZE) throw IllegalStateException("Invalid encrypted data")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            return CipherInputStream(ins, cipher).use { it.readBytes() }
        }
    }

    fun encryptStreamWithKey(baseStream: OutputStream, key: SecretKey, salt: ByteArray): OutputStream {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        baseStream.write(salt)
        baseStream.write(cipher.iv)
        return CipherOutputStream(baseStream, cipher)
    }

    fun decryptStreamWithKey(baseStream: InputStream, key: SecretKey): InputStream {
        baseStream.read(ByteArray(SALT_SIZE))
        val iv = ByteArray(IV_SIZE)
        var read = 0
        while (read < IV_SIZE) {
            val n = baseStream.read(iv, read, IV_SIZE - read)
            if (n < 0) throw IllegalStateException("Invalid encrypted stream: no IV")
            read += n
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return CipherInputStream(baseStream, cipher)
    }

    // === CTR методы с кэшированным ключом (для видео в контейнере) ===

    fun encryptCTRWithKey(input: File, output: File, key: SecretKey, salt: ByteArray) {
        val iv = generateIV(IV_SIZE_CTR)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

        output.outputStream().use { outs ->
            outs.write(salt)
            outs.write(iv)
            CipherOutputStream(outs, cipher).use { cipherOut ->
                input.inputStream().use { it.copyTo(cipherOut) }
            }
        }
    }

    fun decryptCTRWithKey(input: File, output: File, key: SecretKey) {
        input.inputStream().use { ins ->
            ins.read(ByteArray(SALT_SIZE))
            val iv = ByteArray(IV_SIZE_CTR)
            if (ins.read(iv) != IV_SIZE_CTR) throw IllegalStateException("Invalid file: no IV")

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION_CTR)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

            CipherOutputStream(output.outputStream(), cipher).use { cipherOut ->
                ins.copyTo(cipherOut)
            }
        }
    }

    /**
     *  Оптимизированная расшифровка диапазона байт из файла.
     * Использует пул Cipher и принимает IV как параметр (для кэширования).
     */
    fun decryptCTRRangeFromFile(
        file: File,
        key: SecretKey,
        entryOffset: Long,
        payloadOffset: Long,
        length: Int,
        iv: ByteArray
    ): ByteArray {
        if (length <= 0) return ByteArray(0)
        
        val blockSize = 16
        
        // Вычисляем, какие блоки нам нужны
        val startBlock = (payloadOffset / blockSize).toInt()
        val endOffset = payloadOffset + length - 1
        val endBlock = (endOffset / blockSize).toInt()
        val numBlocks = endBlock - startBlock + 1
        
        // Читаем все нужные блоки из файла (выровнено по блокам)
        val alignedPayloadOffset = startBlock * blockSize
        val startFileOffset = entryOffset + SALT_SIZE + IV_SIZE_CTR + alignedPayloadOffset
        val bytesToRead = numBlocks * blockSize
        val encryptedData = ByteArray(bytesToRead)
        
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startFileOffset)
            val actualRead = raf.read(encryptedData)
            if (actualRead < bytesToRead) {
                return ByteArray(0)
            }
        }
        
        // ⭐ Извлекаем начальный counter из IV (последние 4 байта, big-endian)
        val initialCounter = ((iv[12].toInt() and 0xFF) shl 24) or
                             ((iv[13].toInt() and 0xFF) shl 16) or
                             ((iv[14].toInt() and 0xFF) shl 8) or
                             (iv[15].toInt() and 0xFF)
        
        // ⭐ Создаём IV для startBlock: initialCounter + startBlock
        val blockIV = iv.copyOf()
        val startCounter = initialCounter + startBlock
        blockIV[12] = ((startCounter shr 24) and 0xFF).toByte()
        blockIV[13] = ((startCounter shr 16) and 0xFF).toByte()
        blockIV[14] = ((startCounter shr 8) and 0xFF).toByte()
        blockIV[15] = (startCounter and 0xFF).toByte()
        
        // ⭐ Используем Cipher из пула (потокобезопасно)
        val cipher = cipherPool.get()
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(blockIV))
        
        // Один вызов doFinal для всего массива — быстро!
        val decryptedData = cipher.doFinal(encryptedData)
        
        // Возвращаем нужную часть
        val startByte = (payloadOffset % blockSize).toInt()
        return decryptedData.copyOfRange(startByte, startByte + length)
    }
}