package com.google.android.calculator.data.repository

import com.google.android.calculator.data.secure.SecurePreferences
import com.google.android.calculator.domain.repository.PinRepository

class PinRepositoryImpl(private val securePreferences: SecurePreferences) : PinRepository {
    override fun savePin(pin: String) { securePreferences.pin = pin }
    override fun getPin(): String? = securePreferences.pin
    override fun isFirstLaunch(): Boolean = securePreferences.isFirstLaunch
    override fun setFirstLaunchDone() { securePreferences.isFirstLaunch = false }
}