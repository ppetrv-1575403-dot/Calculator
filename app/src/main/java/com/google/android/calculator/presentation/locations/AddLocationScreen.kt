package com.google.android.calculator.presentation.locations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.domain.model.VaultFile
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLocationScreen(
    appContainer: AppContainer,
    folderId: String?,
    editLocationId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lngText by remember { mutableStateOf("") }
    var attachedPhotoId by remember { mutableStateOf<String?>(null) }
    var attachedPhotoName by remember { mutableStateOf<String?>(null) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var vaultImages by remember { mutableStateOf<List<VaultFile>>(emptyList()) }
    var isLocating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingTakePhoto by remember { mutableStateOf(false) }
    val cameraFile = remember { mutableStateOf<File?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        hasLocationPermission = g[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        scope.launch {
            val f = cameraFile.value
            if (success && f != null && f.exists()) {
                appContainer.vaultRepository.addFileWithId(Uri.fromFile(f), f.name, "image/jpeg", folderId)
                    .onSuccess { id -> attachedPhotoId = id; attachedPhotoName = f.name }
                f.delete()
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted && pendingTakePhoto) {
            pendingTakePhoto = false
            launchCamera(cameraFile, cameraLauncher, context)
        }
    }

    LaunchedEffect(editLocationId) {
        if (editLocationId != null) {
            val loc = appContainer.locationRepository.getLocationById(editLocationId)
            if (loc != null) {
                name = loc.name
                description = loc.description
                latText = loc.latitude.toString()
                lngText = loc.longitude.toString()
                attachedPhotoId = loc.photoId
                attachedPhotoName = if (loc.photoId != null) "Прикреплённое фото" else null
            }
        }
    }

    fun fetchLocation() {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        isLocating = true
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                latText = l.latitude.toString(); lngText = l.longitude.toString()
                isLocating = false; lm.removeUpdates(this)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                latText = lastLoc.latitude.toString()
                lngText = lastLoc.longitude.toString()
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0f, listener)
        } catch (e: Exception) { isLocating = false }
    }

    fun save() {
        val lat = latText.toDoubleOrNull(); val lng = lngText.toDoubleOrNull()
        if (name.isBlank() || lat == null || lng == null) { message = "Заполните название и координаты"; return }
        scope.launch {
            val location = SavedLocation(
                editLocationId ?: System.currentTimeMillis().toString(),
                name, description, lat, lng, attachedPhotoId, folderId
            )
            if (editLocationId != null) {
                appContainer.locationRepository.updateLocation(location)
            } else {
                appContainer.locationRepository.addLocation(location)
            }
            appContainer.requestVaultRefresh()
            onBack()
        }
    }

    LaunchedEffect(showPhotoPicker) {
        if (showPhotoPicker) vaultImages = appContainer.getFilesUseCase().filter { it.mimeType.startsWith("image/") }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editLocationId != null) "Редактировать точку" else "Новая точка") },
                navigationIcon = { IconButton(onClick = onBack) { @Suppress("DEPRECATION") Icon(Icons.Default.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latText, { latText = it }, label = { Text("Широта") }, modifier = Modifier.weight(1f))
                OutlinedTextField(lngText, { lngText = it }, label = { Text("Долгота") }, modifier = Modifier.weight(1f))
            }
            Button(onClick = { fetchLocation() }, modifier = Modifier.fillMaxWidth(), enabled = !isLocating) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isLocating) "Определение..." else "Определить текущие координаты")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPhotoPicker = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Фото из хранилища")
                }
                OutlinedButton(onClick = {
                    if (hasCameraPermission) launchCamera(cameraFile, cameraLauncher, context)
                    else { pendingTakePhoto = true; cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Сделать фото")
                }
            }
            attachedPhotoName?.let {
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { attachedPhotoId = null; attachedPhotoName = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Убрать", Modifier.size(16.dp))
                        }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (editLocationId != null) "Сохранить изменения" else "Сохранить точку")
            }
        }
    }

    if (showPhotoPicker) {
        AlertDialog(
            onDismissRequest = { showPhotoPicker = false },
            title = { Text("Выберите фото из хранилища") },
            text = {
                if (vaultImages.isEmpty()) Text("В хранилище нет изображений")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vaultImages) { img ->
                        TextButton(onClick = { attachedPhotoId = img.id; attachedPhotoName = img.originalName; showPhotoPicker = false }) {
                            Text(img.originalName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPhotoPicker = false }) { Text("Отмена") } }
        )
    }
}

private fun launchCamera(
    cameraFile: androidx.compose.runtime.MutableState<File?>,
    cameraLauncher: androidx.activity.result.ActivityResultLauncher<Uri>,
    context: Context
) {
    val f = File(File(context.cacheDir, "camera").apply { mkdirs() }, "photo_${System.currentTimeMillis()}.jpg")
    cameraFile.value = f
    cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f))
}