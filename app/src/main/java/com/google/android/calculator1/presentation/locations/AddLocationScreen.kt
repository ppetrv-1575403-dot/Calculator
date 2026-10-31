package com.google.android.calculator1.presentation.locations

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.calculator1.di.AppContainer
import com.google.android.calculator1.domain.model.SavedLocation
import com.google.android.calculator1.domain.model.VaultFile
import com.google.android.calculator1.utils.ClipboardManager
import kotlinx.coroutines.launch
import java.io.File
import java.util.regex.Pattern

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
    val clipboardManager = LocalClipboardManager.current
    
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var coordinatesText by remember { mutableStateOf("") }
    var parsedLat by remember { mutableStateOf<Double?>(null) }
    var parsedLng by remember { mutableStateOf<Double?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
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

    // Парсер координат
    fun parseCoordinates(text: String): Pair<Double, Double>? {
        if (text.isBlank()) return null
        
        val cleaned = text.trim()
        
        // Паттерны для парсинга
        val patterns = listOf(
            // "52.123456, 23.654321" или "52.123456 23.654321"
            Pattern.compile("(-?\\d+\\.?\\d*)\\s*[,\\s]\\s*(-?\\d+\\.?\\d*)"),
            // "N52.123456, E23.654321"
            Pattern.compile("[NS]?(-?\\d+\\.?\\d*)[,\\s]+[EW]?(-?\\d+\\.?\\d*)"),
            // Geo URI: "geo:52.123456,23.654321"
            Pattern.compile("geo:(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)")
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(cleaned)
            if (matcher.find()) {
                try {
                    val lat = matcher.group(1)?.toDoubleOrNull()
                    val lng = matcher.group(2)?.toDoubleOrNull()
                    if (lat != null && lng != null) {
                        return Pair(lat, lng)
                    }
                } catch (e: Exception) {
                    // Продолжаем пробовать следующий паттерн
                }
            }
        }
        
        return null
    }

    // Автоматический парсинг при изменении текста
    LaunchedEffect(coordinatesText) {
        if (coordinatesText.isBlank()) {
            parsedLat = null
            parsedLng = null
            parseError = null
        } else {
            val result = parseCoordinates(coordinatesText)
            if (result != null) {
                parsedLat = result.first
                parsedLng = result.second
                parseError = null
            } else {
                parsedLat = null
                parsedLng = null
                parseError = "Не удалось распознать координаты"
            }
        }
    }

    LaunchedEffect(editLocationId) {
        if (editLocationId != null) {
            val loc = appContainer.locationRepository.getLocationById(editLocationId)
            if (loc != null) {
                name = loc.name
                description = loc.description
                coordinatesText = "${loc.latitude}, ${loc.longitude}"
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
                coordinatesText = "${l.latitude}, ${l.longitude}"
                isLocating = false
                lm.removeUpdates(this)
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
                coordinatesText = "${lastLoc.latitude}, ${lastLoc.longitude}"
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0f, listener)
        } catch (e: Exception) {
            isLocating = false
        }
    }

    fun pasteFromClipboard() {
        val text = clipboardManager.getText()?.text
        if (!text.isNullOrBlank()) {
            coordinatesText = text
        }
    }

    fun copyCoordinates() {
        if (parsedLat != null && parsedLng != null) {
            val text = "$parsedLat, $parsedLng"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Координаты", text))
            message = "Координаты скопированы"
        }
    }
    
    fun save() {
        if (name.isBlank()) {
            message = "Заполните название"
            return
        }
        if (parsedLat == null || parsedLng == null) {
            message = "Введите корректные координаты"
            return
        }
        
        scope.launch {
            val location = SavedLocation(
                editLocationId ?: System.currentTimeMillis().toString(),
                name, description, parsedLat!!, parsedLng!!, attachedPhotoId, folderId
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                description,
                { description = it },
                label = { Text("Описание") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            
            // Поле координат с кнопками
            OutlinedTextField(
                coordinatesText,
                { coordinatesText = it },
                label = { Text("Координаты (широта, долгота)") },
                placeholder = { Text("52.123456, 23.654321") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                trailingIcon = {
                    if (parsedLat != null && parsedLng != null) {
                        IconButton(onClick = { copyCoordinates() }) {
                            Icon(Icons.Default.ContentCopy, "Копировать координаты")
                        }
                    }
                }
            )
            
            // Отображение распарсенных координат
            if (parsedLat != null && parsedLng != null) {
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Широта", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "%.6f".format(parsedLat),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Долгота", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "%.6f".format(parsedLng),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else if (parseError != null && coordinatesText.isNotBlank()) {
                Text(
                    parseError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Кнопки действий
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pasteFromClipboard() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentPaste, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Вставить")
                }
                OutlinedButton(
                    onClick = { coordinatesText = "" },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Clear, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Очистить")
                }
            }
            
            Button(
                onClick = { fetchLocation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLocating
            ) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isLocating) "Определение..." else "Определить текущие координаты")
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showPhotoPicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Фото из хранилища")
                }
                OutlinedButton(
                    onClick = {
                        if (hasCameraPermission) launchCamera(cameraFile, cameraLauncher, context)
                        else {
                            pendingTakePhoto = true
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сделать фото")
                }
            }
            
            attachedPhotoName?.let {
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { attachedPhotoId = null; attachedPhotoName = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, "Убрать", Modifier.size(16.dp))
                        }
                    }
                }
            }
            
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            
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
                        TextButton(onClick = {
                            attachedPhotoId = img.id
                            attachedPhotoName = img.originalName
                            showPhotoPicker = false
                        }) {
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