package com.google.android.calculator.presentation.locations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.utils.MapStyleUtils
import com.google.android.calculator.utils.OfflineMapManager
import com.google.android.calculator.utils.OfflineRegions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.util.GeoPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLibreMapScreen(
    appContainer: AppContainer,
    locationId: String,
    onBack: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onEditLocation: (String) -> Unit,
    onOpenOfflineMaps: () -> Unit
) {
    val context = LocalContext.current
    var target by remember { mutableStateOf<SavedLocation?>(null) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var deviceAzimuth by remember { mutableStateOf(0f) }
    var road by remember { mutableStateOf<Road?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var autoCenterEnabled by remember { mutableStateOf(false) }
    var mapStyleApplied by remember { mutableStateOf(false) }
    var isOfflineMode by remember { mutableStateOf(false) }
    var currentRegionId by remember { mutableStateOf<String?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        hasLocationPermission = g[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(locationId) {
        target = appContainer.locationRepository.getLocationById(locationId)
        if (!hasLocationPermission)
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Первое центрирование на пользователе
    LaunchedEffect(currentLocation) {
        if (currentLocation != null && !hasCenteredOnUser) {
            hasCenteredOnUser = true
            // После первого центрирования автоцентрирование выключено по умолчанию
        }
    }

    // Останавливаем локальный сервер при выходе с экрана
    DisposableEffect(Unit) {
        onDispose {
            currentRegionId?.let { regionId ->
                OfflineMapManager.stopServer(regionId)
            }
        }
    }

    // Роутинг
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        while (true) {
            val cur = currentLocation
            if (cur != null) {
                isLoadingRoute = true
                val route = withContext(Dispatchers.IO) {
                    try {
                        val roadManager = OSRMRoadManager(context, "calculator_vault_app")
                        roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                        val wayPoints = arrayListOf(
                            GeoPoint(cur.latitude, cur.longitude),
                            GeoPoint(t.latitude, t.longitude)
                        )
                        roadManager.getRoad(wayPoints)
                    } catch (e: Exception) { null }
                }
                if (route != null && route.mRouteHigh.isNotEmpty()) road = route
                isLoadingRoute = false
            }
            delay(10000)
        }
    }

    // Компас
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            val rm = FloatArray(9); val or = FloatArray(3)
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rm, e.values)
                android.hardware.SensorManager.getOrientation(rm, or)
                deviceAzimuth = Math.toDegrees(or[0].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    // GPS
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { currentLocation = l }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, listener)
        } catch (e: Exception) { }
        onDispose { lm.removeUpdates(listener) }
    }

    val targetLoc = target?.let { Location("target").apply { latitude = it.latitude; longitude = it.longitude } }
    val distance = currentLocation?.let { c -> targetLoc?.let { t -> c.distanceTo(t) } }
    val bearingTo = currentLocation?.let { c -> targetLoc?.let { t -> c.bearingTo(t) } }
    val arrowRotation = (bearingTo ?: 0f) - deviceAzimuth

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(target?.name ?: "Карта", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { @Suppress("DEPRECATION") Icon(Icons.Default.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = onOpenOfflineMaps) {
                        Icon(Icons.Default.Map, "Оффлайн-карты")
                    }
                    IconButton(onClick = { target?.let { onEditLocation(it.id) } }) {
                        Icon(Icons.Default.Edit, "Редактировать")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    MapLibre.getInstance(ctx)
                    android.widget.Toast.makeText(ctx, "🗺️ Карта создаётся...", android.widget.Toast.LENGTH_SHORT).show()
                    MapView(ctx)
                },
                update = { mapView ->
                    if (!mapStyleApplied && target != null) {
                        mapStyleApplied = true

                        mapView.getMapAsync { map ->
                            val region = target?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }
                                ?: currentLocation?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }

                            val tileSourceUrl = if (region != null) {
                                currentRegionId = region.id
                                val url = OfflineMapManager.getTileSource(mapView.context, region.id)
                                if (url.contains("localhost")) {
                                    runBlocking { delay(500) }
                                }
                                url
                            } else {
                                "https://tiles.openfreemap.org/planet"
                            }

                            isOfflineMode = tileSourceUrl.contains("localhost")

                            android.widget.Toast.makeText(
                                mapView.context,
                                if (isOfflineMode) "📱 Оффлайн-карта" else "🌐 Онлайн-карта",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()

                            val styleBuilder = Style.Builder().fromJson(MapStyleUtils.getBasicStyleJson(tileSourceUrl))

                            map.setStyle(styleBuilder) { style ->
                                style.addImage("user_arrow", createUserArrowBitmap(Color.BLUE))
                                style.addImage("target_pin", createTargetPinBitmap())

                                // Слой маршрута (добавляем до маркеров, чтобы маркеры были сверху)
                                val routeSource = GeoJsonSource("route_source")
                                style.addSource(routeSource)
                                style.addLayer(
                                    LineLayer("route_layer", "route_source").withProperties(
                                        PropertyFactory.lineColor("#2196F3"),
                                        PropertyFactory.lineWidth(4f),
                                        PropertyFactory.lineCap("round"),
                                        PropertyFactory.lineJoin("round")
                                    )
                                )

                                val userSource = GeoJsonSource("user_source")
                                style.addSource(userSource)
                                style.addLayer(
                                    SymbolLayer("user_layer", "user_source").withProperties(
                                        PropertyFactory.iconImage("user_arrow"),
                                        PropertyFactory.iconAllowOverlap(true),
                                        PropertyFactory.iconIgnorePlacement(true)
                                    )
                                )

                                val targetSource = GeoJsonSource("target_source")
                                target?.let { t ->
                                    targetSource.setGeoJson(
                                        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${t.longitude},${t.latitude}]}}"""
                                    )
                                }
                                style.addSource(targetSource)
                                style.addLayer(
                                    SymbolLayer("target_layer", "target_source").withProperties(
                                        PropertyFactory.iconImage("target_pin"),
                                        PropertyFactory.iconAllowOverlap(true),
                                        PropertyFactory.iconIgnorePlacement(true)
                                    )
                                )

                                // Если маршрут уже построен — показываем его
                                road?.let { r ->
                                    routeSource.setGeoJson(roadToGeoJson(r))
                                }
                            }

                            target?.let { t ->
                                val zoom = if (isOfflineMode) 12.0 else 15.0
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(LatLng(t.latitude, t.longitude))
                                    .zoom(zoom)
                                    .build()
                            }
                        }
                    }

                    mapView.getMapAsync { map ->
                        // Обновляем позицию пользователя
                        currentLocation?.let { loc ->
                            (map.style?.getSource("user_source") as? GeoJsonSource)?.setGeoJson(
                                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${loc.longitude},${loc.latitude}]}}"""
                            )
                        }

                        // Обновляем маршрут
                        road?.let { r ->
                            (map.style?.getSource("route_source") as? GeoJsonSource)?.setGeoJson(roadToGeoJson(r))
                        }

                        // Центрирование: только при первом запуске или если автоцентрирование включено
                        if (currentLocation != null) {
                            val loc = currentLocation!!
                            val targetLatLng = LatLng(loc.latitude, loc.longitude)

                            // Первое центрирование
                            if (!hasCenteredOnUser) {
                                map.animateCamera(CameraUpdateFactory.newLatLng(targetLatLng), 1000)
                                hasCenteredOnUser = true
                            }
                            // Автоцентрирование (если включено)
                            else if (autoCenterEnabled) {
                                val currentTarget = map.cameraPosition.target
                                if (currentTarget == null || currentTarget.distanceTo(targetLatLng) > 50) {
                                    map.animateCamera(CameraUpdateFactory.newLatLng(targetLatLng), 1000)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Кнопка автоцентрирования (переключение)
            FloatingActionButton(
                onClick = {
                    autoCenterEnabled = !autoCenterEnabled
                    if (autoCenterEnabled) {
                        // При включении автоцентрирования сразу центрируем
                        hasCenteredOnUser = false
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 220.dp),
                shape = CircleShape,
                containerColor = if (autoCenterEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (autoCenterEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    if (autoCenterEnabled) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                    if (autoCenterEnabled) "Автоцентрирование включено" else "Автоцентрирование выключено"
                )
            }

            // Кнопка центрирования (однократное)
            FloatingActionButton(
                onClick = {
                    currentLocation?.let {
                        hasCenteredOnUser = false
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 290.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.MyLocation, "Моё местоположение")
            }

            // Нижняя панель
            Surface(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Navigation, null,
                            Modifier.size(32.dp).rotate(arrowRotation),
                            tint = ComposeColor(0xFF2196F3)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    road != null -> formatRouteInfo(road!!)
                                    isLoadingRoute -> "Строим маршрут..."
                                    else -> distance?.let { formatDistance(it) } ?: "Определение расстояния..."
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            target?.let {
                                Text("%.5f, %.5f".format(it.latitude, it.longitude),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (target?.photoId != null) {
                            IconButton(onClick = { target?.photoId?.let { onOpenPhoto(it) } }) {
                                Icon(Icons.Default.Image, "Фото")
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isOfflineMode) Icons.Default.CloudOff else Icons.Default.CloudDone,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isOfflineMode) "Оффлайн-карта (локальная)" else "Онлайн-карта",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onOpenOfflineMaps) {
                            Icon(Icons.Default.Settings, "Управление картами", Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Индикатор роутинга
            if (isLoadingRoute) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 70.dp),
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Строим маршрут...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun roadToGeoJson(road: Road): String {
    val points = road.mRouteHigh.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$points]}}"""
}

private fun formatDistance(m: Float): String = if (m < 1000) "${m.toInt()} м" else String.format("%.2f км", m / 1000)

private fun formatRouteInfo(route: Road): String {
    val lengthKm = route.mLength
    val durationMin = (route.mDuration / 60).toInt()
    return if (lengthKm < 1) {
        "${(lengthKm * 1000).toInt()} м • ${durationMin} мин пешком"
    } else {
        String.format("%.2f км • %d мин пешком", lengthKm, durationMin)
    }
}

private fun createUserArrowBitmap(color: Int): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outlinePaint = Paint().apply {
        this.color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    val fillPaint = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val outlinePath = Path().apply {
        moveTo(size / 2f, -4f)
        lineTo(size * 0.85f, size * 0.95f)
        lineTo(size / 2f, size * 0.7f)
        lineTo(size * 0.15f, size * 0.95f)
        close()
    }
    val fillPath = Path().apply {
        moveTo(size / 2f, 2f)
        lineTo(size * 0.8f, size * 0.9f)
        lineTo(size / 2f, size * 0.65f)
        lineTo(size * 0.2f, size * 0.9f)
        close()
    }

    canvas.drawPath(outlinePath, outlinePaint)
    canvas.drawPath(fillPath, fillPaint)
    return bitmap
}

private fun createTargetPinBitmap(): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val outlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    val fillPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    canvas.drawCircle(size / 2f, size / 3f, size / 4f + 4f, outlinePaint)
    canvas.drawCircle(size / 2f, size / 3f, size / 4f, fillPaint)
    val path = Path().apply {
        moveTo(size / 2f - size / 5f, size / 3f + size / 6f)
        lineTo(size / 2f, size.toFloat() - 4f)
        lineTo(size / 2f + size / 5f, size / 3f + size / 6f)
        close()
    }
    canvas.drawPath(path, fillPaint)
    val dotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    canvas.drawCircle(size / 2f, size / 3f, size / 10f, dotPaint)

    return bitmap
}