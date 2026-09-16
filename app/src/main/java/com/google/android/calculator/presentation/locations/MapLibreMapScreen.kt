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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.android.calculator.di.AppContainer
import com.google.android.calculator.domain.model.SavedLocation
import com.google.android.calculator.utils.FileLogger
import com.google.android.calculator.utils.MapStyleUtils
import com.google.android.calculator.utils.OfflineMapManager
import com.google.android.calculator.utils.OfflineRegions
import com.google.android.calculator.utils.OfflineRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.util.GeoPoint
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    var routePoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var routeDistance by remember { mutableStateOf<Double?>(null) }
    var routeDuration by remember { mutableStateOf<Double?>(null) }
    var hasDirectLine by remember { mutableStateOf(false) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var isOfflineRouting by remember { mutableStateOf(false) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var autoCenterEnabled by remember { mutableStateOf(false) }
    var transportMode by remember { mutableStateOf("foot") }
    var isOfflineMode by remember { mutableStateOf(false) }
    var currentRegionId by remember { mutableStateOf<String?>(null) }
    
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var userSourceRef by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userLayerRef by remember { mutableStateOf<SymbolLayer?>(null) }
    var routeSourceRef by remember { mutableStateOf<GeoJsonSource?>(null) }
    
    // Executor для гарантированного таймаута роутинга
    val routeExecutor = remember { Executors.newSingleThreadExecutor() }
    
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

    DisposableEffect(Unit) {
        onDispose {
            currentRegionId?.let { regionId ->
                OfflineMapManager.stopServer(regionId)
            }
            OfflineRouter.unloadGraph()
            routeExecutor.shutdownNow()
        }
    }

    // Роутинг с гарантированным таймаутом через Java Executor
    LaunchedEffect(target, transportMode, isOfflineRouting) {
        val t = target ?: return@LaunchedEffect
        FileLogger.i("Route", "Routing loop started for target: ${t.name}")
        
        val region = OfflineRegions.findRegionForPoint(t.latitude, t.longitude)
            ?: currentLocation?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }
        
        val hasOfflineGraph = region != null && OfflineRouter.isGraphDownloaded(context, region.id)
        
        if (hasOfflineGraph && region != null) {
            val loadError = withContext(Dispatchers.IO) {
                OfflineRouter.loadGraph(context, region.id)
            }
            
            if (loadError != null) {
                isOfflineRouting = false
                FileLogger.e("Route", "Graph load failed: $loadError")
            } else {
                isOfflineRouting = true
                FileLogger.i("Route", "Offline routing active")
            }
        } else {
            isOfflineRouting = false
            FileLogger.i("Route", "Using online routing (no graph)")
        }
        
        while (true) {
            val cur = currentLocation
            if (cur == null) {
                FileLogger.w("Route", "Waiting for GPS location...")
            } else {
                FileLogger.d("Route", "Building route from (${cur.latitude}, ${cur.longitude}) to (${t.latitude}, ${t.longitude})")
                
                // СРАЗУ показываем прямую линию — UI не зависает
                hasDirectLine = true
                routePoints = listOf(
                    Pair(cur.latitude, cur.longitude),
                    Pair(t.latitude, t.longitude)
                )
                routeDistance = distanceBetween(cur.latitude, cur.longitude, t.latitude, t.longitude)
                routeDuration = routeDistance?.let { dist ->
                    if (transportMode == "car") dist / (50.0 / 3.6) else dist / (5.0 / 3.6)
                }
                isLoadingRoute = true
                
                if (isOfflineRouting) {
                    val vehicle = if (transportMode == "car") "car" else "foot"
                    FileLogger.d("Route", "Using offline routing, vehicle=$vehicle")
                    
                    // Используем Executor для гарантированного таймаута
                    val future = routeExecutor.submit<Pair<List<Pair<Double, Double>>?, Double?>?> {
                        val pts = OfflineRouter.getRoute(
                            cur.latitude, cur.longitude,
                            t.latitude, t.longitude,
                            vehicle
                        )
                        val dist = if (pts != null) {
                            OfflineRouter.getRouteDistance(
                                cur.latitude, cur.longitude,
                                t.latitude, t.longitude,
                                vehicle
                            )
                        } else null
                        Pair(pts, dist)
                    }
                    
                    try {
                        // Ждём максимум 10 секунд
                        val routeResult = future.get(10, TimeUnit.SECONDS)
                        
                        // Явно проверяем типы
                        val points: List<Pair<Double, Double>>? = routeResult?.first
                        val distance: Double? = routeResult?.second
                        
                        if (points != null && points.isNotEmpty()) {
                            val lastPoint = points.last()
                            val distToTarget = distanceBetween(lastPoint.first, lastPoint.second, t.latitude, t.longitude)
                            
                            val finalPoints: List<Pair<Double, Double>> = if (distToTarget > 100) {
                                hasDirectLine = true
                                points + Pair(t.latitude, t.longitude)
                            } else {
                                hasDirectLine = false
                                points
                            }
                            
                            routePoints = finalPoints
                            routeDistance = distance
                            routeDuration = distance?.let { dist ->
                                if (transportMode == "car") dist / (50.0 / 3.6) else dist / (5.0 / 3.6)
                            }
                            
                            FileLogger.i("Route", "✅ Offline route: ${finalPoints.size} points, distance=${distance}m")
                        } else {
                            FileLogger.w("Route", "No route found — keeping direct line")
                        }
                    } catch (e: TimeoutException) {
                        future.cancel(true)
                        FileLogger.e("Route", "⏰ Route timeout (10s)! Keeping direct line")
                    } catch (e: Exception) {
                        FileLogger.e("Route", "❌ Route failed: ${e.message}", e)
                    }
                } else {
                    FileLogger.d("Route", "Using online routing")
                    val route = withContext(Dispatchers.IO) {
                        try {
                            val roadManager = OSRMRoadManager(context, "calculator_vault_app")
                            
                            when (transportMode) {
                                "foot" -> roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                                "car" -> roadManager.setMean(OSRMRoadManager.MEAN_BY_CAR)
                                else -> roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                            }
                            
                            val wayPoints = arrayListOf(
                                GeoPoint(cur.latitude, cur.longitude),
                                GeoPoint(t.latitude, t.longitude)
                            )
                            roadManager.getRoad(wayPoints)
                        } catch (e: Exception) {
                            FileLogger.e("Route", "Online routing failed", e)
                            null
                        }
                    }
                    
                    if (route != null && route.mRouteHigh.isNotEmpty()) {
                        val points = route.mRouteHigh.map { Pair(it.latitude, it.longitude) }
                        val lastPoint = points.last()
                        val distToTarget = distanceBetween(lastPoint.first, lastPoint.second, t.latitude, t.longitude)
                        
                        routePoints = if (distToTarget > 100) {
                            hasDirectLine = true
                            points + Pair(t.latitude, t.longitude)
                        } else {
                            hasDirectLine = false
                            points
                        }
                        routeDistance = route.mLength * 1000
                        routeDuration = route.mDuration
                        FileLogger.i("Route", "✅ Online route: ${routePoints.size} points")
                    }
                }
                
                isLoadingRoute = false
            }
            delay(15000)
        }
    }

    // Обновление маршрута на карте
    LaunchedEffect(routePoints, routeSourceRef) {
        if (routePoints.isNotEmpty() && routeSourceRef != null) {
            try {
                routeSourceRef?.setGeoJson(routeToGeoJson(routePoints))
                // Обновляем цвет линии: красный для прямой, синий для маршрута
                mapRef?.style?.getLayer("route_layer")?.let { layer ->
                    if (layer is LineLayer) {
                        layer.setProperties(
                            PropertyFactory.lineColor(if (hasDirectLine) "#FF5722" else "#2196F3")
                        )
                    }
                }
                FileLogger.d("Route", "Route displayed on map: ${routePoints.size} points, direct=$hasDirectLine")
            } catch (e: Exception) {
                FileLogger.e("Route", "Failed to display route", e)
            }
        }
    }

    // Обновление позиции пользователя и центрирование
    LaunchedEffect(currentLocation, userSourceRef) {
        currentLocation?.let { loc ->
            userSourceRef?.setGeoJson(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${loc.longitude},${loc.latitude}]}}"""
            )
            mapRef?.let { map ->
                val targetLatLng = LatLng(loc.latitude, loc.longitude)
                if (!hasCenteredOnUser) {
                    map.animateCamera(CameraUpdateFactory.newLatLng(targetLatLng), 1000)
                    hasCenteredOnUser = true
                } else if (autoCenterEnabled) {
                    val currentTarget = map.cameraPosition.target
                    if (currentTarget == null || currentTarget.distanceTo(targetLatLng) > 50) {
                        map.animateCamera(CameraUpdateFactory.newLatLng(targetLatLng), 1000)
                    }
                }
            }
        }
    }

    // Компас — напрямую обновляем слой, без Compose state
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        var lastAzimuth = 0f
        
        val listener = object : android.hardware.SensorEventListener {
            val rm = FloatArray(9); val or = FloatArray(3)
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rm, e.values)
                android.hardware.SensorManager.getOrientation(rm, or)
                val newAzimuth = Math.toDegrees(or[0].toDouble()).toFloat()
                
                if (Math.abs(newAzimuth - lastAzimuth) > 10f) {
                    lastAzimuth = newAzimuth
                    userLayerRef?.setProperties(PropertyFactory.iconRotate(newAzimuth))
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    // GPS
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                currentLocation = l
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 5f, listener)
            FileLogger.i("GPS", "Location updates started")
        } catch (e: Exception) {
            FileLogger.e("GPS", "Failed to start location updates", e)
        }
        onDispose { lm.removeUpdates(listener) }
    }

    val targetLoc = target?.let { Location("target").apply { latitude = it.latitude; longitude = it.longitude } }
    val distance = currentLocation?.let { c -> targetLoc?.let { t -> c.distanceTo(t) } }

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
                    val mapView = MapView(ctx)
                    mapView.getMapAsync { map ->
                        mapRef = map
                        
                        val region = target?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }
                            ?: currentLocation?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }

                        val tileSourceUrl = if (region != null) {
                            currentRegionId = region.id
                            OfflineMapManager.getTileSource(ctx, region.id)
                        } else {
                            "https://tiles.openfreemap.org/planet"
                        }

                        isOfflineMode = tileSourceUrl.contains("localhost")
                        FileLogger.i("Map", "Using tile source: $tileSourceUrl")

                        val styleBuilder = Style.Builder().fromJson(MapStyleUtils.getBasicStyleJson(tileSourceUrl))

                        map.setStyle(styleBuilder) { style ->
                            style.addImage("user_arrow", createUserArrowBitmap(Color.BLUE))
                            style.addImage("target_pin", createTargetPinBitmap())

                            val routeSource = GeoJsonSource("route_source")
                            style.addSource(routeSource)
                            routeSourceRef = routeSource
                            style.addLayer(
                                LineLayer("route_layer", "route_source").withProperties(
                                    PropertyFactory.lineColor("#FF5722"),
                                    PropertyFactory.lineWidth(4f),
                                    PropertyFactory.lineCap("round"),
                                    PropertyFactory.lineJoin("round")
                                )
                            )

                            val userSource = GeoJsonSource("user_source")
                            style.addSource(userSource)
                            userSourceRef = userSource
                            val userLayer = SymbolLayer("user_layer", "user_source").withProperties(
                                PropertyFactory.iconImage("user_arrow"),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true)
                            )
                            style.addLayer(userLayer)
                            userLayerRef = userLayer

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

                            FileLogger.i("Map", "Style applied, layers: ${style.layers.size}")
                        }

                        target?.let { t ->
                            val zoom = if (isOfflineMode) 12.0 else 15.0
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(t.latitude, t.longitude))
                                .zoom(zoom)
                                .build()
                        }
                    }
                    mapView
                },
                update = { },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 200.dp)
                    .zIndex(100f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        autoCenterEnabled = !autoCenterEnabled
                        if (autoCenterEnabled) hasCenteredOnUser = false
                    },
                    shape = CircleShape,
                    containerColor = if (autoCenterEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (autoCenterEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        if (autoCenterEnabled) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        "Автоцентрирование"
                    )
                }

                FloatingActionButton(
                    onClick = {
                        currentLocation?.let { hasCenteredOnUser = false }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.MyLocation, "Моё местоположение")
                }
            }

            Surface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
                    .zIndex(100f),
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Navigation, null,
                            Modifier.size(32.dp),
                            tint = if (hasDirectLine) ComposeColor(0xFFFF5722) else ComposeColor(0xFF2196F3)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    routePoints.isNotEmpty() -> formatRouteInfo(routeDistance, routeDuration, isOfflineRouting, hasDirectLine)
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

                    if (hasDirectLine && routePoints.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                Modifier.size(14.dp),
                                tint = ComposeColor(0xFFFF5722)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isLoadingRoute) "Строим маршрут..." else "Маршрут неполный — часть по прямой",
                                style = MaterialTheme.typography.bodySmall,
                                color = ComposeColor(0xFFFF5722)
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Транспорт:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = transportMode == "foot",
                            onClick = {
                                if (transportMode != "foot") {
                                    transportMode = "foot"
                                    routePoints = emptyList()
                                }
                            },
                            label = { Text("Пешком") },
                            leadingIcon = if (transportMode == "foot") {
                                { Icon(Icons.Default.DirectionsWalk, null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )

                        Spacer(Modifier.width(8.dp))

                        FilterChip(
                            selected = transportMode == "car",
                            onClick = {
                                if (transportMode != "car") {
                                    transportMode = "car"
                                    routePoints = emptyList()
                                }
                            },
                            label = { Text("Машина") },
                            leadingIcon = if (transportMode == "car") {
                                { Icon(Icons.Default.DirectionsCar, null, Modifier.size(16.dp)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
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
                            when {
                                isOfflineMode && isOfflineRouting -> "Оффлайн: карта + роутинг"
                                isOfflineMode -> "Оффлайн: карта, онлайн-роутинг"
                                isOfflineRouting -> "Онлайн-карта, оффлайн-роутинг"
                                else -> "Онлайн-режим"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onOpenOfflineMaps) {
                            Icon(Icons.Default.Settings, "Управление картами", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val results = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0].toDouble()
}

private fun routeToGeoJson(points: List<Pair<Double, Double>>): String {
    val coords = points.joinToString(",") { "[${it.second},${it.first}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

private fun formatDistance(m: Float): String = if (m < 1000) "${m.toInt()} м" else String.format("%.2f км", m / 1000)

private fun formatRouteInfo(distance: Double?, duration: Double?, isOffline: Boolean, hasDirectLine: Boolean): String {
    val dist = distance ?: return "Маршрут построен"
    
    val distStr = if (dist < 1000) "${dist.toInt()} м" else String.format("%.2f км", dist / 1000)
    val durStr = duration?.let { " • ${it.toInt() / 60} мин" } ?: ""
    val modeStr = when {
        isOffline && hasDirectLine -> " (оффлайн, частично)"
        isOffline -> " (оффлайн)"
        hasDirectLine -> " (частично)"
        else -> ""
    }
    
    return "$distStr$durStr$modeStr"
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