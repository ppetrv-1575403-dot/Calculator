package com.google.android.calculator.presentation

import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.calculator.presentation.MainActivity
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.presentation.calculator.CalculatorScreen
import com.google.android.calculator.presentation.filepicker.FilePickerScreen
import com.google.android.calculator.presentation.locations.AddLocationScreen
import com.google.android.calculator.presentation.locations.AddLocationFromPhotoScreen
import com.google.android.calculator.presentation.locations.MapLibreMapScreen
import com.google.android.calculator.presentation.locations.OfflineMapsScreen
import com.google.android.calculator.presentation.preview.PreviewScreen
import com.google.android.calculator.presentation.settings.SettingsScreen
import com.google.android.calculator.presentation.setup.SetupPinScreen
import com.google.android.calculator.presentation.vault.VaultScreen
import com.google.android.calculator.utils.DecoySeeder
import com.google.android.calculator.utils.PermissionUtils
import com.google.android.calculator.utils.VaultManager
import com.google.android.calculator.utils.VaultMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AppNavigator(appContainer: AppContainer, mainActivity: MainActivity) {
    val navController = rememberNavController()
    val startDestination = if (appContainer.isFirstLaunchUseCase()) "setup_pin" else "calculator"

    LaunchedEffect(Unit) {
        appContainer.navigateToCalculator.collect { should ->
            if (should) {
                navController.navigate("calculator") { popUpTo(0) { inclusive = true } }
                appContainer.resetNavigateSignal()
                mainActivity.markVaultAsClosed()
                VaultManager.enterMode(VaultMode.REAL)
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable("setup_pin") {
            SetupPinScreen(appContainer) {
                navController.navigate("calculator") { popUpTo("setup_pin") { inclusive = true } }
            }
        }

        composable("calculator") {
            LaunchedEffect(Unit) { mainActivity.markVaultAsClosed() }
            CalculatorScreen(appContainer) { navController.navigate("vault") }
        }

        composable("vault") {
            LaunchedEffect(Unit) { mainActivity.markVaultAsOpen() }
            VaultScreen(
                appContainer = appContainer,
                onBack = {
                    navController.popBackStack()
                    mainActivity.markVaultAsClosed()
                    VaultManager.enterMode(VaultMode.REAL)
                },
                onNavigateToPicker = { fid -> navController.navigate("file_picker/${fid.ifEmpty { "root" }}") },
                onAddLocation = { fid -> navController.navigate("add_location/${fid.ifEmpty { "root" }}") },
                onOpenMap = { id -> navController.navigate("map/$id") },
                onPreviewFile = { id -> navController.navigate("preview/$id") },
                onOpenSettings = { navController.navigate("settings") },
                onEditLocation = { id -> navController.navigate("add_location/root?editId=$id") },
                onAddPhotoLocation = { fid -> navController.navigate("add_location_photo/${fid.ifEmpty { "root" }}") }
            )
        }

        composable(
            route = "file_picker/{folderId}",
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { entry ->
            val fid = entry.arguments?.getString("folderId") ?: "root"
            if (PermissionUtils.hasManageExternalStorage()) {
                FilePickerScreen(
                    appContainer = appContainer,
                    initialPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
                    folderId = fid.takeIf { it != "root" },
                    onBack = { navController.popBackStack() }
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(
            route = "add_location/{folderId}?editId={editId}",
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
                navArgument("editId") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) { entry ->
            val fid = entry.arguments?.getString("folderId") ?: "root"
            val editId = entry.arguments?.getString("editId")?.takeIf { it.isNotEmpty() }
            AddLocationScreen(
                appContainer = appContainer,
                folderId = fid.takeIf { it != "root" },
                editLocationId = editId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "add_location_photo/{folderId}",
            arguments = listOf(navArgument("folderId") { type = NavType.StringType })
        ) { entry ->
            val fid = entry.arguments?.getString("folderId") ?: "root"
            AddLocationFromPhotoScreen(
                appContainer = appContainer,
                folderId = fid.takeIf { it != "root" },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "map/{locationId}",
            arguments = listOf(navArgument("locationId") { type = NavType.StringType })
        ) { entry ->
            MapLibreMapScreen(
                appContainer = appContainer,
                locationId = entry.arguments?.getString("locationId") ?: "",
                onBack = { navController.popBackStack() },
                onOpenPhoto = { id -> navController.navigate("preview/$id") },
                onEditLocation = { id -> navController.navigate("add_location/root?editId=$id") },
                onOpenOfflineMaps = { navController.navigate("offline_maps") }
            )
        }

        composable("offline_maps") {
            OfflineMapsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "preview/{fileId}",
            arguments = listOf(navArgument("fileId") { type = NavType.StringType })
        ) { entry ->
            PreviewScreen(
                fileId = entry.arguments?.getString("fileId") ?: "",
                appContainer = appContainer,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                securePreferences = appContainer.securePreferences,
                onBack = { navController.popBackStack() },
                onOpenOfflineMaps = { navController.navigate("offline_maps") },
                onOpenAbout = { /* TODO */ },
                onSyncMainPin = { newPin ->
                    appContainer.savePinUseCase(newPin)
                },
                onSeedDecoyData = {
                    CoroutineScope(Dispatchers.IO).launch {
                        VaultManager.enterMode(VaultMode.DECOY)
                        DecoySeeder.seedLocationRepository(appContainer.locationRepository)
                        VaultManager.enterMode(VaultMode.REAL)
                    }
                }
            )
        }
    }
}