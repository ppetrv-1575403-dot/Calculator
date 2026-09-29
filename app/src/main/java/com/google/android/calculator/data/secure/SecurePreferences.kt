package com.google.android.calculator.data.secure

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.calculator.utils.OriginalFileAction

class SecurePreferences(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var pin: String?
        get() = prefs.getString(KEY_PIN, null)
        set(value) { prefs.edit().putString(KEY_PIN, value).apply() }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var defaultDirectoryUri: String?
        get() = prefs.getString(KEY_DIR_URI, null)
        set(value) = prefs.edit().putString(KEY_DIR_URI, value).apply()

    var originalFileAction: OriginalFileAction
        get() = try {
            OriginalFileAction.valueOf(
                prefs.getString(KEY_ORIGINAL_ACTION, null) ?: OriginalFileAction.KEEP.name
            )
        } catch (e: Exception) {
            OriginalFileAction.KEEP
        }
        set(value) { prefs.edit().putString(KEY_ORIGINAL_ACTION, value.name).apply() }

    companion object {
        private const val KEY_PIN = "vault_pin"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_DIR_URI = "default_dir_uri"
        private const val KEY_ORIGINAL_ACTION = "original_file_action"
    }
}