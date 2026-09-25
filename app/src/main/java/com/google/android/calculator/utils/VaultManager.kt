package com.google.android.calculator.utils

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

enum class VaultMode { REAL, DECOY }

/**
 * Управление двумя хранилищами: реальным и фейковым (duress vault).
 * Фейковое открывается отдельным PIN и содержит только несекретные данные.
 * Внешне оба режима неотличимы.
 */
object VaultManager {
    private const val PREFS = "vault_prefs"
    private const val KEY_MAIN_PIN = "main_pin_hash"
    private const val KEY_DECOY_PIN = "decoy_pin_hash"
    private const val KEY_DECOY_ENABLED = "decoy_enabled"
    private const val KEY_DECOY_SEEDED = "decoy_seeded"

    private var prefs: SharedPreferences? = null

    /** Режим текущего сеанса. Устанавливается при разблокировке. */
    var currentMode: VaultMode = VaultMode.REAL
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isDecoyEnabled(): Boolean = prefs?.getBoolean(KEY_DECOY_ENABLED, false) == true
    fun hasMainPin(): Boolean = prefs?.getString(KEY_MAIN_PIN, null) != null
    fun isDecoySeeded(): Boolean = prefs?.getBoolean(KEY_DECOY_SEEDED, false) == true
    fun markDecoySeeded() {
        prefs?.edit()?.putBoolean(KEY_DECOY_SEEDED, true)?.apply()
    }

    fun setMainPin(pin: String) {
        prefs?.edit()?.putString(KEY_MAIN_PIN, hash(pin))?.apply()
    }

    fun enableDecoy(pin: String) {
        prefs?.edit()
            ?.putString(KEY_DECOY_PIN, hash(pin))
            ?.putBoolean(KEY_DECOY_ENABLED, true)
            ?.apply()
    }

    /**
     * Меняет основной PIN.
     * @param syncCallback вызывается для синхронизации с PinRepository
     */
    fun changeMainPin(oldPin: String, newPin: String, syncCallback: (String) -> Unit): Boolean {
        if (authenticate(oldPin) != VaultMode.REAL) return false
        prefs?.edit()?.putString(KEY_MAIN_PIN, hash(newPin))?.apply()
        syncCallback(newPin)
        return true
    }

    /**
     * Меняет фейковый PIN.
     * @param syncCallback НЕ вызывается, т.к. фейковый PIN не в PinRepository
     */
    fun changeDecoyPin(oldPin: String, newPin: String): Boolean {
        if (authenticate(oldPin) != VaultMode.DECOY) return false
        prefs?.edit()?.putString(KEY_DECOY_PIN, hash(newPin))?.apply()
        return true
    }

    fun disableDecoy(mainPin: String): Boolean {
        if (authenticate(mainPin) != VaultMode.REAL) return false
        prefs?.edit()
            ?.remove(KEY_DECOY_PIN)
            ?.putBoolean(KEY_DECOY_ENABLED, false)
            ?.putBoolean(KEY_DECOY_SEEDED, false)
            ?.apply()
        return true
    }

    /**
     * Проверяет введённый PIN и возвращает режим хранилища.
     * null — PIN неверный.
     */
    fun authenticate(pin: String): VaultMode? {
        val h = hash(pin)
        return when {
            h == prefs?.getString(KEY_MAIN_PIN, null) -> VaultMode.REAL
            isDecoyEnabled() && h == prefs?.getString(KEY_DECOY_PIN, null) -> VaultMode.DECOY
            else -> null
        }
    }

    fun enterMode(mode: VaultMode) {
        currentMode = mode
        FileLogger.i("Vault", "Session mode: $mode")
    }

    /** Суффикс для имён БД/файлов текущего режима */
    fun storageSuffix(): String =
        if (currentMode == VaultMode.DECOY) "_decoy" else ""

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
            .joinToString("") { "%02x".format(it) }
}