package com.google.android.calculator.presentation

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.calculator.presentation.overlay.FloatingButtonController
import com.google.android.calculator.presentation.overlay.OverlayService
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.usecase.ClearCacheUseCase
import com.google.android.calculator.presentation.theme.VaultTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer
    private lateinit var clearCacheUseCase: ClearCacheUseCase
    private lateinit var keyguardManager: KeyguardManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // Проверяем, заблокирован ли экран при его включении
                    if (keyguardManager.isDeviceLocked) {
                        forceCloseApp()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // Пользователь разблокировал устройство — ничего не делаем
                }
            }        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = AppContainer(applicationContext)
        clearCacheUseCase = ClearCacheUseCase(applicationContext)
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (intent?.getBooleanExtra(EXTRA_FORCE_CLOSE, false) == true) {
            forceCloseApp()
            return
        }

        //requestOverlayPermissionIfNeeded()

        setContent {
            VaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigator(appContainer)
                }
            }
        }

        //observeAppLifecycle()
        //registerKeyguardListener()
    }

    override fun onResume() {
        super.onResume()
        // Регистрируем receiver для отслеживания включения экрана
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_FORCE_CLOSE, false) == true) {
            forceCloseApp()
        }
    }

    private fun observeAppLifecycle() {
        val overlayController = FloatingButtonController(this)
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (overlayController.canDrawOverlays()) {
                    OverlayService.show(this@MainActivity)
                }
            }
            override fun onStart(owner: LifecycleOwner) {
                OverlayService.hide(this@MainActivity)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    private fun registerKeyguardListener() {
        // Для API 30+ используем более точный listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            keyguardManager.addKeyguardLockedStateListener(
                { it.run() }, // Main executor
                object : KeyguardManager.KeyguardLockedStateListener {
                    override fun onKeyguardLockedStateChanged(isKeyguardLocked: Boolean) {
                        if (isKeyguardLocked) {
                            forceCloseApp()
                        }
                    }
                }
            )
        }
    }

    private fun forceCloseApp() {
        serviceScope.launch(Dispatchers.IO) {
            clearCacheUseCase()
        }
        OverlayService.hide(this)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun requestOverlayPermissionIfNeeded() {        
        val controller = FloatingButtonController(this)
        if (!controller.canDrawOverlays()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    companion object {
        const val EXTRA_FORCE_CLOSE = "extra_force_close"
    }
}