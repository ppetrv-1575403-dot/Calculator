package com.google.android.calculator.presentation

import android.app.Application
import com.google.android.calculator.utils.CrashHandler
import com.google.android.calculator.utils.FileLogger

class MyApplication : Application() {

    companion object {
        var app: MyApplication? = null
    }
    
    lateinit var appContainer: com.google.android.calculator.di.AppContainer
        private set
    
    override fun onCreate() {
        super.onCreate()
        app = this
        // Инициализируем логгер и обработчик крашей
        FileLogger.init(this)
        CrashHandler.init(this)
        
        appContainer = com.google.android.calculator.di.AppContainer(this)
    }
}