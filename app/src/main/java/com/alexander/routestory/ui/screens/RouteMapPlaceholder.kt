package com.alexander.routestory.ui.screens

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.utils.MapAvailability
import com.alexander.routestory.utils.MapTiles
import com.alexander.routestory.utils.OsmTileLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.max
import kotlin.math.min

private const val ROUTE_SOURCE = "routestory-route"
private const val ROUTE_LAYER = "routestory-route-line"
private const val PHOTO_SOURCE = "routestory-photos"
private const val PHOTO_LAYER = "routestory-photos-layer"
private const val ME_SOURCE = "routestory-me"
private const val ME_LAYER = "routestory-me-dot"
private const val ROUTE_WIDTH = 5f
private const val MARKER_RADIUS = 6f
private const val MARKER_STROKE = 2f
private const val TAP_RADIUS_PX = 30f
/** Raster size of the camera glyph and its size on screen (px). */
private const val PHOTO_GLYPH_PX = 48f
private const val PHOTO_GLYPH_TARGET_PX = 24f
private const val BOUNDS_PADDING_PX = 64
private const val STYLE_TIMEOUT_MS = 8_000L
/**
 * Sprint 65: the builder hold is a single comfortable press, not a 2 s wait.
 * 900 ms is inside the 800-1200 ms "feels instant" band; the progress ring
 * ([RouteMapCanvas] hold feedback) and the hint text stay as they are.
 * Cancelled by a drag past the slop, a second finger, or a lift - unchanged.
 */
private const val LONG_PRESS_HOLD_MS = 900L
/** Sprint 62: a slop of 14 dp swallowed real presses; 10 dp still survives a shaky finger. */
private const val LONG_PRESS_TOUCH_SLOP_DP = 10f
/**
 * Sprint 65: builder waypoint hit radius. A press is "on a marker" (delete / drag)
 * well before it reaches the slop, so a marker tap is never swallowed by the
 * long-press-add path. Bigger than the drawn 14 px circle, smaller than the slop.
 */
private const val WAYPOINT_HIT_DP = 26f

/**
 * Sprint 62: immutable camera snapshot used by the pointer handlers.
 *
 * `pointerInput(Unit)` keeps the code of the very first composition, so converting screen
 * coordinates with the plain viewX/viewY/zoom vals returned the position computed for the camera
 * of that first frame - after a zoom or a pan a long-press produced coordinates far away from the
 * finger. Handlers read the snapshot through [rememberUpdatedState] instead, and can also write
 * the snapshot back into the camera state to freeze the viewport.
 */
private data class CanvasCamera(
    val viewX: Double,
    val viewY: Double,
    val zoom: Float,
    val worldPixels: Double
) {
    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset = Offset(
        (width / 2.0 + ((lon + 180.0) / 360.0 - viewX) * worldPixels).toFloat(),
        (height / 2.0 + (OsmTileLayer.mercYTiles(lat) - viewY) * worldPixels).toFloat()
    )

    fun toLatLon(x: Float, y: Float, width: Float, height: Float): Pair<Double, Double> {
        val lon = viewX * 360.0 - 180.0 + (x - width / 2f) / worldPixels
        val lat = Math.toDegrees(
            kotlin.math.atan(
                kotlin.math.sinh(Math.PI * (1.0 - 2.0 * (viewY + (y - height / 2f) / worldPixels)))
            )
        )
        return lat to lon
    }
}

/**
 * Route preview on top of MapLibre + OpenFreeMap tiles.
 *
 * Falls back to a plain canvas rendering when the map must not or cannot be used. The native
 * renderer is only started after the first layout pass and every call is guarded by the view
 * destroyed state, because MapLibre itself does not check it.
 */
@Composable
fun RouteMapPlaceholder(
    points: List<TrackPoint>,
    plannedPoints: List<TrackPoint> = emptyList(),
    waypointMarkers: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
    photoPositions: List<Pair<Double, Double>> = emptyList(),
    onPhotoTap: ((Int) -> Unit)? = null,
    followLastPoint: Boolean = false,
    currentPosition: Pair<Double, Double>? = null,
    locateTick: Int = 0,
    onLocate: (() -> Unit)? = null,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onMapLongPress: ((Double, Double) -> Unit)? = null,
    /**
     * Sprint 65: builder point editing on the map itself.
     *  - [onWaypointTap] - a plain tap on an existing marker (the caller confirms the delete);
     *  - [onWaypointMove] - index + new lat/lon, fired once when the finger LIFTS after a
     *    long-press drag on a marker, so the routing debounce sees a single change.
     * Both take priority over "add a point": a press that lands on a marker never adds.
     */
    onWaypointTap: ((Int) -> Unit)? = null,
    onWaypointMove: ((Int, Double, Double) -> Unit)? = null,
    /** Sprint 61: where to look before any route/fix exists (builder opening screen). */
    initialCentre: Pair<Double, Double>? = null,
    /** Sprint 62: index inside [waypointMarkers] painted red - the route start (waypoint #1). */
    startMarkerIndex: Int = -1,
    /**
     * Sprint 62: the builder renders its own GPS status (and a "turn GPS on" dialog), so it
     * switches this hint off to avoid a second "GPS is off" message on top of the dialog.
     */
    warnNoFixOnLocate: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Sprint 12: MapLibre stays blank on the target device even with a healthy network,
    // so FORCE_OSM_CANVAS routes every screen through the OsmTileLayer Canvas path.
    // Decided once per screen visit: swapping renderers mid-flight would destroy a live
    // MapView while its renderer is still drawing.
    val mapAllowed = remember {
        MapAvailability.canUseMap(context.applicationContext) && !MapTiles.FORCE_OSM_CANVAS
    }
    var useCanvas by remember { mutableStateOf(true) }
    var online by remember { mutableStateOf(true) }
    var styleOk by remember { mutableStateOf<Boolean?>(null) }
    val onStyleResult = remember { { ok: Boolean, reason: String? ->
        Log.i("RouteMap", "style ok=$ok reason=$reason")
        styleOk = ok
    } }

    // Logged once per screen visit, not on every recomposition (points arrive every 500 ms
    // while recording and would spam logcat otherwise).
    LaunchedEffect(Unit) {
        Log.i("RouteMap", "map path: canUseMap=$mapAllowed canvas=true (forced)")
    }

    // ConnectivityManager alone is not enough: the emulator can report the radio as
    // connected while tile.openstreetmap.org is actually unreachable. The real check is a
    // GET request to the tile server.
    LaunchedEffect(Unit) {
        online = withContext(Dispatchers.IO) { MapTiles.canReachTileServer() }
        Log.i("RouteMap", "tile server reach=$online")
    }

    Box(modifier = modifier) {
        if (useCanvas) {
            RouteMapCanvas(
                points = points,
                plannedPoints = plannedPoints,
                waypointMarkers = waypointMarkers,
                photoPositions = photoPositions,
                onPhotoTap = onPhotoTap,
                modifier = Modifier.fillMaxSize(),
                currentPosition = currentPosition,
                followLastPoint = followLastPoint,
                locateTick = locateTick,
                onMapTap = onMapTap,
                onMapLongPress = onMapLongPress,
                onWaypointTap = onWaypointTap,
                onWaypointMove = onWaypointMove,
                initialCentre = initialCentre,
                startMarkerIndex = startMarkerIndex,
                mapLoadingText = if (!online) null else stringResource(R.string.loading_map)
            )
            when {
                !mapAllowed && !MapTiles.FORCE_OSM_CANVAS -> MapNotice(
                    text = stringResource(R.string.map_unavailable),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                !online -> MapNotice(
                    text = stringResource(R.string.offline_region),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        } else {
            MapLibreRouteMap(
                points = points,
                photoPositions = photoPositions,
                onPhotoTap = onPhotoTap,
                followLastPoint = followLastPoint,
                currentPosition = currentPosition,
                online = online,
                onCreationFailed = { useCanvas = true },
                onStyleResult = onStyleResult,
                modifier = Modifier.fillMaxSize()
            )
        }
        onLocate?.let {
            val gpsDisabledText = stringResource(R.string.gps_disabled)
            val myLocationText = stringResource(R.string.map_my_location)
            var flashing by remember { mutableStateOf(false) }
            val fabColor by animateColorAsState(
                targetValue = if (flashing) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = tween(durationMillis = 150),
                label = "locate-fab-color"
            )
            FloatingActionButton(
                onClick = {
                    it()
                    // Explicit 0.5 s white flash so the tap is unmistakable.
                    flashing = true
                    scope.launch {
                        delay(500)
                        flashing = false
                    }
                    // No fix yet -> tell the user instead of silently doing nothing.
                    if (currentPosition == null && warnNoFixOnLocate) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = gpsDisabledText,
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = fabColor,
                contentColor = if (flashing) Color.Black else MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 12.dp,
                    focusedElevation = 8.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = myLocationText,
                    tint = if (flashing) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        SnackbarHost(snackbarHostState)
    }
}

@Composable
private fun MapNotice(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp)
        )
    }
}

/** Mutable bridge between Compose state and the MapView instance. */
private class MapHolder {
    var mapView: MapView? = null
    var map: MapLibreMap? = null
    var routeSource: GeoJsonSource? = null
    var photosSource: GeoJsonSource? = null
    var meSource: GeoJsonSource? = null
    var photos: List<Pair<Double, Double>> = emptyList()
    var me: Pair<Double, Double>? = null
    var onPhotoTap: ((Int) -> Unit)? = null
    var lastBoundsKey: String? = null
    var userPanned = false
    var styleReady = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun start() {
        if (destroyed || started) return
        val view = mapView ?: return
        if (runCatching { view.isDestroyed() }.getOrDefault(true)) return
        started = true
        runCatching { view.onStart() }
    }

    fun resume() {
        if (destroyed) return
        val view = mapView ?: return
        if (runCatching { view.isDestroyed() }.getOrDefault(true)) return
        if (!started) start()
        if (resumed) return
        resumed = true
        runCatching { view.onResume() }
    }

    fun pause() {
        if (destroyed || !resumed) return
        resumed = false
        val view = mapView ?: return
        if (runCatching { view.isDestroyed() }.getOrDefault(true)) return
        runCatching { view.onPause() }
    }

    fun stop() {
        if (destroyed || !started) return
        started = false
        resumed = false
        val view = mapView ?: return
        if (runCatching { view.isDestroyed() }.getOrDefault(true)) return
        runCatching { view.onStop() }
    }

    fun destroy() {
        if (destroyed) {
            clearReferences()
            return
        }
        destroyed = true
        started = false
        resumed = false
        val view = mapView
        if (view != null && runCatching { view.isDestroyed() }.getOrDefault(true)) {
            clearReferences()
            return
        }
        runCatching { view?.onPause() }
        runCatching { view?.onStop() }
        runCatching { view?.onDestroy() }
        clearReferences()
    }

    /** True only when it is safe to touch the map or its view. */
    fun isAlive(): Boolean {
        if (destroyed) return false
        val view = mapView ?: return false
        return runCatching { !view.isDestroyed() }.getOrDefault(false)
    }

    private fun clearReferences() {
        map = null
        mapView = null
        routeSource = null
        photosSource = null
        meSource = null
    }

    fun attachStyle(style: Style, lineColor: Int, markerColor: Int, meColor: Int) {
        // Все источники должны быть зарегистрированы ДО добавления любого слоя, который
        // на них ссылается. Иначе нативный движок MapLibre/Mapbox отклоняет слой с ошибкой
        // "layer references source that is not defined", весь стиль не применяется и остаётся
        // только background-цвет (песочный фон без тайлов). Исключение гасится обёрткой
        // runCatching в map.setStyle, поэтому карта молча остаётся пустой.
        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
        val route = GeoJsonSource(ROUTE_SOURCE, empty)
        val photos = GeoJsonSource(PHOTO_SOURCE, empty)
        val me = GeoJsonSource(ME_SOURCE, empty)
        style.addSource(route)
        style.addSource(photos)
        style.addSource(me)
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(lineColor),
                PropertyFactory.lineWidth(ROUTE_WIDTH)
            )
        )
        style.addLayer(
            CircleLayer(ME_LAYER, ME_SOURCE).withProperties(
                PropertyFactory.circleColor(meColor),
                PropertyFactory.circleRadius(MARKER_RADIUS + 2f),
                PropertyFactory.circleStrokeWidth(MARKER_STROKE + 1f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)
            )
        )
        style.addLayer(
            CircleLayer(PHOTO_LAYER, PHOTO_SOURCE).withProperties(
                PropertyFactory.circleColor(markerColor),
                PropertyFactory.circleRadius(MARKER_RADIUS),
                PropertyFactory.circleStrokeWidth(MARKER_STROKE),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)
            )
        )
        routeSource = route
        photosSource = photos
        meSource = me
        updateMe()
    }

    fun updateMe() {
        val source = meSource ?: return
        val pos = me
        runCatching {
            source.setGeoJson(
                if (pos == null) FeatureCollection.fromFeatures(emptyArray<Feature>())
                else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(pos.second, pos.first)))
            )
        }
    }

    fun updateRoute(points: List<TrackPoint>) {
        val source = routeSource ?: return
        val line = if (points.size >= 2) {
            LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        } else {
            null
        }
        runCatching {
            source.setGeoJson(
                if (line == null) {
                    FeatureCollection.fromFeatures(emptyArray<Feature>())
                } else {
                    FeatureCollection.fromFeature(Feature.fromGeometry(line))
                }
            )
        }
    }

    fun updatePhotos() {
        val source = photosSource ?: return
        val features = photos.map { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) }
        runCatching { source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray())) }
    }

    /** Returns true when the tap hit a photo marker. */
    fun handleTap(map: MapLibreMap, latLng: LatLng): Boolean {
        val callback = onPhotoTap ?: return false
        val projection = map.projection
        val tapped = projection.toScreenLocation(latLng)
        val index = photos.indices.firstOrNull { i ->
            val marker = projection.toScreenLocation(LatLng(photos[i].first, photos[i].second))
            val dx = tapped.x - marker.x
            val dy = tapped.y - marker.y
            dx * dx + dy * dy <= TAP_RADIUS_PX * TAP_RADIUS_PX
        }
        if (index == null) return false
        callback(index)
        return true
    }

    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean, currentPosition: Pair<Double, Double>? = null) {
        val mePos = currentPosition ?: me
        if (mePos != null && (followLastPoint || points.size < 2)) {
            if (followLastPoint && userPanned) return
            runCatching { map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mePos.first, mePos.second), MapTiles.TRACKING_ZOOM)) }
            if (points.size < 2) { lastBoundsKey = null; return }
        }
        val last = points.lastOrNull() ?: return
        if (followLastPoint) {
            if (userPanned) return
            runCatching {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(last.lat, last.lon),
                        MapTiles.TRACKING_ZOOM
                    )
                )
            }
            return
        }
        if (points.size < 2) return
        val key = boundsKey(points)
        if (key == lastBoundsKey) return
        lastBoundsKey = key
        runCatching {
            val bounds = LatLngBounds.Builder()
            points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds.build(), BOUNDS_PADDING_PX)
            )
        }
    }
}

private fun boundsKey(points: List<TrackPoint>): String {
    val first = points.first()
    val last = points.last()
    return "${points.size}:${first.lat},${first.lon}:${last.lat},${last.lon}"
}

@Composable
private fun MapLibreRouteMap(
    points: List<TrackPoint>,
    photoPositions: List<Pair<Double, Double>>,
    onPhotoTap: ((Int) -> Unit)?,
    followLastPoint: Boolean,
    currentPosition: Pair<Double, Double>?,
    online: Boolean,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onMapLongPress: ((Double, Double) -> Unit)? = null,
    onCreationFailed: () -> Unit,
    onStyleResult: (Boolean, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val markerColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val meColor = MaterialTheme.colorScheme.secondary.toArgb()
    val holder = remember { MapHolder() }
    holder.photos = photoPositions
    holder.me = currentPosition
    holder.onPhotoTap = onPhotoTap
    var laidOut by remember { mutableStateOf(false) }
    var styleTimedOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(STYLE_TIMEOUT_MS)
        if (!holder.styleReady && holder.isAlive()) styleTimedOut = true
    }

    // Renderer starts only after the first layout pass; guarded by destroyed state.
    LaunchedEffect(laidOut) {
        if (laidOut) {
            holder.start()
            holder.resume()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                holder.destroy()
                val options = MapLibreMapOptions.createFromAttributes(ctx)
                    .textureMode(true)
                    .compassEnabled(false)
                    .logoEnabled(false)
                    .attributionEnabled(true)
                val created = runCatching {
                    MapAvailability.markAttemptStarted(appContext)
                    MapView(ctx, options)
                }.getOrElse {
                    onCreationFailed()
                    FrameLayout(ctx)
                }
                if (created !is MapView) return@AndroidView created
                created.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                holder.mapView = created
                holder.map = null
                holder.routeSource = null
                holder.photosSource = null
                holder.meSource = null
                holder.lastBoundsKey = null
                holder.userPanned = false
                holder.styleReady = false
                // Real MapLibre failure callbacks instead of silent beige background.
                created.addOnDidFailLoadingMapListener(object : MapView.OnDidFailLoadingMapListener {
                    override fun onDidFailLoadingMap(reason: String) {
                        Log.e("RouteMap", "MapLibre failed to load map: $reason")
                        onCreationFailed()
                    }
                })
                created.addOnDidFinishLoadingMapListener(object : MapView.OnDidFinishLoadingMapListener {
                    override fun onDidFinishLoadingMap() {
                        Log.i("RouteMap", "MapLibre finished loading map (backend ready)")
                    }
                })
                val styleJson = MapTiles.loadStyleJson(appContext)
                val builder = styleJson?.let { Style.Builder().fromJson(it) }
                if (builder == null) {
                    Log.e("RouteMap", "Failed to load OSM style asset, falling back to canvas")
                    onCreationFailed()
                    return@AndroidView created
                }
                created.getMapAsync { map ->
                    if (!holder.isAlive()) return@getMapAsync
                    holder.map = map
                    Log.i("RouteMap", "MapLibre backend: ${MapLibre.getInstance(appContext)}")
                    map.addOnMapClickListener { latLng ->
                        if (holder.map !== map || !holder.isAlive()) return@addOnMapClickListener false
                        if (runCatching { holder.handleTap(map, latLng) }.getOrDefault(false)) true
                        else {
                            onMapTap?.invoke(latLng.latitude, latLng.longitude)
                            onMapTap != null
                        }
                    }
                    map.addOnMapLongClickListener { latLng ->
                        if (holder.map !== map || !holder.isAlive()) false
                        else {
                            onMapLongPress?.invoke(latLng.latitude, latLng.longitude)
                            onMapLongPress != null
                        }
                    }
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            holder.userPanned = true
                        }
                    }
                    runCatching {
                        map.setStyle(
                            builder,
                            object : Style.OnStyleLoaded {
                                override fun onStyleLoaded(style: Style) {
                                    if (!holder.isAlive() || holder.map !== map) return
                                    runCatching {
                                        holder.attachStyle(style, lineColor, markerColor, meColor)
                                        holder.styleReady = true
                                        onStyleResult(true, null)
                                        MapAvailability.markReady(appContext)
                                        holder.updateRoute(points)
                                        holder.updatePhotos()
                                        holder.updateMe()
                                        holder.updateCamera(map, points, followLastPoint, holder.me)
                                    }.onFailure { e ->
                                        Log.e("RouteMap", "Failed to apply style/layers", e)
                                        onStyleResult(false, e.message)
                                        onCreationFailed()
                                    }
                                }
                            }
                        )
                    }.onFailure { e ->
                        Log.e("RouteMap", "Failed to load map style", e)
                        onStyleResult(false, e.message)
                        onCreationFailed()
                    }
                }
                created
            },
            update = { holder.updateRoute(points) },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) laidOut = true
                },
            onRelease = { holder.destroy() }
        )
        if (!online || styleTimedOut) {
            MapNotice(
                text = stringResource(R.string.map_no_net),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    LaunchedEffect(points) {
        holder.updateRoute(points)
        val map = holder.map
        if (map != null && holder.styleReady && holder.isAlive()) {
            holder.updateCamera(map, points, followLastPoint, holder.me)
        }
    }
    LaunchedEffect(photoPositions) {
        holder.photos = photoPositions
        holder.updatePhotos()
    }
    LaunchedEffect(currentPosition) {
        holder.me = currentPosition
        holder.updateMe()
        val map = holder.map
        if (map != null && holder.styleReady && holder.isAlive() && currentPosition != null) {
            holder.updateCamera(map, points, followLastPoint, currentPosition)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.start()
                Lifecycle.Event.ON_RESUME -> holder.resume()
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                Lifecycle.Event.ON_STOP -> holder.stop()
                Lifecycle.Event.ON_DESTROY -> holder.destroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }
}

/** Last-resort renderer used when MapLibre cannot start; keeps the route visible. */
@Composable
private fun BoxScope.RouteMapCanvas(
    points: List<TrackPoint>,
    plannedPoints: List<TrackPoint> = emptyList(),
    waypointMarkers: List<Pair<Double, Double>> = emptyList(),
    photoPositions: List<Pair<Double, Double>>,
    onPhotoTap: ((Int) -> Unit)?,
    onMapTap: ((Double, Double) -> Unit)? = null,
    onMapLongPress: ((Double, Double) -> Unit)? = null,
    /** Sprint 65: see the same parameters on [RouteMapPlaceholder]. */
    onWaypointTap: ((Int) -> Unit)? = null,
    onWaypointMove: ((Int, Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier,
    currentPosition: Pair<Double, Double>? = null,
    followLastPoint: Boolean = false,
    /**
     * Sprint 62: index inside [waypointMarkers] painted as the red "start" marker (route
     * waypoint #1). -1 keeps every marker blue, so tracking / track detail stay as before.
     */
    startMarkerIndex: Int = -1,
    locateTick: Int = 0,
    initialCentre: Pair<Double, Double>? = null,
    mapLoadingText: String? = null
) {
    val context = LocalContext.current
    val markerColor = MaterialTheme.colorScheme.tertiary
    // Photo points are camera badges. The vector is rasterized once (drawImage is used for the
    // map mosaic too, so the marker path stays on the same, version-stable API).
    val photoGlyph = remember {
        val px = PHOTO_GLYPH_PX.toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.drawable.ic_photo_camera)?.let { drawable ->
            drawable.setBounds(0, 0, px, px)
            drawable.setTint(android.graphics.Color.WHITE)
            drawable.draw(android.graphics.Canvas(bitmap))
        }
        bitmap.asImageBitmap()
    }

    val hasRoute = points.size >= 2

    // Gentle pulse for the "me" marker (0 -> 1 -> 0).
    val pulseTransition = rememberInfiniteTransition(label = "me-pulse")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "me-pulse-phase"
    )

    // Geo window to cover: the route, or just the current fix when nothing is recorded yet.
    // While recording (followLastPoint) the mosaic follows the live fix instead of the
    // whole route, like the MapLibre follow mode did.
    // Sprint 61: in the builder there is no recorded route yet, so the window also takes the
    // planned line, the waypoints and the fix into account; before any of them exists the
    // caller supplies initialCentre so the map opens on something meaningful.
    val windowPoints: List<TrackPoint> = if (hasRoute) points else buildList {
        addAll(points)
        addAll(plannedPoints)
        waypointMarkers.forEach { add(TrackPoint(it.first, it.second, 0.0, 0f, 0f, 0L)) }
        currentPosition?.let { add(TrackPoint(it.first, it.second, 0.0, 0f, 0f, 0L)) }
    }
    val minLat =
        if (windowPoints.isNotEmpty()) windowPoints.minOf { it.lat } else initialCentre?.first ?: 0.0
    val maxLat =
        if (windowPoints.isNotEmpty()) windowPoints.maxOf { it.lat } else initialCentre?.first ?: 0.0
    val minLon =
        if (windowPoints.isNotEmpty()) windowPoints.minOf { it.lon } else initialCentre?.second ?: 0.0
    val maxLon =
        if (windowPoints.isNotEmpty()) windowPoints.maxOf { it.lon } else initialCentre?.second ?: 0.0
    val followFix: Pair<Double, Double>? = if (followLastPoint) currentPosition else null
    val centerLat = followFix?.first ?: ((minLat + maxLat) / 2.0)
    val centerLon = followFix?.second ?: ((minLon + maxLon) / 2.0)
    val latSpan = if (followFix != null) 0.004 else max(maxLat - minLat, 0.002)
    val lonSpan = if (followFix != null) 0.004 else max(maxLon - minLon, 0.002)

    // Normalized Web Mercator camera. Imagery and overlays share ONE pixel scale.
    var manualCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var userZoom by remember { mutableStateOf<Float?>(null) }
    var locateFollow by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(locateTick) {
        if (locateTick > 0) {
            manualCenter = null
            locateFollow = true
            // Taxi-style strong zoom: street level, regardless of the previous gesture zoom.
            userZoom = MapTiles.TRACKING_ZOOM.toFloat()
        }
    }
    val target = if (locateFollow) currentPosition else null
    val viewX = manualCenter?.first ?: (((target?.second ?: centerLon) + 180.0) / 360.0)
    val viewY = manualCenter?.second ?: OsmTileLayer.mercYTiles(target?.first ?: centerLat)
    val zoom = userZoom ?: OsmTileLayer.pickZoom(centerLat, lonSpan, latSpan, 2).toFloat()
    val tileZoom = kotlin.math.floor(zoom).toInt().coerceIn(4, MapTiles.MAX_LIVE_ZOOM)
    // At integer zoom the viewport spans at most two tiles; a 3x3 grid covers its edges.
    val density = max(canvasSize.width, canvasSize.height).coerceAtLeast(1) / 512.0
    val worldPixels = 256.0 * Math.pow(2.0, zoom.toDouble()) * density
    val tileN = 1 shl tileZoom
    val tileX = kotlin.math.floor(viewX * tileN).toInt().coerceIn(0, tileN - 1)
    val tileY = kotlin.math.floor(viewY * tileN).toInt().coerceIn(0, tileN - 1)
    var mosaic by remember { mutableStateOf<OsmTileLayer.Mosaic?>(null) }
    var mapLoading by remember { mutableStateOf(false) }
    // Sprint 61: the builder opens with no fix and no waypoints, so initialCentre alone must
    // be enough to start loading tiles - otherwise the map stays blank on first visit.
    val hasPosition = currentPosition != null || points.isNotEmpty() ||
        plannedPoints.isNotEmpty() || waypointMarkers.isNotEmpty() || initialCentre != null
    val showMapLoading = !hasRoute && mapLoading && hasPosition
    // One fetch path, debounced by viewport tile/zoom, not by every GPS fix.
    LaunchedEffect(tileZoom, tileX, tileY, hasPosition) {
        if (!hasPosition) { mapLoading = false; return@LaunchedEffect }
        mapLoading = mosaic == null
        delay(250)
        val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * viewY))))
        val next = OsmTileLayer.mosaic(
            context.applicationContext, lat, viewX * 360.0 - 180.0,
            lonSpan, latSpan, tiles = 3, requestedZoom = tileZoom
        )
        if (next != null) mosaic = next
        mapLoading = false
    }
    val transform by rememberUpdatedState<(Offset, Offset, Float) -> Unit>({ centroid, pan, factor ->
        val nextZoom = (zoom + (kotlin.math.ln(factor.toDouble()) / kotlin.math.ln(2.0)).toFloat())
            // Sprint 28: the ceiling is high enough for house-level detail (was 14).
            .coerceIn(4f, MapTiles.MAX_LIVE_ZOOM.toFloat())
        val nextPixels = 256.0 * Math.pow(2.0, nextZoom.toDouble()) * density
        val dx = centroid.x - canvasSize.width / 2f
        val dy = centroid.y - canvasSize.height / 2f
        manualCenter =
            (viewX + dx / worldPixels - (dx + pan.x) / nextPixels).coerceIn(0.0, 1.0) to
            (viewY + dy / worldPixels - (dy + pan.y) / nextPixels).coerceIn(0.0, 1.0)
        userZoom = nextZoom
        locateFollow = false
    })
    // Sprint 62: pointer handlers live inside `pointerInput(Unit)`, so a closure over the plain
    // viewX/viewY/zoom vals keeps the camera of the frame the handler was created in. After a
    // pinch/drag that meant every long-press converted the OLD viewport back to lat/lon and the
    // waypoint landed far from the finger. This snapshot is refreshed on every recomposition and
    // the handlers read it through [latestCam], i.e. always the camera the user is looking at.
    val cam = CanvasCamera(viewX, viewY, zoom, worldPixels)
    val latestCam = rememberUpdatedState(cam)
    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset =
        cam.project(lat, lon, width, height)

    val wantTap = onPhotoTap != null && photoPositions.isNotEmpty() || onMapTap != null ||
        onWaypointTap != null
    // Sprint 61: a builder waypoint is created only by a hold without dragging or a
    // second finger; drag/pinch stay untouched because nothing here is ever consumed.
    // The hold is measured in wall-clock time: a motionless finger produces no pointer events
    // at all, so counting events instead of time never reaches the threshold on a real device.
    // Sprint 65: the hold is ~0.9 s, and a press that STARTS on a marker is a drag, not an add.
    val pressDensity = LocalDensity.current.density
    val hitRadiusPx = WAYPOINT_HIT_DP * pressDensity
    val latestLongPress = rememberUpdatedState(onMapLongPress)
    val latestWaypointTap = rememberUpdatedState(onWaypointTap)
    val latestWaypointMove = rememberUpdatedState(onWaypointMove)
    val latestWaypoints = rememberUpdatedState(waypointMarkers)
    // Sprint 62: hold feedback - an anchor in *screen* space plus a filling ring, so the user
    // sees that the press is being registered (previously a press looked dead for 2 seconds).
    var holdAnchor by remember { mutableStateOf<Offset?>(null) }
    var holdStartedAt by remember { mutableStateOf(0L) }
    val longPressModifier = if (onMapLongPress != null || onWaypointMove != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startId = down.id
                val startPos = down.position
                val slopPx = LONG_PRESS_TOUCH_SLOP_DP * pressDensity

                // Sprint 65: which marker (if any) the press landed on, in screen pixels.
                // The hit test wins over "add a point", so the two gestures never conflict.
                val size0 = canvasSize
                val hitIndex: Int? = if (size0.width > 0 && size0.height > 0 &&
                    latestWaypointMove.value != null
                ) {
                    val camera = latestCam.value
                    latestWaypoints.value.indices.firstOrNull { i ->
                        val marker = latestWaypoints.value[i]
                        val center = camera.project(
                            marker.first, marker.second,
                            size0.width.toFloat(), size0.height.toFloat()
                        )
                        val dx = startPos.x - center.x
                        val dy = startPos.y - center.y
                        dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                    }
                } else {
                    null
                }

                if (hitIndex != null) {
                    // Drag branch: no add, no ring - just follow the finger and commit on lift.
                    val dragRadius = 48f * pressDensity
                    var lastPos = startPos
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == startId }
                        if (change == null || !change.pressed) break
                        if (event.changes.size > 1) break
                        lastPos = change.position
                        // Panning the map with the same finger is still possible: a huge jump
                        // means the gesture is not a marker drag, so it is abandoned silently.
                        if ((lastPos - startPos).getDistance() > dragRadius) lastPos = startPos
                    }
                    val size = canvasSize
                    if (size.width > 0 && size.height > 0 && lastPos != startPos) {
                        val camera = latestCam.value
                        val (lat, lon) = camera.toLatLon(
                            lastPos.x, lastPos.y,
                            size.width.toFloat(), size.height.toFloat()
                        )
                        latestWaypointMove.value?.invoke(hitIndex, lat, lon)
                    }
                    return@awaitEachGesture
                }

                holdAnchor = startPos
                holdStartedAt = System.nanoTime()
                // Any cancel event (lift, drag past the slop, second finger) returns true;
                // a timeout - no cancel inside the window - means the finger really held still.
                val cancelled = withTimeoutOrNull(LONG_PRESS_HOLD_MS) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == startId }
                        if (change == null || !change.pressed) {
                            return@withTimeoutOrNull true
                        }
                        if (event.changes.size > 1 ||
                            (change.position - startPos).getDistance() > slopPx
                        ) {
                            return@withTimeoutOrNull true
                        }
                    }
                }
                holdAnchor = null
                if (cancelled == null) {
                    val size = canvasSize
                    if (size.width > 0 && size.height > 0) {
                        val camera = latestCam.value
                        val (lat, lon) = camera.toLatLon(
                            startPos.x, startPos.y,
                            size.width.toFloat(), size.height.toFloat()
                        )
                        // Sprint 62: freeze the viewport the moment a point is placed - the
                        // auto-fit window must not pull the map away from where the user works.
                        manualCenter = camera.viewX to camera.viewY
                        userZoom = camera.zoom
                        locateFollow = false
                        latestLongPress.value?.invoke(lat, lon)
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val tapModifier = if (wantTap) {
        Modifier.pointerInput(photoPositions, onMapTap) {
            detectTapGestures { tap ->
                val camera = latestCam.value
                val (lat, lon) = camera.toLatLon(
                    tap.x, tap.y, size.width.toFloat(), size.height.toFloat()
                )
                val photoIndex = photoPositions.indices.firstOrNull { i ->
                    val position = photoPositions[i]
                    val offset = camera.project(
                        position.first, position.second,
                        size.width.toFloat(), size.height.toFloat()
                    )
                    val dx = tap.x - offset.x
                    val dy = tap.y - offset.y
                    dx * dx + dy * dy <= TAP_RADIUS_PX * TAP_RADIUS_PX
                }
                if (photoIndex != null) {
                    onPhotoTap?.invoke(photoIndex)
                } else {
                    // Sprint 65: a tap on an existing builder marker edits it (the caller
                    // confirms the delete) instead of reaching the map-tap handler.
                    val waypointIndex = latestWaypointTap.value?.let { _ ->
                        waypointMarkers.indices.firstOrNull { i ->
                            val marker = waypointMarkers[i]
                            val center = camera.project(
                                marker.first, marker.second,
                                size.width.toFloat(), size.height.toFloat()
                            )
                            val dx = tap.x - center.x
                            val dy = tap.y - center.y
                            dx * dx + dy * dy <= hitRadiusPx * hitRadiusPx
                        }
                    }
                    if (waypointIndex != null) {
                        latestWaypointTap.value?.invoke(waypointIndex)
                    } else {
                        // Builder ignores short taps: waypoints come only from long-press.
                        onMapTap?.invoke(lat, lon)
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier
        .clipToBounds()
        .onSizeChanged { canvasSize = it }
        .then(tapModifier)
        .then(longPressModifier)
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, scale, _ -> transform(centroid, pan, scale) }
        }
    ) {
        // Sprint 62: reading the infinite transition *inside* the draw scope keeps this canvas
        // invalidating every frame - that is what makes the hold-progress ring animate even when
        // there is no "me" dot on screen yet.
        val framePulse = pulsePhase
        // Map imagery first (when downloaded), then route, photos and the "me" dot.
        mosaic?.let { m ->
            val n = (1 shl m.zoom).toDouble()
            val scale = worldPixels / (n * OsmTileLayer.TILE_PX)
            val left = size.width / 2.0 + (m.x0 / n - viewX) * worldPixels
            val top = size.height / 2.0 + (m.y0 / n - viewY) * worldPixels
            // One uniform transform even while the old mosaic remains during a zoom reload.
            withTransform({
                translate(left.toFloat(), top.toFloat())
                scale(scale.toFloat(), scale.toFloat(), Offset.Zero)
            }) {
                drawImage(m.bitmap.asImageBitmap())
            }
        }
        if (plannedPoints.size >= 2) {
            val plannedPath = Path()
            plannedPoints.forEachIndexed { index, point ->
                val offset = project(point.lat, point.lon, size.width, size.height)
                if (index == 0) plannedPath.moveTo(offset.x, offset.y) else plannedPath.lineTo(offset.x, offset.y)
            }
            drawPath(plannedPath, color = Color(0xFF1565C0), style = Stroke(width = 10f))
        }
        // Sprint 61: builder waypoints (numbered markers, visible immediately).
        // Sprint 62: waypoint #1 (the start) is painted red so the chain direction is obvious.
        waypointMarkers.forEachIndexed { index, marker ->
            val center = project(marker.first, marker.second, size.width, size.height)
            val markerFill = if (index == startMarkerIndex) Color(0xFFD32F2F) else Color(0xFF1565C0)
            drawCircle(Color.White, radius = 17f, center = center)
            drawCircle(markerFill, radius = 14f, center = center)
        }
        if (hasRoute) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val offset = project(point.lat, point.lon, size.width, size.height)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            // White underlay + bright blue line: readable on any tile imagery.
            drawPath(path, color = Color(0xCCFFFFFF), style = Stroke(width = 13f))
            drawPath(path, color = Color(0xFF2196F3), style = Stroke(width = 7f))
        }
        photoPositions.forEach { position ->
            val offset = project(position.first, position.second, size.width, size.height)
            // Camera badge: dark halo + brand disc + a white camera glyph; reads as "a photo
            // was taken here" on any tile imagery and stays visible next to the route line.
            drawCircle(Color(0x99000000), radius = 19f, center = offset)
            drawCircle(markerColor, radius = 16f, center = offset)
            withTransform({
                translate(offset.x - PHOTO_GLYPH_TARGET_PX / 2f, offset.y - PHOTO_GLYPH_TARGET_PX / 2f)
                scale(PHOTO_GLYPH_TARGET_PX / PHOTO_GLYPH_PX, PHOTO_GLYPH_TARGET_PX / PHOTO_GLYPH_PX, Offset.Zero)
            }) {
                drawImage(photoGlyph)
            }
        }
        // Live GPS fix: bright blue, larger, with a white ring and a subtle pulse so it
        // reads as "current position" on any backdrop.
        if (currentPosition != null) {
            val pos = currentPosition
            val meCenter = project(pos.first, pos.second, size.width, size.height)
            val pulse = 1f + 0.18f * pulsePhase
            drawCircle(Color(0x332196F3), radius = 30f * pulse, center = meCenter)
            drawCircle(Color(0xFF0D47A1), radius = 18f, center = meCenter)
            drawCircle(Color(0xFF2196F3), radius = 15f, center = meCenter)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 15f, center = meCenter, style = Stroke(width = 4f))
        }
        // Sprint 62: hold-to-place feedback. The anchor is stored in screen space (the camera is
        // frozen while the finger is down), and the pulse animation already invalidates the
        // canvas every frame, so the ring fills without any extra coroutine.
        holdAnchor?.let { anchor ->
            val elapsed = (System.nanoTime() - holdStartedAt) / 1_000_000f
            val progress = (elapsed / LONG_PRESS_HOLD_MS).coerceIn(0f, 1f)
            drawCircle(Color(0x66FFFFFF), radius = 34f + 2f * framePulse, center = anchor)
            drawCircle(Color(0x33000000), radius = 34f, center = anchor, style = Stroke(width = 6f))
            drawArc(
                color = Color(0xFFD32F2F),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(anchor.x - 34f, anchor.y - 34f),
                size = Size(68f, 68f),
                style = Stroke(width = 6f)
            )
        }
    }
    if (showMapLoading && mapLoadingText != null) {
        MapNotice(text = mapLoadingText, modifier = Modifier.align(Alignment.TopCenter))
    }
}
