package com.google.android.calculator.presentation.locations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.utils.CoordinateRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLocationFromPhotoScreen(
    appContainer: AppContainer,
    folderId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var name by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Выберите фото с координатами") }
    var isProcessing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            onBack()
        } else {
            imageUri = uri
            scope.launch {
                isProcessing = true
                status = "Распознавание координат..."
                previewBitmap = loadPreview(context, uri)
                val result = CoordinateRecognizer.recognize(context, uri)
                if (result != null) {
                    latText = String.format(Locale.US, "%.6f", result.latitude)
                    lonText = String.format(Locale.US, "%.6f", result.longitude)
                    status = "✅ Координаты найдены (источник: ${result.source})"
                } else {
                    status = "⚠️ Координаты не найдены — введите вручную"
                }
                isProcessing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        picker.launch("image/*")
    }

    fun save() {
        val uri = imageUri ?: return
        val lat = latText.replace(',', '.').toDoubleOrNull()
        val lon = lonText.replace(',', '.').toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            status = "❌ Некорректные координаты"
            return
        }
        scope.launch {
            isProcessing = true
            status = "Сохранение..."
            try {
                val addResult = appContainer.vaultRepository.addFileWithId(
                    uri,
                    "photo_${System.currentTimeMillis()}.jpg",
                    "image/jpeg",
                    folderId
                )
                addResult.onSuccess { photoId ->
                    appContainer.locationRepository.addLocation(
                        SavedLocation(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { "Фото-точка $latText, $lonText" },
                            description = "Создано по фото",
                            latitude = lat,
                            longitude = lon,
                            photoId = photoId,
                            folderId = folderId
                        )
                    )
                    onBack()
                }.onFailure { e ->
                    status = "❌ Ошибка сохранения: ${e.message}"
                }
            } finally {
                isProcessing = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Точка по фото") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()          // ⭐ не залазим под системную навигацию
                .imePadding()                      // ⭐ не залазим под клавиатуру
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Предпросмотр фото
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = previewBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Фото",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Image, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Фото не выбрано", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (isProcessing) {
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                }
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название точки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Широта") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Долгота") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Другое фото")
                }
                Button(
                    onClick = { save() },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing && imageUri != null
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить")
                }
            }
        }
    }
}

private suspend fun loadPreview(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (e: Exception) {
        null
    }
}