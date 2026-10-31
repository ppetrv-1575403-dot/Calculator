package com.google.android.calculator1.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.calculator1.di.AppContainer
import com.google.android.calculator1.domain.usecase.ClearCacheUseCase
import com.google.android.calculator1.presentation.theme.VaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.maplibre.android.MapLibre

import com.google.android.calculator1.R
import com.google.android.calculator1.BuildConfig
import com.google.android.calculator1.presentation.MyApplication
import com.google.android.calculator1.utils.ClipboardManager
import com.google.android.calculator1.utils.FileLogger
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var clearCacheUseCase: ClearCacheUseCase
    private var isVaultOpen = false
    private var lifecycleObserverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d("MainActivity", "Screen OFF received")
                // При погасании экрана приложение ещё на переднем плане — проверяем буфер
                ClipboardManager.checkAndClearIfGeoData(this@MainActivity)
                cleanupAndRemoveFromRecents()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        val name = getString(R.string.app_name)
        setTaskDescription(
            android.app.ActivityManager.TaskDescription(name)
        )

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        appContainer = MyApplication.app?.appContainer as AppContainer
        clearCacheUseCase = ClearCacheUseCase(applicationContext)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

        MapLibre.getInstance(this)

        // ⭐ ГАРАНТИРОВАННО сбрасываем флаги окна при каждом запуске Activity
        WindowCompat.setDecorFitsSystemWindows(window, true)

        resetWindowFlags()
        
        setContent {
            VaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigator(appContainer, this)
                }
            }
        }

        observeAppLifecycle()
    }

    // ⭐ ГАРАНТИРОВАННЫЙ СБРОС ПРИ КАЖДОМ ВОЗВРАТЕ В ПРИЛОЖЕНИЕ
    // Это сработает, когда пользователь возвращается с PreviewScreen
    override fun onResume() {
        super.onResume()
        resetWindowFlags()
        FileLogger.d("MainActivity", "Window flags reset on resume")
    }
    
    private fun resetWindowFlags() {
        val window = this.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        
        // 1. Показываем все системные бары
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        
        // 2. Возвращаем системе управление отступами
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // 3. Сбрасываем поведение (убираем transient mode)
        insetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }

    /**
     * ВАЖНО: onPause() Activity вызывается ПОКА приложение ещё на переднем плане.
     * Это единственное место, где можно прочитать буфер обмена в новых версиях Android.
     */
    override fun onPause() {
        super.onPause()
        FileLogger.d("MainActivity", "Activity.onPause — проверяем буфер обмена")
        ClipboardManager.checkAndClearIfGeoData(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
    }

    private fun observeAppLifecycle() {
        if (lifecycleObserverRegistered) return
        lifecycleObserverRegistered = true

        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                FileLogger.d("MainActivity", "ProcessLifecycle onPause, isVaultOpen=$isVaultOpen")
            }

            override fun onStop(owner: LifecycleOwner) {
                FileLogger.d("MainActivity", "ProcessLifecycle onStop, isVaultOpen=$isVaultOpen")
                cleanupAndRemoveFromRecents()
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    fun markVaultAsOpen() {
        Log.d("MainActivity", "markVaultAsOpen")
        isVaultOpen = true
    }

    fun markVaultAsClosed() {
        Log.d("MainActivity", "markVaultAsClosed")
        isVaultOpen = false
    }

    private fun cleanupAndRemoveFromRecents() {
        if (appContainer.isFileOperationInProgress.value) {
            Log.d("MainActivity", "File operation in progress, keeping app alive")
            return
        }

        if (isVaultOpen) {
            Log.d("MainActivity", "Vault was open, clearing cache")
            runBlocking(Dispatchers.IO) {
                clearCacheUseCase()
            }
        }

        isVaultOpen = false
        Log.d("MainActivity", "Removing from recents")
        finishAndRemoveTask()
    }

    companion object {
        const val EXTRA_FORCE_CLOSE = "extra_force_close"
    }
}