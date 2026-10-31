package com.google.android.calculator1.presentation

import android.app.Application
import com.google.android.calculator1.BuildConfig
import com.google.android.calculator1.utils.CrashHandler
import com.google.android.calculator1.utils.FileLogger

class MyApplication : Application() {

    companion object {
        var app: MyApplication? = null
    }
    
    lateinit var appContainer: com.google.android.calculator1.di.AppContainer
        private set
    
    override fun onCreate() {
        super.onCreate()
        app = this
        // Файловые логи и запись крашей на диск — только в debug-сборке.
        // В релизе ничего не пишем на диск (в т.ч. на внешнее хранилище).
        if (BuildConfig.DEBUG) {
            FileLogger.init(this)
            CrashHandler.init(this)
        }
        
        appContainer = com.google.android.calculator1.di.AppContainer(this)
    }
}