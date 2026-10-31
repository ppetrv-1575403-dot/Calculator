package com.google.android.calculator1.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class CalculatorColors(
    val displayBackground: Color,
    val displayText: Color,
    val historyText: Color,
    val digitButton: Color,
    val operatorButton: Color,
    val functionButton: Color,
    val buttonText: Color
)

val DarkCalculatorColors = CalculatorColors(
    displayBackground = Color(0xFF1C1B1F),
    displayText = Color.White,
    historyText = Color(0xFF8E8E93),
    digitButton = Color(0xFF2C2C2E),
    operatorButton = Color(0xFF4A90E2),
    functionButton = Color(0xFF3A3A3C),
    buttonText = Color.White
)

val LightCalculatorColors = CalculatorColors(
    displayBackground = Color(0xFFF5F5F7),
    displayText = Color(0xFF1C1B1F),
    historyText = Color(0xFF8E8E93),
    digitButton = Color.White,
    operatorButton = Color(0xFF007AFF),
    functionButton = Color(0xFFE5E5EA),
    buttonText = Color(0xFF1C1B1F)
)

val LocalCalculatorColors = staticCompositionLocalOf { DarkCalculatorColors }

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A90E2),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF2C2C2E),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    background = Color(0xFFF5F5F7),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

@Composable
fun VaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val calculatorColors = if (darkTheme) DarkCalculatorColors else LightCalculatorColors

    //val view = LocalView.current
    //if (!view.isInEditMode) {
    //    SideEffect {
    //        val window = (view.context as Activity).window
    //)        window.statusBarColor = colorScheme.background.toArgb()
    //        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    //)    }
    //}

    CompositionLocalProvider(LocalCalculatorColors provides calculatorColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}