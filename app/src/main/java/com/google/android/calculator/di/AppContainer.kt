package com.google.android.calculator.di

import android.content.Context
import com.google.android.calculator.data.crypto.CryptoManager
import com.google.android.calculator.data.repository.PinRepositoryImpl
import com.google.android.calculator.data.repository.VaultRepositoryImpl
import com.google.android.calculator.data.secure.SecurePreferences
import com.google.android.calculator.domain.repository.PinRepository
import com.google.android.calculator.domain.repository.VaultRepository
import com.google.android.calculator.domain.usecase.*

class AppContainer(context: Context) {
    private val securePreferences = SecurePreferences(context.applicationContext)
    private val cryptoManager = CryptoManager(context.applicationContext)

    val pinRepository: PinRepository = PinRepositoryImpl(securePreferences)
    val vaultRepository: VaultRepository = VaultRepositoryImpl(context.applicationContext, cryptoManager)

    val checkPinUseCase = CheckPinUseCase(pinRepository)
    val savePinUseCase = SavePinUseCase(pinRepository)
    val isFirstLaunchUseCase = IsFirstLaunchUseCase(pinRepository)
    val addFileUseCase = AddFileUseCase(vaultRepository)
    val getFilesUseCase = GetFilesUseCase(vaultRepository)
}