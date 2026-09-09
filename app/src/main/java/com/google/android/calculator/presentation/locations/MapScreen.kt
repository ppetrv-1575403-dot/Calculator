package com.google.android.calculator.presentation.locations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.google.android.calculator.utils.NetworkUtils
import com.google.android.calculator.utils.OfflineMapUtils
import com.google.android.calculator.utils.OfflineRegions
import com.google.android.calculator.utils.RoutingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    appContainer: AppContainer,
    locationId: String,
    onBack: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onEditLocation: (String) -> Unit,
    onOpenOfflineMaps: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<SavedLocation?>(null) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var deviceAzimuth by remember { mutableStateOf(0f) }
    var offlineMode by remember { mutableStateOf(false) }
    var offlineArchives by remember { mutableStateOf<List<File>>(emptyList()) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var userMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var targetMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var hasCenteredOnUser by remember { mutableStateOf(false) }
    var road by remember { mutableStateOf<Road?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var isRouteOnline by remember { mutableStateOf(true) }
    var hasLocationPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        hasLocationPermission = g[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(locationId) {
        target = appContainer.locationRepository.getLocationById(locationId)
        road = null
        if (!hasLocationPermission)
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) {
        OfflineMapUtils.initConfiguration(context)
        offlineArchives = OfflineMapUtils.findOfflineArchives()
    }

    // Автоцентрировка на пользователе при первой фиксации позиции
    LaunchedEffect(currentLocation) {
        val loc = currentLocation
        if (loc != null && !hasCenteredOnUser) {
            mapViewRef?.controller?.setZoom(17.0)
            mapViewRef?.controller?.setCenter(GeoPoint(loc.latitude, loc.longitude))
            hasCenteredOnUser = true
        }
    }

    // Гибридный роутинг: оффлайн через GraphHopper → онлайн через OSRM
    LaunchedEffect(target, offlineMode) {
        val t = target ?: return@LaunchedEffect
        if (offlineMode) {
            isRouteOnline = false
            return@LaunchedEffect
        }

        while (true) {
            val cur = currentLocation
            if (cur != null) {
                isLoadingRoute = true
                val route = withContext(Dispatchers.IO) {
                    try {
                        // Определяем регион для текущей позиции
                        val region = OfflineRegions.findRegionForPoint(cur.latitude, cur.longitude)

                        // Пробуем оффлайн-роутинг если граф доступен
                        val offlinePath = if (region != null && RoutingManager.isGraphAvailable(context, region.id)) {
                            RoutingManager.routeOffline(
                                context, region.id,
                                GeoPoint(cur.latitude, cur.longitude),
                                GeoPoint(t.latitude, t.longitude)
                            )
                        } else null

                        if (offlinePath != null) {
                            isRouteOnline = false
                            roadFromResponsePath(offlinePath)
                        } else if (NetworkUtils.isInternetAvailable(context)) {
                            // Fallback: онлайн через OSRM
                            isRouteOnline = true
                            val roadManager = OSRMRoadManager(context, "calculator_vault_app")
                            roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT)
                            val wayPoints = arrayListOf(
                                GeoPoint(cur.latitude, cur.longitude),
                                GeoPoint(t.latitude, t.longitude)
                            )
                            roadManager.getRoad(wayPoints)
                        } else {
                            isRouteOnline = false
                            null
                        }
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
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            val rm = FloatArray(9); val or = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rm, e.values)
                SensorManager.getOrientation(rm, or)
                deviceAzimuth = Math.toDegrees(or[0].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
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

    DisposableEffect(Unit) { onDispose { mapViewRef?.onDetach() } }

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
                    OfflineMapUtils.initConfiguration(ctx)
                    MapView(ctx).apply {
                        mapViewRef = this
                        setUseDataConnection(!offlineMode)
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(16.0)

                        // Центрируемся на цели, если позиция пользователя ещё не определена
                        target?.let { t ->
                            if (!hasCenteredOnUser) {
                                controller.setCenter(GeoPoint(t.latitude, t.longitude))
                            }
                        }

                        // Маркер пользователя — стрелка, вращающаяся по компасу
                        if (hasLocationPermission) {
                            val arrowBitmap = createUserArrowBitmap(ctx, Color.BLUE)
                            val userMarker = Marker(this)
                            userMarker.icon = BitmapDrawable(ctx.resources, arrowBitmap)
                            userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            userMarker.position = GeoPoint(0.0, 0.0)
                            userMarker.rotation = 0f
                            overlays.add(userMarker)
                            userMarkerRef = userMarker
                        }

                        // Маркер цели — красный пин
                        target?.let { t ->
                            val targetBitmap = createTargetPinBitmap(ctx)
                            val targetMarker = Marker(this)
                            targetMarker.icon = BitmapDrawable(ctx.resources, targetBitmap)
                            targetMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            targetMarker.position = GeoPoint(t.latitude, t.longitude)
                            targetMarker.title = t.name
                            targetMarker.setOnMarkerClickListener { _, _ ->
                                t.photoId?.let { onOpenPhoto(it) }
                                true
                            }
                            overlays.add(targetMarker)
                            targetMarkerRef = targetMarker
                        }
                    }
                },
                update = { mv ->
                    mv.setUseDataConnection(!offlineMode)

                    // Обновляем позицию и поворот стрелки пользователя
                    userMarkerRef?.let { marker ->
                        currentLocation?.let { loc ->
                            marker.position = GeoPoint(loc.latitude, loc.longitude)
                        }
                        marker.rotation = deviceAzimuth
                    }

                    val cur = currentLocation; val t = target

                    mv.overlays.removeAll { it is Polyline }

                    if (cur != null && t != null) {
                        if (road != null) {
                            val smoothedPoints = smoothPoints(road!!.mRouteHigh)

                            // Тень/подложка
                            val shadowLine = Polyline()
                            shadowLine.setPoints(smoothedPoints)
                            shadowLine.outlinePaint.color = Color.argb(80, 0, 0, 0)
                            shadowLine.outlinePaint.strokeWidth = 14f
                            shadowLine.outlinePaint.isAntiAlias = true
                            shadowLine.outlinePaint.strokeCap = Paint.Cap.ROUND
                            shadowLine.outlinePaint.strokeJoin = Paint.Join.ROUND
                            shadowLine.outlinePaint.style = Paint.Style.STROKE

                            // Основная синяя линия
                            val mainLine = Polyline()
                            mainLine.setPoints(smoothedPoints)
                            mainLine.outlinePaint.color = Color.BLUE
                            mainLine.outlinePaint.strokeWidth = 8f
                            mainLine.outlinePaint.isAntiAlias = true
                            mainLine.outlinePaint.strokeCap = Paint.Cap.ROUND
                            mainLine.outlinePaint.strokeJoin = Paint.Join.ROUND
                            mainLine.outlinePaint.style = Paint.Style.STROKE

                            // В начало списка: маркеры остаются поверх линий
                            mv.overlays.add(0, mainLine)
                            mv.overlays.add(0, shadowLine)
                        } else {
                            // Маршрут ещё не построен — серая пунктирная прямая
                            val line = Polyline()
                            line.setPoints(listOf(
                                GeoPoint(cur.latitude, cur.longitude),
                                GeoPoint(t.latitude, t.longitude)
                            ))
                            line.outlinePaint.color = Color.GRAY
                            line.outlinePaint.strokeWidth = 5f
                            line.outlinePaint.isAntiAlias = true
                            line.outlinePaint.strokeCap = Paint.Cap.ROUND
                            line.outlinePaint.strokeJoin = Paint.Join.ROUND
                            line.outlinePaint.style = Paint.Style.STROKE
                            line.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
                            mv.overlays.add(0, line)
                        }
                        mv.invalidate()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Кнопка центрирования на текущей позиции
            FloatingActionButton(
                onClick = {
                    currentLocation?.let { loc ->
                        mapViewRef?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 200.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.MyLocation, "Моё местоположение")
            }

            // Нижняя информационная панель
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
                                    road != null -> {
                                        val modeLabel = if (isRouteOnline) "пешком • онлайн" else "пешком • оффлайн"
                                        "${formatRouteInfo(road!!)} • $modeLabel"
                                    }
                                    isLoadingRoute -> "Строим маршрут..."
                                    !isRouteOnline && road == null -> {
                                        val d = distance?.let { formatDistance(it) } ?: "..."
                                        "$d по прямой • роутинг недоступен"
                                    }
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
                            if (offlineMode) Icons.Default.CloudOff else Icons.Default.CloudDone,
                            null, Modifier.size(20.dp),
                            tint = if (offlineMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (offlineMode) "Оффлайн-карты (${offlineArchives.size})" else "Онлайн-карты",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onOpenOfflineMaps) {
                            Icon(Icons.Default.Settings, "Управление картами", Modifier.size(20.dp))
                        }
                        Switch(checked = offlineMode, onCheckedChange = { offlineMode = it })
                    }
                }
            }

            // Индикатор построения маршрута
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

private fun formatDistance(m: Float): String = if (m < 1000) "${m.toInt()} м" else String.format("%.2f км", m / 1000)

private fun formatRouteInfo(route: Road): String {
    val lengthKm = route.mLength
    val durationMin = (route.mDuration / 60).toInt()
    return if (lengthKm < 1) {
        "${(lengthKm * 1000).toInt()} м • ${durationMin} мин"
    } else {
        String.format("%.2f км • %d мин", lengthKm, durationMin)
    }
}

// Сглаживание точек маршрута алгоритмом Chaikin's
private fun smoothPoints(points: List<GeoPoint>, iterations: Int = 2): List<GeoPoint> {
    if (points.size < 3) return points

    var result = points
    repeat(iterations) {
        val smoothed = mutableListOf<GeoPoint>()
        smoothed.add(result.first())

        for (i in 0 until result.size - 1) {
            val p0 = result[i]
            val p1 = result[i + 1]

            val q = GeoPoint(
                0.75 * p0.latitude + 0.25 * p1.latitude,
                0.75 * p0.longitude + 0.25 * p1.longitude
            )
            val r = GeoPoint(
                0.25 * p0.latitude + 0.75 * p1.latitude,
                0.25 * p0.longitude + 0.75 * p1.longitude
            )
            smoothed.add(q)
            smoothed.add(r)
        }

        smoothed.add(result.last())
        result = smoothed
    }
    return result
}

// Конвертация маршрута GraphHopper в osmdroid Road
private fun roadFromResponsePath(path: com.graphhopper.ResponsePath): Road {
    val road = Road()
    road.mLength = path.distance / 1000.0  // Double -> Double (метры в км)
    road.mDuration = path.time / 1000.0    // long -> Double (мс в сек)
    road.mRouteHigh = ArrayList(RoutingManager.pathToGeoPoints(path))
    road.mStatus = Road.STATUS_OK
    return road
}

// Стрелка пользователя (вращается по компасу)
private fun createUserArrowBitmap(context: Context, color: Int): Bitmap {
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

// Красный пин для конечной точки маршрута
private fun createTargetPinBitmap(context: Context): Bitmap {
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