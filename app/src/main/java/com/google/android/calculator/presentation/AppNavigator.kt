package com.google.android.calculator.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.presentation.calculator.CalculatorScreen
import com.google.android.calculator.presentation.preview.PreviewScreen
import com.google.android.calculator.presentation.setup.SetupPinScreen
import com.google.android.calculator.presentation.vault.VaultScreen

@Composable
fun AppNavigator(appContainer: AppContainer) {
    val navController = rememberNavController()
    val startDestination = if (appContainer.isFirstLaunchUseCase()) "setup_pin" else "calculator"

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
            CalculatorScreen(
                appContainer = appContainer,
                onNavigateToVault = { navController.navigate("vault") }
            )
        }
        composable("vault") {
            VaultScreen(
                appContainer = appContainer,
                onBack = { navController.popBackStack() },
                // Теперь передаем ТОЛЬКО fileId (строку)
                onPreviewFile = { fileId ->
                    navController.navigate("preview/$fileId")
                }
            )
        }
        composable(
            route = "preview/{fileId}",
            arguments = listOf(
                navArgument("fileId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            
            // Передаем 3 параметра, которые ожидает PreviewScreen
            PreviewScreen(
                fileId = fileId,
                appContainer = appContainer,
                onBack = { navController.popBackStack() }
            )
        }
    }
}