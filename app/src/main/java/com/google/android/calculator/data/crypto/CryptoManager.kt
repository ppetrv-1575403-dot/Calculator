package com.google.android.calculator.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
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

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "vault_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}