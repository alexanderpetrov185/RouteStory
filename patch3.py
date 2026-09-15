import re
from pathlib import Path
mp = Path(r"E:\Projects\RouteStory\app\src\main\java\com\alexander\routestory\ui\screens\RouteMapPlaceholder.kt")
t = mp.read_text(encoding="utf-8")
old_layers = """        style.addLayer(
            CircleLayer(PHOTO_LAYER, PHOTO_SOURCE).withProperties("""
assert old_layers in t, "layers anchor missing"
t = t.replace(old_layers, """        style.addLayer(
            CircleLayer(ME_LAYER, ME_SOURCE).withProperties(
                PropertyFactory.circleColor(meColor),
                PropertyFactory.circleRadius(MARKER_RADIUS + 2f),
                PropertyFactory.circleStrokeWidth(MARKER_STROKE + 1f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)
            )
        )
        style.addLayer(
            CircleLayer(PHOTO_LAYER, PHOTO_SOURCE).withProperties(""", 1)
old_tail = """        routeSource = route
        photosSource = shots
    }"""
assert old_tail in t, "tail missing"
t = t.replace(old_tail, """        routeSource = route
        photosSource = shots
        meSource = meSrc
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
    }""", 1)
old_cam = """    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean) {
        val last = points.lastOrNull() ?: return
        if (followLastPoint) {"""
assert old_cam in t, "camera anchor missing"
t = t.replace(old_cam, """    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean, currentPosition: Pair<Double, Double>? = null) {
        val mePos = currentPosition ?: me
        if (mePos != null && (followLastPoint || points.size < 2)) {
            if (followLastPoint && userPanned) return
            runCatching { map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mePos.first, mePos.second), MapTiles.TRACKING_ZOOM)) }
            if (points.size < 2) { lastBoundsKey = null; return }
        }
        val last = points.lastOrNull() ?: return
        if (followLastPoint) {""", 1)
mp.write_text(t, encoding="utf-8")
print("ok2")
