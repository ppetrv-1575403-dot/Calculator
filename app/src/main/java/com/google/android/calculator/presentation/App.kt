package com.google.android.calculator.presentation

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                
                val logFile = File(dir, "crash.log")
                PrintWriter(FileWriter(logFile, true)).use { pw ->
                    pw.println("=== CRASH ${Date()} ===")
                    pw.println("Thread: ${thread.name}")
                    throwable.printStackTrace(pw)
                }
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }
            
            try {
                Thread.sleep(1000)
            } catch (ignored: Exception) {
            }
            
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
}