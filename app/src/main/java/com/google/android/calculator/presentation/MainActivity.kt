package com.google.android.calculator.presentation

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
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.usecase.ClearCacheUseCase
import com.google.android.calculator.presentation.theme.VaultTheme
import com.google.android.calculator.presentation.AppNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

import com.google.android.calculator.R

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var clearCacheUseCase: ClearCacheUseCase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isVaultOpen = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d("MainActivity", "Screen OFF received")
                cleanupAndRemoveFromRecents()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        // 1. Запрет скриншотов и записи экрана (в т.ч. чёрная превьюшка в recents)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        
        // 2. Маскировка записи в списке задач (если вдруг приложение мелькнёт в recents)
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

        // Инициализация MapLibre
        MapLibre.getInstance(this)
    
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
    }

    private fun observeAppLifecycle() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                Log.d("MainActivity", "onStop called, isVaultOpen=$isVaultOpen")
                // ВСЕГДА удаляем из recents при сворачивании,
                // если не идёт файловая операция
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

    /**
     * Единая функция очистки: удаляет кэш если хранилище открыто,
     * и ВСЕГДА убирает приложение из списка недавних.
     */
    private fun cleanupAndRemoveFromRecents() {
        // Если файловая операция в процессе — не трогаем приложение
        if (appContainer.isFileOperationInProgress.value) {
            Log.d("MainActivity", "File operation in progress, keeping app alive")
            return
        }

        // Если хранилище было открыто — очищаем кэш
        if (isVaultOpen) {
            Log.d("MainActivity", "Vault was open, clearing cache")
            serviceScope.launch(Dispatchers.IO) {
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