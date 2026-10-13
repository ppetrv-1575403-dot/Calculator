package com.google.android.calculator.data.crypto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATIONS = 100_000
    private const val KEY_SIZE = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_LENGTH_BIT = 128

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // === Методы для отдельных файлов (соль внутри файла) ===
    fun encrypt(input: File, output: File, password: String) {
        val salt = generateSalt()
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
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
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))

            CipherOutputStream(output.outputStream(), cipher).use { cipherOut ->
                ins.copyTo(cipherOut)
            }
        }
    }

    // === Методы для контейнера (с внешним управлением солью) ===

    fun encryptBytes(bytes: ByteArray, output: File, password: String, salt: ByteArray) {
        val key = deriveKey(password, salt)
        encryptBytesWithKey(bytes, output, key, salt)
    }

    fun decryptToBytes(bytes: ByteArray, password: String, salt: ByteArray): ByteArray {
        val key = deriveKey(password, salt)
        return decryptToBytesWithKey(bytes, key)
    }

    fun encryptStream(baseStream: OutputStream, password: String, salt: ByteArray): OutputStream {
        val key = deriveKey(password, salt)
        return encryptStreamWithKey(baseStream, key, salt)
    }

    fun decryptStream(baseStream: InputStream, password: String, salt: ByteArray): InputStream {
        val key = deriveKey(password, salt)
        return decryptStreamWithKey(baseStream, key)
    }

    // ⭐ БЫСТРЫЕ МЕТОДЫ С ИСПОЛЬЗОВАНИЕМ КЭШИРОВАННОГО КЛЮЧА
    fun encryptBytesWithKey(bytes: ByteArray, output: File, key: SecretKey, salt: ByteArray) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.outputStream().use { outs ->
            outs.write(salt)
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { it.write(bytes) }
        }
    }

    fun decryptToBytesWithKey(bytes: ByteArray, key: SecretKey): ByteArray {
        bytes.inputStream().use { ins ->
            ins.read(ByteArray(SALT_SIZE)) // Пропускаем соль (ключ уже известен)
            val iv = ByteArray(IV_SIZE)
            if (ins.read(iv) != IV_SIZE) throw IllegalStateException("Invalid encrypted data")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
            return CipherInputStream(ins, cipher).use { it.readBytes() }
        }
    }

    fun encryptStreamWithKey(baseStream: OutputStream, key: SecretKey, salt: ByteArray): OutputStream {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        baseStream.write(salt)
        baseStream.write(cipher.iv)
        return CipherOutputStream(baseStream, cipher)
    }

    fun decryptStreamWithKey(baseStream: InputStream, key: SecretKey): InputStream {
        baseStream.read(ByteArray(SALT_SIZE)) // Пропускаем соль
        val iv = ByteArray(IV_SIZE)
        var read = 0
        while (read < IV_SIZE) {
            val n = baseStream.read(iv, read, IV_SIZE - read)
            if (n < 0) throw IllegalStateException("Invalid encrypted stream: no IV")
            read += n
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        return CipherInputStream(baseStream, cipher)
    }
}