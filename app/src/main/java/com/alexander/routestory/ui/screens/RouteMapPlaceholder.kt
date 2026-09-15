package com.alexander.routestory.ui.screens

import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alexander.routestory.R
import com.alexander.routestory.domain.TrackPoint
import com.alexander.routestory.utils.MapTiles
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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
private const val ROUTE_WIDTH = 5f
private const val MARKER_RADIUS = 6f
private const val MARKER_STROKE = 2f
private const val TAP_RADIUS_PX = 30f
private const val BOUNDS_PADDING_PX = 64

/**
 * Route preview on top of MapLibre + OpenStreetMap tiles.
 * Falls back to a plain canvas rendering when the map cannot be initialised,
 * so the screens keep working offline and on devices without OpenGL ES.
 */
@Composable
fun RouteMapPlaceholder(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    photoPositions: List<Pair<Double, Double>> = emptyList(),
    onPhotoTap: ((Int) -> Unit)? = null,
    followLastPoint: Boolean = false
) {
    val context = LocalContext.current
    val styleJson = remember { MapTiles.loadStyleJson(context) }
    var mapUnavailable by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { online = MapTiles.isOnline(context) }

    if (styleJson == null || mapUnavailable) {
        RouteMapCanvas(
            points = points,
            photoPositions = photoPositions,
            onPhotoTap = onPhotoTap,
            modifier = modifier
        )
        return
    }

    Box(modifier = modifier) {
        MapLibreRouteMap(
            styleJson = styleJson,
            points = points,
            photoPositions = photoPositions,
            onPhotoTap = onPhotoTap,
            followLastPoint = followLastPoint,
            onUnavailable = { mapUnavailable = true },
            modifier = Modifier.fillMaxSize()
        )
        if (!online) {
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                Text(
                    text = stringResource(R.string.map_no_network),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/** Mutable bridge between Compose state and the MapView instance. */
private class MapHolder {
    var mapView: MapView? = null
    var map: MapLibreMap? = null
    var routeSource: GeoJsonSource? = null
    var photosSource: GeoJsonSource? = null
    var photos: List<Pair<Double, Double>> = emptyList()
    var onPhotoTap: ((Int) -> Unit)? = null
    var lastBoundsKey: String? = null
    var userPanned = false
    private var started = false
    private var resumed = false
    private var destroyed = false

    fun start() {
        if (destroyed || started) return
        started = true
        runCatching { mapView?.onStart() }
    }

    fun resume() {
        if (destroyed || resumed) return
        resumed = true
        runCatching { mapView?.onResume() }
    }

    fun pause() {
        if (destroyed || !resumed) return
        resumed = false
        runCatching { mapView?.onPause() }
    }

    fun stop() {
        if (destroyed || !started) return
        started = false
        runCatching { mapView?.onStop() }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        started = false
        resumed = false
        runCatching { mapView?.onDestroy() }
        mapView = null
        map = null
        routeSource = null
        photosSource = null
    }

    fun attachStyle(style: Style, lineColor: Int, markerColor: Int) {
        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
        val route = GeoJsonSource(ROUTE_SOURCE, empty)
        val photos = GeoJsonSource(PHOTO_SOURCE, empty)
        style.addSource(route)
        style.addSource(photos)
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(lineColor),
                PropertyFactory.lineWidth(ROUTE_WIDTH)
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

    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean) {
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
    styleJson: String,
    points: List<TrackPoint>,
    photoPositions: List<Pair<Double, Double>>,
    onPhotoTap: ((Int) -> Unit)?,
    followLastPoint: Boolean,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { MapHolder() }
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val markerColor = MaterialTheme.colorScheme.tertiary.toArgb()

    holder.photos = photoPositions
    holder.onPhotoTap = onPhotoTap

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = runCatching { MapView(context) }.getOrNull()
            if (view == null) {
                onUnavailable()
                FrameLayout(context)
            } else {
                holder.mapView = view
                view.getMapAsync { map ->
                    holder.map = map
                    map.setStyle(
                        Style.Builder().fromJson(styleJson),
                        Style.OnStyleLoaded { style ->
                            holder.attachStyle(style, lineColor, markerColor)
                            holder.updateRoute(points)
                            holder.updatePhotos()
                        }
                    )
                    map.addOnMapClickListener { latLng -> holder.handleTap(map, latLng) }
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            holder.userPanned = true
                        }
                    }
                }
                // The observer can miss the initial lifecycle events, so sync with the host state.
                val state = lifecycleOwner.lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.STARTED)) holder.start()
                if (state.isAtLeast(Lifecycle.State.RESUMED)) holder.resume()
                view
            }
        },
        update = {
            holder.updateRoute(points)
            holder.updatePhotos()
            holder.map?.let { map -> holder.updateCamera(map, points, followLastPoint) }
        }
    )

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
            holder.pause()
            holder.stop()
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
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val hasRoute = points.size >= 2

    val minLat = if (hasRoute) points.minOf { it.lat } else 0.0
    val maxLat = if (hasRoute) points.maxOf { it.lat } else 0.0
    val minLon = if (hasRoute) points.minOf { it.lon } else 0.0
    val maxLon = if (hasRoute) points.maxOf { it.lon } else 0.0
    val latRange = max(maxLat - minLat, 1e-6)
    val lonRange = max(maxLon - minLon, 1e-6)

    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset {
        val padding = min(width, height) * 0.1f
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
    }
}
