package com.google.android.calculator.domain.repository

interface PinRepository {
    fun savePin(pin: String)
    fun getPin(): String?
    fun isFirstLaunch(): Boolean
    fun setFirstLaunchDone()
}