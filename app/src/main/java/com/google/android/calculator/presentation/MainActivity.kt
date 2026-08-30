package com.google.android.calculator

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

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var clearCacheUseCase: ClearCacheUseCase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isVaultOpen = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d("MainActivity", "Screen OFF received")
                softExitToCalculator()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        appContainer = AppContainer(applicationContext)
        clearCacheUseCase = ClearCacheUseCase(applicationContext)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

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
                Log.d("MainActivity", "onStop called, isVaultOpen=$isVaultOpen, isFileOp=${appContainer.isFileOperationInProgress.value}")

                if (isVaultOpen) {
                    // Хранилище открыто — закрываем его
                    softExitToCalculator()
                } else {
                    // Хранилище закрыто — проверяем, идёт ли операция с файлами
                    if (!appContainer.isFileOperationInProgress.value) {
                        Log.d("MainActivity", "No file operation, finishing task")
                        finishAndRemoveTask()
                    } else {
                        Log.d("MainActivity", "File operation in progress, keeping app alive")
                    }
                }
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

    private fun softExitToCalculator() {
        if (!isVaultOpen) {
            Log.d("MainActivity", "Vault not open, skipping")
            return
        }

        Log.d("MainActivity", "Soft exit to calculator")
        serviceScope.launch(Dispatchers.IO) {
            clearCacheUseCase()
        }
        isVaultOpen = false
        appContainer.triggerNavigateToCalculator()
    }

    companion object {
        const val EXTRA_FORCE_CLOSE = "extra_force_close"
    }
}