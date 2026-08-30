package com.google.android.calculator.presentation

import android.os.Environment
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.calculator.MainActivity
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.presentation.calculator.CalculatorScreen
import com.google.android.calculator.presentation.filepicker.FilePickerScreen
import com.google.android.calculator.presentation.preview.PreviewScreen
import com.google.android.calculator.presentation.setup.SetupPinScreen
import com.google.android.calculator.presentation.vault.VaultScreen
import com.google.android.calculator.utils.PermissionUtils

@Composable
fun AppNavigator(appContainer: AppContainer, mainActivity: MainActivity) {
    val navController = rememberNavController()
    val startDestination = if (appContainer.isFirstLaunchUseCase()) "setup_pin" else "calculator"

    // Слушаем сигнал перехода на калькулятор
    LaunchedEffect(Unit) {
        appContainer.navigateToCalculator.collect { shouldNavigate ->
            if (shouldNavigate) {
                Log.d("AppNavigation", "Navigate to calculator signal received")
                navController.navigate("calculator") {
                    popUpTo(0) { inclusive = true } // Очищаем весь стек
                }
                appContainer.resetNavigateSignal()
                mainActivity.markVaultAsClosed()
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("setup_pin") {
            SetupPinScreen(
                appContainer = appContainer,
                onSuccess = {
                    navController.navigate("calculator") {
                        popUpTo("setup_pin") { inclusive = true }
                    }
                }
            )
        }
        composable("calculator") {
            LaunchedEffect(Unit) {
                Log.d("AppNavigation", "Calculator screen shown")
                mainActivity.markVaultAsClosed()
            }
            
            CalculatorScreen(
                appContainer = appContainer,
                onNavigateToVault = { 
                    Log.d("AppNavigation", "Navigate to vault")
                    navController.navigate("vault") 
                }
            )
        }
        composable("vault") {
            LaunchedEffect(Unit) {
                Log.d("AppNavigation", "Vault screen shown")
                mainActivity.markVaultAsOpen()
            }
            
            VaultScreen(
                appContainer = appContainer,
                onBack = { 
                    Log.d("AppNavigation", "Back from vault")
                    navController.popBackStack()
                    mainActivity.markVaultAsClosed()
                },
                onNavigateToPicker = {
                    navController.navigate("file_picker")
                },
                onPreviewFile = { fileId ->
                    navController.navigate("preview/$fileId")
                }
            )
        }
        
        // <-- ИСПРАВЛЕННЫЙ ЭКРАН ПИКЕРА -->
        composable("file_picker") {
            if (PermissionUtils.hasManageExternalStorage()) {
                // Получаем путь к папке Загрузки как стартовую точку
                val startPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                
                FilePickerScreen(
                    appContainer = appContainer,
                    initialPath = startPath, // <-- Здесь было исправлено с directoryUri на initialPath
                    onBack = { navController.popBackStack() }
                )
            } else {
                // Если разрешения нет, сразу возвращаемся назад (там сработает запрос через FAB)
                LaunchedEffect(Unit) { 
                    navController.popBackStack() 
                }
            }
        }
        // <-- КОНЕЦ ИСПРАВЛЕНИЯ -->

        composable(
            route = "preview/{fileId}",
            arguments = listOf(
                navArgument("fileId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            
            PreviewScreen(
                fileId = fileId,
                appContainer = appContainer,
                onBack = { navController.popBackStack() }
            )
        }
    }
}