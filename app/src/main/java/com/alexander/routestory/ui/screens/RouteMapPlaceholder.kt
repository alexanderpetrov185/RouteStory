package com.alexander.routestory.ui.screens

import android.os.Build
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexander.routestory.BuildConfig
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.utils.MapAvailability
import com.alexander.routestory.utils.MapTiles
import com.alexander.routestory.utils.OsmTileLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
private const val BOUNDS_PADDING_PX = 64
private const val STYLE_TIMEOUT_MS = 8_000L

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
    modifier: Modifier = Modifier,
    photoPositions: List<Pair<Double, Double>> = emptyList(),
    onPhotoTap: ((Int) -> Unit)? = null,
    followLastPoint: Boolean = false,
    currentPosition: Pair<Double, Double>? = null,
    locateTick: Int = 0,
    onLocate: (() -> Unit)? = null
) {
    val context = LocalContext.current
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
                photoPositions = photoPositions,
                onPhotoTap = onPhotoTap,
                modifier = Modifier.fillMaxSize(),
                currentPosition = currentPosition,
                followLastPoint = followLastPoint,
                locateTick = locateTick
            )
            when {
                !mapAllowed && !MapTiles.FORCE_OSM_CANVAS -> MapNotice(
                    text = stringResource(R.string.map_unavailable),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                !online -> MapNotice(
                    text = stringResource(R.string.map_no_network),
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
            androidx.compose.material3.IconButton(
                onClick = it,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0xCC1C2330), shape = androidx.compose.foundation.shape.CircleShape)
            ) {
                // Core icon set has no "my location"; a simple crosshair glyph is enough.
                Text("◎", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (BuildConfig.DEBUG) {
            val fixText = currentPosition?.let { "%.5f,%.5f".format(it.first, it.second) } ?: "-"
            Text(
                text = "map=${if (useCanvas) "Canvas" else "ML"} reach=$online " +
                    "style=${styleOk?.let { if (it) "ok" else "fail" } ?: "?"} fix=$fixText",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0xCC1C2330))
                    .padding(4.dp)
            )
        }
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
                        runCatching { holder.handleTap(map, latLng) }.getOrDefault(false)
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
private fun RouteMapCanvas(
    points: List<TrackPoint>,
    photoPositions: List<Pair<Double, Double>>,
    onPhotoTap: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    currentPosition: Pair<Double, Double>? = null,
    followLastPoint: Boolean = false,
    locateTick: Int = 0
) {
    val context = LocalContext.current
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val meColor = MaterialTheme.colorScheme.secondary
    val hasRoute = points.size >= 2

    // Geo window to cover: the route, or just the current fix when nothing is recorded yet.
    // While recording (followLastPoint) the mosaic follows the live fix instead of the
    // whole route, like the MapLibre follow mode did.
    val minLat = if (hasRoute) points.minOf { it.lat } else currentPosition?.first ?: 0.0
    val maxLat = if (hasRoute) points.maxOf { it.lat } else currentPosition?.first ?: 0.0
    val minLon = if (hasRoute) points.minOf { it.lon } else currentPosition?.second ?: 0.0
    val maxLon = if (hasRoute) points.maxOf { it.lon } else currentPosition?.second ?: 0.0
    val followFix: Pair<Double, Double>? = if (followLastPoint) currentPosition else null
    val centerLat = followFix?.first ?: ((minLat + maxLat) / 2.0)
    val centerLon = followFix?.second ?: ((minLon + maxLon) / 2.0)
    val latSpan = if (followFix != null) 0.004 else max(maxLat - minLat, 0.002)
    val lonSpan = if (followFix != null) 0.004 else max(maxLon - minLon, 0.002)

    // OSM tile mosaic behind the route (real map imagery without MapLibre).
    // Route, photos and the "me" dot render IMMEDIATELY; tiles are swapped in when the
    // mosaic arrives. The fetch loop is NOT keyed on GPS ticks: it re-fetches only when
    // the window meaningfully changed (center moved ~1 km or the span grew), and the
    // previous mosaic stays on screen while the next one is downloading.
    var mosaic by remember { mutableStateOf<OsmTileLayer.Mosaic?>(null) }
    val window = rememberUpdatedState(
        listOf(centerLat, centerLon, lonSpan, latSpan) to locateTick
    )
    LaunchedEffect(Unit) {
        var lastKey = ""
        while (true) {
            val (geo, tick) = window.value
            // locateTick forces an immediate re-centre on the latest fix (locate button).
            val key = "%.2f,%.2f|%.3f|tick=%d".format(geo[0], geo[1], max(geo[2], geo[3]), tick)
            if (key != lastKey) {
                val next = OsmTileLayer.mosaic(
                    context = context.applicationContext,
                    centerLat = geo[0],
                    centerLon = geo[1],
                    lonSpanDeg = geo[2],
                    latSpanDeg = geo[3],
                    tiles = 2 // 2x2 = 4 tiles: fastest useful coverage
                )
                if (next != null) {
                    mosaic = next
                    lastKey = key
                }
            }
            delay(1_000)
        }
    }

    /**
     * Projection matching the mosaic geometry (zoom / x0 / y0), so the route lands on
     * the same pixels as the tile imagery. Falls back to a plain linear fit when the
     * mosaic is unavailable (offline).
     */
    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset {
        val m = mosaic
        if (m != null) {
            val n = 1 shl m.zoom
            val x = (((lon + 180.0) / 360.0) * n - m.x0) * OsmTileLayer.TILE_PX / m.tilesX *
                (width / OsmTileLayer.TILE_PX)
            val y = (OsmTileLayer.mercYTiles(lat) * n - m.y0) * OsmTileLayer.TILE_PX / m.tilesY *
                (height / OsmTileLayer.TILE_PX)
            return Offset(x.toFloat(), y.toFloat())
        }
        val padding = min(width, height) * 0.1f
        val latRange = max(maxLat - minLat, 1e-6)
        val lonRange = max(maxLon - minLon, 1e-6)
        val scaleX = (width - 2 * padding) / lonRange.toFloat()
        val scaleY = (height - 2 * padding) / latRange.toFloat()
        val scale = min(scaleX, scaleY)
        val x = padding + ((lon - minLon) * scale).toFloat() +
            (width - 2 * padding - ((maxLon - minLon) * scale).toFloat()) / 2f
        val y = padding + ((maxLat - lat) * scale).toFloat() +
            (height - 2 * padding - ((maxLat - minLat) * scale).toFloat()) / 2f
        return Offset(x, y)
    }

    val tapModifier = if (onPhotoTap != null && photoPositions.isNotEmpty()) {
        Modifier.pointerInput(photoPositions) {
            detectTapGestures { tap ->
                val index = photoPositions.indices.firstOrNull { i ->
                    val position = photoPositions[i]
                    val offset = project(
                        position.first, position.second,
                        size.width.toFloat(), size.height.toFloat()
                    )
                    val dx = tap.x - offset.x
                    val dy = tap.y - offset.y
                    dx * dx + dy * dy <= TAP_RADIUS_PX * TAP_RADIUS_PX
                }
                if (index != null) onPhotoTap(index)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.then(tapModifier)) {
        // Map imagery first (when downloaded), then route, photos and the "me" dot.
        mosaic?.let { m ->
            // Stretch the square mosaic over the canvas; the projection above
            // applies the same stretch, so imagery and route stay aligned.
            drawImage(
                image = m.bitmap.asImageBitmap(),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(m.widthPx, m.heightPx),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
        if (hasRoute) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val offset = project(point.lat, point.lon, size.width, size.height)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = 6f))
        }
        photoPositions.forEach { position ->
            val offset = project(position.first, position.second, size.width, size.height)
            drawCircle(markerColor, radius = 14f, center = offset)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 5f, center = offset)
        }
        // Live GPS fix: visible even when no route points exist yet (single point case).
        if (currentPosition != null) {
            val pos = currentPosition
            val center = if (hasRoute) {
                project(pos.first, pos.second, size.width, size.height)
            } else {
                Offset(size.width / 2f, size.height / 2f)
            }
            drawCircle(meColor, radius = 12f, center = center)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 4f, center = center)
        }
    }
}
