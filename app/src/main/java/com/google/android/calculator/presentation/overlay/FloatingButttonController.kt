package com.google.android.calculator.presentation.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.calculator.presentation.MainActivity

class FloatingButtonController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun show() {
        if (!canDrawOverlays()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_FORCE_CLOSE, true)
        }

        val button = android.widget.TextView(context).apply {
            text = "✕"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(Color(0xFFE53935).toArgb())
            gravity = android.view.Gravity.CENTER
            val size = (56 * context.resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(
                size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 120
            }
            setOnClickListener {
                hide()
                context.startActivity(intent)
            }
        }

        try {
            windowManager.addView(button, button.layoutParams)
            currentView = button
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        currentView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            currentView = null
        }
    }

    fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private var currentView: android.view.View? = null
}