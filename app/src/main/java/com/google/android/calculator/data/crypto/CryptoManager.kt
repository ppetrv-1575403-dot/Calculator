package com.google.android.calculator.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager(private val context: Context) {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private val key: SecretKey
        get() {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateKey()
            }
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

    private fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    // === File → File (оригинал) ===

    fun encrypt(input: File, output: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.outputStream().use { outs ->
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { cipherOut ->
                input.inputStream().use { it.copyTo(cipherOut) }
            }
        }
    }

    fun decrypt(input: File, output: File) {
        input.inputStream().use { ins ->
            val iv = ByteArray(12)
            if (ins.read(iv) != 12) throw IllegalStateException("Invalid encrypted file")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            CipherOutputStream(output.outputStream(), cipher).use { cipherOut ->
                ins.copyTo(cipherOut)
            }
        }
    }

    // === Stream-API (для RAM-only чтения) ===

    /**
     * Возвращает OutputStream, который шифрует всё записываемое и пишет в baseStream.
     * Первым байтом запишет 12-байтовый IV, поэтому baseStream должен быть доступен
     * для начала записи. Обязательно close() по окончании (иначе GCM-тег не запишется).
     */
    fun encryptStream(baseStream: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        baseStream.write(cipher.iv)
        return CipherOutputStream(baseStream, cipher)
    }

    /**
     * Возвращает InputStream, который читает шифротекст из baseStream и отдаёт plaintext.
     * Читает IV первым делом. Обязательно close() по окончании.
     */
    fun decryptStream(baseStream: InputStream): InputStream {
        val iv = ByteArray(12)
        var read = 0
        while (read < 12) {
            val n = baseStream.read(iv, read, 12 - read)
            if (n < 0) throw IllegalStateException("Invalid encrypted stream: no IV")
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return CipherInputStream(baseStream, cipher)
    }

    /**
     * Расшифровать поток напрямую в ByteArray (всё в ОЗУ, ноль байтов на диске).
     * Подходит для файлов ≤ ~20 МБ.
     */
    fun decryptToBytes(input: File): ByteArray {
        input.inputStream().use { ins ->
            val iv = ByteArray(12)
            if (ins.read(iv) != 12) throw IllegalStateException("Invalid encrypted file")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return CipherInputStream(ins, cipher).use { it.readBytes() }
        }
    }

    /**
     * Расшифровать поток в строку (для текстовых файлов, JSON-метаданных и т.п.).
     */
    fun decryptToString(input: File): String = String(decryptToBytes(input), Charsets.UTF_8)

    /**
     * Зашифровать ByteArray в файл (без промежуточного plaintext-файла).
     */
    fun encryptBytes(bytes: ByteArray, output: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.outputStream().use { outs ->
            outs.write(cipher.iv)
            CipherOutputStream(outs, cipher).use { cipherOut ->
                cipherOut.write(bytes)
            }
        }
    }

    /**
     * Зашифровать строку в файл.
     */
    fun encryptString(text: String, output: File) =
        encryptBytes(text.toByteArray(Charsets.UTF_8), output)

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "vault_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}