package com.google.android.calculator.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest

enum class VaultMode { REAL, DECOY }

data class VaultSlot(val id: String, val label: String, val isReal: Boolean)

object VaultManager {
    const val REAL_ID = "real"

    private const val PREFS = "vault_prefs"
    private const val KEY_SLOTS = "decoy_slots"

    private const val LEGACY_MAIN = "main_pin_hash"
    private const val LEGACY_DECOY = "decoy_pin_hash"
    private const val LEGACY_ENABLED = "decoy_enabled"
    private const val LEGACY_SEEDED = "decoy_seeded"

    private fun keyPin(id: String) = "slot_${id}_pin"
    private fun keyLabel(id: String) = "slot_${id}_label"
    private fun keySeeded(id: String) = "slot_${id}_seeded"
    private fun keyContainerPath(id: String) = "slot_${id}_container_path"
    private fun keyContainerName(id: String) = "slot_${id}_container_name"
    private fun keyContainerEnabled(id: String) = "slot_${id}_container_enabled"

    private var prefs: SharedPreferences? = null

    var currentSlotId: String = REAL_ID
        private set

    var activeSessionPin: String? = null
        private set

    val currentMode: VaultMode
        get() = if (currentSlotId == REAL_ID) VaultMode.REAL else VaultMode.DECOY

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacy()
    }

    private fun migrateLegacy() {
        val p = prefs ?: return
        if (p.getString(keyPin(REAL_ID), null) == null && p.contains(LEGACY_MAIN)) {
            p.edit().putString(keyPin(REAL_ID), p.getString(LEGACY_MAIN, null))
                .remove(LEGACY_MAIN).apply()
        }
        if (p.getBoolean(LEGACY_ENABLED, false) && p.contains(LEGACY_DECOY) && p.getString(KEY_SLOTS, null) == null) {
            val id = "d1"
            p.edit()
                .putString(KEY_SLOTS, id)
                .putString(keyPin(id), p.getString(LEGACY_DECOY, null))
                .putString(keyLabel(id), "Фейковое 1")
                .putBoolean(keySeeded(id), p.getBoolean(LEGACY_SEEDED, false))
                .remove(LEGACY_DECOY).remove(LEGACY_ENABLED).remove(LEGACY_SEEDED)
                .apply()
        }
    }

    fun realSlot() = VaultSlot(REAL_ID, "Основное хранилище", true)

    fun decoySlots(): List<VaultSlot> {
        val ids = prefs?.getString(KEY_SLOTS, "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        return ids.map { VaultSlot(it, prefs?.getString(keyLabel(it), it) ?: it, false) }
    }

    fun slots(): List<VaultSlot> = listOf(realSlot()) + decoySlots()

    fun isDecoyEnabled(): Boolean = decoySlots().isNotEmpty()
    fun hasMainPin(): Boolean = prefs?.getString(keyPin(REAL_ID), null) != null

    fun setMainPin(pin: String) {
        prefs?.edit()?.putString(keyPin(REAL_ID), hash(pin))?.apply()
    }

    fun authenticateSlot(pin: String): VaultSlot? {
        val h = hash(pin)
        val slot = slots().firstOrNull { prefs?.getString(keyPin(it.id), null) == h }
        if (slot != null) {
            activeSessionPin = pin
        }
        return slot
    }

    // ⭐ Метод для явной установки PIN сессии (для миграции со старой системы)
    fun setSessionPin(pin: String) {
        activeSessionPin = pin
        FileLogger.i("Vault", "Session PIN set explicitly")
    }

    fun authenticate(pin: String): VaultMode? =
        authenticateSlot(pin)?.let { if (it.isReal) VaultMode.REAL else VaultMode.DECOY }

    fun enterSlot(id: String) {
        currentSlotId = id
        FileLogger.i("Vault", "Session slot: $id")
    }

    fun enterMode(mode: VaultMode) {
        enterSlot(if (mode == VaultMode.REAL) REAL_ID else decoySlots().firstOrNull()?.id ?: REAL_ID)
    }

    fun lock() {
        activeSessionPin = null
        currentSlotId = REAL_ID
        FileLogger.i("Vault", "Locked")
    }

    fun storageSuffix(): String = if (currentSlotId == REAL_ID) "" else "_$currentSlotId"

    fun createDecoySlot(pin: String, label: String): Result<String> {
        if (pin.length < 4) return Result.failure(Exception("PIN слишком короткий"))
        if (authenticateSlot(pin) != null) return Result.failure(Exception("Этот PIN уже используется"))
        val id = "d" + System.currentTimeMillis().toString().takeLast(8)
        val ids = (decoySlots().map { it.id } + id).joinToString(",")
        prefs?.edit()
            ?.putString(KEY_SLOTS, ids)
            ?.putString(keyPin(id), hash(pin))
            ?.putString(keyLabel(id), label)
            ?.apply()
        return Result.success(id)
    }

    fun deleteSlot(id: String, mainPin: String): Boolean {
        if (id == REAL_ID) return false
        if (authenticateSlot(mainPin)?.isReal != true) return false
        val ids = decoySlots().map { it.id }.filter { it != id }.joinToString(",")
        prefs?.edit()
            ?.putString(KEY_SLOTS, ids)
            ?.remove(keyPin(id))?.remove(keyLabel(id))?.remove(keySeeded(id))
            ?.apply()
        return true
    }

    fun changePin(slotId: String, oldPin: String, newPin: String, syncMain: ((String) -> Unit)? = null): Boolean {
        val slot = authenticateSlot(oldPin) ?: return false
        if (slot.id != slotId) return false
        if (newPin.length < 4) return false
        if (authenticateSlot(newPin) != null) return false
        prefs?.edit()?.putString(keyPin(slotId), hash(newPin))?.apply()
        if (slotId == REAL_ID) syncMain?.invoke(newPin)
        return true
    }

    fun isSeeded(id: String): Boolean = prefs?.getBoolean(keySeeded(id), false) == true
    fun markSeeded(id: String) { prefs?.edit()?.putBoolean(keySeeded(id), true)?.apply() }
    fun isDecoySeeded(): Boolean = isSeeded(currentSlotId)
    fun markDecoySeeded() = markSeeded(currentSlotId)

    // === Контейнер ===

    fun containerPath(id: String): String? = prefs?.getString(keyContainerPath(id), null)

    fun setContainerPath(id: String, path: String?) =
        prefs?.edit()?.apply {
            if (path == null) remove(keyContainerPath(id)) else putString(keyContainerPath(id), path)
        }?.apply()

    fun containerName(id: String): String =
        prefs?.getString(keyContainerName(id), null) ?: defaultContainerName(id)

    fun setContainerName(id: String, name: String) =
        prefs?.edit()?.putString(keyContainerName(id), name)?.apply()

    fun containerEnabled(id: String): Boolean =
        prefs?.getBoolean(keyContainerEnabled(id), false) == true

    fun setContainerEnabled(id: String, enabled: Boolean) =
        prefs?.edit()?.putBoolean(keyContainerEnabled(id), enabled)?.apply()

    fun containerFile(id: String): File? {
        val path = containerPath(id) ?: return null
        val name = containerName(id)
        return File(path, name)
    }

    fun isContainerValid(id: String): Boolean {
        val f = containerFile(id)
        return f != null && f.exists() && f.length() >= 32
    }

    private fun defaultContainerName(id: String): String =
        if (id == REAL_ID) "media_cache.bin" else "media_cache_$id.bin"

    // === Legacy API ===

    fun enableDecoy(pin: String) { createDecoySlot(pin, "Фейковое ${decoySlots().size + 1}") }

    fun changeMainPin(old: String, new: String, sync: (String) -> Unit): Boolean =
        changePin(REAL_ID, old, new, sync)

    fun changeDecoyPin(old: String, new: String): Boolean =
        authenticateSlot(old)?.let { if (it.isReal) false else changePin(it.id, old, new) } ?: false

    fun disableDecoy(mainPin: String): Boolean =
        decoySlots().firstOrNull()?.let { deleteSlot(it.id, mainPin) } ?: false

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray())
            .joinToString("") { "%02x".format(it) }
}