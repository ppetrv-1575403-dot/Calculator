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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import java.util.concurrent.atomic.AtomicReference

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
    var connectionStartPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var connectionEndPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    
    var routeDistance by remember { mutableStateOf<Double?>(null) }
    var routeDuration by remember { mutableStateOf<Double?>(null) }
    var routeMode by remember { mutableStateOf<String>("") }
    
    var isLoadingRoute by remember { mutableStateOf(false) }
    var isOfflineRouting by remember { mutableStateOf(false) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var autoCenterEnabled by remember { mutableStateOf(false) }
    var transportMode by remember { mutableStateOf("foot") }
    var isOfflineMode by remember { mutableStateOf(false) }
    var currentRegionId by remember { mutableStateOf<String?>(null) }
    
    var isPanelExpanded by remember { mutableStateOf(false) }
    var panelHeight by remember { mutableStateOf(0.dp) }
    var routeRegenCounter by remember { mutableStateOf(0) }
    var routeVersion by remember { mutableStateOf(0) }
    
    val currentLocationRef = remember { AtomicReference<Location?>(null) }
    val routeStartRef = remember { AtomicReference<Pair<Double, Double>?>(null) }
    val executorRef = remember { AtomicReference(Executors.newSingleThreadExecutor()) }
    
    val density = LocalDensity.current
    
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var userSourceRef by remember { mutableStateOf<GeoJsonSource?>(null) }
    var userLayerRef by remember { mutableStateOf<SymbolLayer?>(null) }
    
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

    fun setupMapStyle(style: Style, logPrefix: String = "initial") {
        try {
            try { style.getLayer("route_layer")?.let { style.removeLayer(it) } } catch (_: Exception) {}
            try { style.getLayer("connection_start_layer")?.let { style.removeLayer(it) } } catch (_: Exception) {}
            try { style.getLayer("connection_end_layer")?.let { style.removeLayer(it) } } catch (_: Exception) {}
            try { style.getLayer("user_layer")?.let { style.removeLayer(it) } } catch (_: Exception) {}
            try { style.getLayer("target_layer")?.let { style.removeLayer(it) } } catch (_: Exception) {}
            try { style.getSource("route_source")?.let { style.removeSource(it) } } catch (_: Exception) {}
            try { style.getSource("connection_start_source")?.let { style.removeSource(it) } } catch (_: Exception) {}
            try { style.getSource("connection_end_source")?.let { style.removeSource(it) } } catch (_: Exception) {}
            try { style.getSource("user_source")?.let { style.removeSource(it) } } catch (_: Exception) {}
            try { style.getSource("target_source")?.let { style.removeSource(it) } } catch (_: Exception) {}
            
            style.addImage("user_arrow", createUserArrowBitmap(Color.BLUE))
            style.addImage("target_pin", createTargetPinBitmap())

            val topLayerId = style.layers.lastOrNull()?.id

            val connectionEndSource = GeoJsonSource("connection_end_source")
            style.addSource(connectionEndSource)
            
            val connectionEndLayer = LineLayer("connection_end_layer", "connection_end_source").withProperties(
                PropertyFactory.lineColor("#9E9E9E"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(0.9f)
            )

            val connectionStartSource = GeoJsonSource("connection_start_source")
            style.addSource(connectionStartSource)
            
            val connectionStartLayer = LineLayer("connection_start_layer", "connection_start_source").withProperties(
                PropertyFactory.lineColor("#1565C0"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(0.9f)
            )

            val routeSource = GeoJsonSource("route_source")
            style.addSource(routeSource)
            
            val routeLayer = LineLayer("route_layer", "route_source").withProperties(
                PropertyFactory.lineColor("#1565C0"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(1.0f)
            )

            if (topLayerId != null) {
                style.addLayerAbove(connectionEndLayer, topLayerId)
                style.addLayerAbove(connectionStartLayer, "connection_end_layer")
                style.addLayerAbove(routeLayer, "connection_start_layer")
                FileLogger.i("Map", "[$logPrefix] ✅ Route layers added ABOVE '$topLayerId'")
            } else {
                style.addLayer(connectionEndLayer)
                style.addLayer(connectionStartLayer)
                style.addLayer(routeLayer)
                FileLogger.i("Map", "[$logPrefix] ✅ Route layers added (no top layer)")
            }

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

            if (routePoints.isNotEmpty()) {
                routeSource.setGeoJson(pointsToLineString(routePoints))
            }
            if (connectionStartPoints.isNotEmpty()) {
                connectionStartSource.setGeoJson(pointsToLineString(connectionStartPoints))
            }
            if (connectionEndPoints.isNotEmpty()) {
                connectionEndSource.setGeoJson(pointsToLineString(connectionEndPoints))
            }
            
            currentLocation?.let { loc ->
                userSource.setGeoJson(
                    """{"type":"Feature","geometry":{"type":"Point","coordinates":[${loc.longitude},${loc.latitude}]}}"""
                )
            }
            
            FileLogger.i("MapDiag", "[$logPrefix] Style layers (${style.layers.size}): ${style.layers.joinToString(", ") { it.id }}")
            FileLogger.i("MapDiag", "[$logPrefix] Style sources: ${style.sources.joinToString(", ") { it.id }}")
            FileLogger.i("Map", "[$logPrefix] ✅ Style setup complete. Layers: ${style.layers.size}")
        } catch (e: Exception) {
            FileLogger.e("Map", "[$logPrefix] Failed to setup style", e)
        }
    }

    LaunchedEffect(locationId) {
        target = appContainer.locationRepository.getLocationById(locationId)
        if (!hasLocationPermission)
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    
    // Обновление пина цели при загрузке target
    LaunchedEffect(target, mapRef) {
        val t = target ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        
        val source = style.getSourceAs<GeoJsonSource>("target_source")
        if (source != null) {
            source.setGeoJson(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${t.longitude},${t.latitude}]}}"""
            )
            FileLogger.d("Map", "🎯 Target pin updated: ${t.name}")
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            currentRegionId?.let { regionId ->
                OfflineMapManager.stopServer(regionId)
            }
            OfflineRouter.unloadGraph()
            executorRef.get().shutdownNow()
        }
    }

    LaunchedEffect(target, transportMode, routeRegenCounter) {
        val t = target ?: return@LaunchedEffect
        FileLogger.i("Route", "Routing loop started for target: ${t.name}, transport=$transportMode")
        
        val region = OfflineRegions.findRegionForPoint(t.latitude, t.longitude)
            ?: currentLocationRef.get()?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }
        
        val offlineActive = if (region != null && OfflineRouter.isGraphDownloaded(context, region.id)) {
            val loadError = withContext(Dispatchers.IO) {
                OfflineRouter.loadGraph(context, region.id)
            }
            
            if (loadError != null) {
                isOfflineRouting = false
                FileLogger.e("Route", "Graph load failed: $loadError")
                false
            } else {
                isOfflineRouting = true
                FileLogger.i("Route", "Offline routing active")
                true
            }
        } else {
            isOfflineRouting = false
            FileLogger.i("Route", "Using online routing (no graph)")
            false
        }
        
        while (true) {
            try {
                val cur = currentLocationRef.get()
                val currentTransport = transportMode
                val useOffline = offlineActive
                
                if (cur == null) {
                    FileLogger.w("Route", "Waiting for GPS location...")
                } else {
                    isLoadingRoute = true
                    
                    var points: List<Pair<Double, Double>>? = null
                    var distance: Double? = null
                    var duration: Double? = null
                    var mode: String = ""
                    
                    if (useOffline) {
                        val vehicle = if (currentTransport == "car") "car" else "foot"
                        
                        val future = executorRef.get().submit<Pair<List<Pair<Double, Double>>?, Double?>?> {
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
                            val routeResult = future.get(10, TimeUnit.SECONDS)
                            val pts: List<Pair<Double, Double>>? = routeResult?.first
                            val dist: Double? = routeResult?.second
                            
                            if (pts != null && pts.isNotEmpty()) {
                                points = pts
                                distance = dist
                                duration = dist?.let { d ->
                                    if (currentTransport == "car") d / (50.0 / 3.6) else d / (5.0 / 3.6)
                                }
                                mode = "offline"
                                FileLogger.i("Route", "✅ Offline route: ${pts.size} points")
                            }
                        } catch (e: TimeoutException) {
                            future.cancel(true)
                            FileLogger.e("Route", "⏰ Route timeout (10s)! Recreating executor...")
                            val old = executorRef.getAndSet(Executors.newSingleThreadExecutor())
                            old.shutdownNow()
                        } catch (e: Exception) {
                            FileLogger.e("Route", "❌ Route failed: ${e.message}", e)
                        }
                    } else {
                        val route = withContext(Dispatchers.IO) {
                            try {
                                val roadManager = OSRMRoadManager(context, "calculator_vault_app")
                                when (currentTransport) {
                                    "foot" -> roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                                    "car" -> roadManager.setMean(OSRMRoadManager.MEAN_BY_CAR)
                                    else -> roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                                }
                                val wayPoints = arrayListOf(
                                    GeoPoint(cur.latitude, cur.longitude),
                                    GeoPoint(t.latitude, t.longitude)
                                )
                                roadManager.getRoad(wayPoints)
                            } catch (e: Exception) { null }
                        }
                        
                        if (route != null && route.mRouteHigh.isNotEmpty()) {
                            points = route.mRouteHigh.map { Pair(it.latitude, it.longitude) }
                            distance = route.mLength * 1000
                            duration = route.mDuration
                            mode = "online"
                            FileLogger.i("Route", "✅ Online route: ${points.size} points")
                        }
                    }
                    
                    if (points != null && points.isNotEmpty()) {
                        routePoints = points
                        
                        val firstPoint = points.first()
                        val lastPoint = points.last()
                        
                        val distFromUser = distanceBetween(cur.latitude, cur.longitude, firstPoint.first, firstPoint.second)
                        if (distFromUser > 0) {
                            connectionStartPoints = listOf(
                                Pair(cur.latitude, cur.longitude),
                                firstPoint
                            )
                        } else {
                            connectionStartPoints = emptyList()
                        }
                        
                        val distToTarget = distanceBetween(lastPoint.first, lastPoint.second, t.latitude, t.longitude)
                        if (distToTarget > 0) {
                            connectionEndPoints = listOf(lastPoint, Pair(t.latitude, t.longitude))
                        } else {
                            connectionEndPoints = emptyList()
                        }
                        
                        routeMode = mode
                        routeDistance = distance
                        routeDuration = duration
                    } else {
                        connectionStartPoints = emptyList()
                        connectionEndPoints = listOf(
                            Pair(cur.latitude, cur.longitude),
                            Pair(t.latitude, t.longitude)
                        )
                        distance = distanceBetween(cur.latitude, cur.longitude, t.latitude, t.longitude)
                        duration = distance?.let { d ->
                            if (currentTransport == "car") d / (50.0 / 3.6) else d / (5.0 / 3.6)
                        }
                        routeMode = "direct"
                        routeDistance = distance
                        routeDuration = duration
                    }
                    
                    isLoadingRoute = false
                    routeVersion++
                    FileLogger.d("Route", "♥ loop alive: cur=${"%.5f".format(cur.latitude)}, ${"%.5f".format(cur.longitude)}, mode=$routeMode, pts=${routePoints.size}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e("Route", "❌ Loop iteration crashed, continuing", e)
            }
            delay(5000)
        }
    }

    LaunchedEffect(routePoints, connectionStartPoints, connectionEndPoints, routeMode, transportMode, routeVersion, routeRegenCounter) {
      try {
        val map = mapRef
        val style = map?.style
        if (map == null || style == null) {
            FileLogger.w("Route", "⚠️ Map or Style is NULL, skipping")
            return@LaunchedEffect
        }
        if (style.getSource("route_source") == null) {
            FileLogger.w("Route", "⚠️ Sources missing! Re-adding...")
            setupMapStyle(style, "recovery")
            return@LaunchedEffect
        }

        val routeColor = when {
            routeMode == "direct" -> "#9E9E9E"
            transportMode == "car" -> "#E65100"
            else -> "#1565C0"
        }

        val routeSource = style.getSourceAs<GeoJsonSource>("route_source")
        if (routePoints.isNotEmpty()) {
            routeSource?.setGeoJson(pointsToLineString(routePoints))
            FileLogger.i("Route", "✅ Route APPLIED [$transportMode/$routeColor]: ${routePoints.size} pts, first=${routePoints.first()}, last=${routePoints.last()}")
        } else {
            routeSource?.setGeoJson("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}""")
        }
        routeStartRef.set(routePoints.firstOrNull())
        
        (style.getLayer("route_layer") as? LineLayer)?.setProperties(PropertyFactory.lineColor(routeColor))
        (style.getLayer("connection_start_layer") as? LineLayer)?.setProperties(PropertyFactory.lineColor(routeColor))

        val startSource = style.getSourceAs<GeoJsonSource>("connection_start_source")
        if (connectionStartPoints.isNotEmpty()) {
            startSource?.setGeoJson(pointsToLineString(connectionStartPoints))
        } else {
            startSource?.setGeoJson("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}""")
        }

        val endSource = style.getSourceAs<GeoJsonSource>("connection_end_source")
        if (connectionEndPoints.isNotEmpty()) {
            endSource?.setGeoJson(pointsToLineString(connectionEndPoints))
            FileLogger.i("Route", "✅ Connection END (gray) APPLIED: ${connectionEndPoints.size} pts")
        } else {
            endSource?.setGeoJson("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}""")
        }

        map.triggerRepaint()
      } catch (e: Exception) {
        FileLogger.e("Route", "❌ Update effect failed", e)
      }
    }
    
    LaunchedEffect(currentLocation, routeRegenCounter) {
        currentLocation?.let { loc ->
            currentLocationRef.set(loc)
            
            val style = mapRef?.style
            val source = style?.getSourceAs<GeoJsonSource>("user_source")
            source?.setGeoJson(
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
            
            val routeStart = routeStartRef.get()
            if (routeStart != null) {
                val distToRouteStart = distanceBetween(
                    loc.latitude, loc.longitude,
                    routeStart.first, routeStart.second
                )
                connectionStartPoints = if (distToRouteStart > 0) {
                    listOf(Pair(loc.latitude, loc.longitude), routeStart)
                } else {
                    emptyList()
                }
            }
        }
    }

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

    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { currentLocation = l }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, listener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 5f, listener)
        } catch (e: Exception) { }
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
                    IconButton(onClick = onOpenOfflineMaps) { Icon(Icons.Default.Map, "Оффлайн-карты") }
                    IconButton(onClick = { target?.let { onEditLocation(it.id) } }) { Icon(Icons.Default.Edit, "Редактировать") }
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
                            ?: currentLocationRef.get()?.let { OfflineRegions.findRegionForPoint(it.latitude, it.longitude) }

                        val tileSourceUrl = if (region != null) {
                            currentRegionId = region.id
                            OfflineMapManager.getTileSource(ctx, region.id)
                        } else {
                            "https://tiles.openfreemap.org/planet"
                        }

                        isOfflineMode = tileSourceUrl.contains("localhost")
                        FileLogger.i("MapDiag", "Tile source URL: $tileSourceUrl")

                        val styleBuilder = Style.Builder().fromJson(MapStyleUtils.getBasicStyleJson(tileSourceUrl))

                        map.setStyle(styleBuilder) { style ->
                            setupMapStyle(style, "initial")
                        }

                        map.addOnCameraIdleListener {
                            val pos = map.cameraPosition
                            FileLogger.d("MapDiag", "📍 Camera: zoom=${"%.2f".format(pos.zoom)}, target=${pos.target}")
                        }

                        mapView.addOnDidFinishLoadingStyleListener {
                            FileLogger.i("MapDiag", "🎨 Style finished loading")
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
                    .padding(end = 16.dp, bottom = panelHeight + 12.dp)
                    .zIndex(100f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        autoCenterEnabled = !autoCenterEnabled
                        if (autoCenterEnabled) {
                            currentLocationRef.get()?.let { loc ->
                                mapRef?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.5),
                                    600
                                )
                            }
                            hasCenteredOnUser = true
                        }
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
                        currentLocationRef.get()?.let { loc ->
                            mapRef?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16.5),
                                600
                            )
                            hasCenteredOnUser = true
                        }
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        panelHeight = with(density) { coords.size.height.toDp() }
                    }
                    .zIndex(100f),
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Navigation, null,
                            Modifier.size(24.dp),
                            tint = androidx.compose.ui.graphics.Color(0xFF1565C0)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    routeDistance != null -> formatRouteInfo(routeDistance, routeDuration, routeMode)
                                    isLoadingRoute -> "Строим маршрут..."
                                    else -> distance?.let { formatDistance(it) } ?: "Определение расстояния..."
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            if (isPanelExpanded) {
                                target?.let {
                                    Text(
                                        "%.5f, %.5f".format(it.latitude, it.longitude),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (isPanelExpanded && target?.photoId != null) {
                            IconButton(onClick = { target?.photoId?.let { onOpenPhoto(it) } }) {
                                Icon(Icons.Default.Image, "Фото", Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = {
                            FileLogger.i("Route", "🔄 Regenerate requested by user")
                            routeRegenCounter++
                        }) {
                            Icon(Icons.Default.Refresh, "Перегенерировать маршрут", Modifier.size(20.dp))
                        }
                        IconButton(onClick = { isPanelExpanded = !isPanelExpanded }) {
                            Icon(
                                if (isPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                if (isPanelExpanded) "Свернуть" else "Развернуть",
                                Modifier.size(22.dp)
                            )
                        }
                    }

                    if (isPanelExpanded) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Транспорт:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))

                            FilterChip(
                                selected = transportMode == "foot",
                                onClick = {
                                    if (transportMode != "foot") {
                                        transportMode = "foot"
                                    }
                                },
                                label = { Text("Пешком", style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = { Icon(Icons.Default.DirectionsWalk, null, Modifier.size(16.dp)) },
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
                                    }
                                },
                                label = { Text("Машина", style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = { Icon(Icons.Default.DirectionsCar, null, Modifier.size(16.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isOfflineMode) Icons.Default.CloudOff else Icons.Default.CloudDone,
                                null, Modifier.size(18.dp),
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
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onOpenOfflineMaps) {
                                Icon(Icons.Default.Settings, "Управление картами", Modifier.size(18.dp))
                            }
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

private fun pointsToLineString(points: List<Pair<Double, Double>>): String {
    val coords = points.joinToString(",") { "[${it.second},${it.first}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

private fun formatDistance(m: Float): String = if (m < 1000) "${m.toInt()} м" else String.format("%.2f км", m / 1000)

private fun formatRouteInfo(distance: Double?, duration: Double?, mode: String): String {
    val dist = distance ?: return "Маршрут построен"
    val distStr = if (dist < 1000) "${dist.toInt()} м" else String.format("%.2f км", dist / 1000)
    val durStr = duration?.let { " • ${it.toInt() / 60} мин" } ?: ""
    val modeStr = when (mode) {
        "offline" -> " (оффлайн)"
        "online" -> " (онлайн)"
        "direct" -> " (по прямой)"
        else -> ""
    }
    return "$distStr$durStr$modeStr"
}

private fun createUserArrowBitmap(color: Int): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val outlinePaint = Paint().apply { this.color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    val fillPaint = Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = true }
    val outlinePath = Path().apply {
        moveTo(size / 2f, -4f); lineTo(size * 0.85f, size * 0.95f)
        lineTo(size / 2f, size * 0.7f); lineTo(size * 0.15f, size * 0.95f); close()
    }
    val fillPath = Path().apply {
        moveTo(size / 2f, 2f); lineTo(size * 0.8f, size * 0.9f)
        lineTo(size / 2f, size * 0.65f); lineTo(size * 0.2f, size * 0.9f); close()
    }
    canvas.drawPath(outlinePath, outlinePaint)
    canvas.drawPath(fillPath, fillPaint)
    return bitmap
}

private fun createTargetPinBitmap(): Bitmap {
    val size = 80
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val outlinePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    val fillPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }
    canvas.drawCircle(size / 2f, size / 3f, size / 4f + 4f, outlinePaint)
    canvas.drawCircle(size / 2f, size / 3f, size / 4f, fillPaint)
    val path = Path().apply {
        moveTo(size / 2f - size / 5f, size / 3f + size / 6f)
        lineTo(size / 2f, size.toFloat() - 4f)
        lineTo(size / 2f + size / 5f, size / 3f + size / 6f); close()
    }
    canvas.drawPath(path, fillPaint)
    val dotPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    canvas.drawCircle(size / 2f, size / 3f, size / 10f, dotPaint)
    return bitmap
}