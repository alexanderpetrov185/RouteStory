import re
from pathlib import Path
base = Path(r"E:\Projects\RouteStory")
mp = base/"app/src/main/java/com/alexander/routestory/ui/screens/RouteMapPlaceholder.kt"
t = mp.read_text(encoding="utf-8")

def sub(old, new):
    global t
    assert old in t, "NOT FOUND: "+old[:80]
    t = t.replace(old, new, 1)

# fix destroy() naming back
sub("            clear()\n            return", "            clearReferences()\n            return")
sub("        runCatching { view?.onDestroy() }\n        clear()\n    }", "        runCatching { view?.onDestroy() }\n        clearReferences()\n    }")
# meSource clear
sub("        routeSource = null\n        photosSource = null\n    }", "        routeSource = null\n        photosSource = null\n        meSource = null\n    }")
# attachStyle with me layer
sub("    fun attachStyle(style: Style, lineColor: Int, markerColor: Int) {\n        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())\n        val route = GeoJsonSource(ROUTE_SOURCE, empty)\n        val shots = GeoJsonSource(PHOTO_SOURCE, empty)\n        style.addSource(route)\n        style.addSource(shots)",
    "    fun attachStyle(style: Style, lineColor: Int, markerColor: Int, meColor: Int) {\n        val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())\n        val route = GeoJsonSource(ROUTE_SOURCE, empty)\n        val shots = GeoJsonSource(PHOTO_SOURCE, empty)\n        val meSrc = GeoJsonSource(ME_SOURCE, empty)\n        style.addSource(route)\n        style.addSource(shots)\n        style.addSource(meSrc)")
sub("        routeSource = route\n        photosSource = shots\n    }",
    "        style.addLayer(\n            CircleLayer(ME_LAYER, ME_SOURCE).withProperties(\n                PropertyFactory.circleColor(meColor),\n                PropertyFactory.circleRadius(MARKER_RADIUS + 2f),\n                PropertyFactory.circleStrokeWidth(MARKER_STROKE + 1f),\n                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)\n            )\n        )\n        routeSource = route\n        photosSource = shots\n        meSource = meSrc\n        updateMe()\n    }\n\n    fun updateMe() {\n        val source = meSource ?: return\n        val pos = me\n        runCatching {\n            source.setGeoJson(\n                if (pos == null) FeatureCollection.fromFeatures(emptyArray<Feature>())\n                else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(pos.second, pos.first)))\n            )\n        }\n    }")
# updateCamera with currentPosition
sub("    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean) {\n        val last = points.lastOrNull() ?: return",
    "    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean, currentPosition: Pair<Double, Double>? = null) {\n        val mePos = currentPosition ?: me\n        if (mePos != null && (followLastPoint || points.size < 2)) {\n            if (followLastPoint && userPanned) return\n            runCatching { map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mePos.first, mePos.second), MapTiles.TRACKING_ZOOM)) }\n            if (points.size < 2) { lastBoundsKey = null; return }\n        }\n        val last = points.lastOrNull() ?: return")
sub("    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean, currentPosition: Pair<Double, Double>? = null) {",
    "    fun updateCamera(map: MapLibreMap, points: List<TrackPoint>, followLastPoint: Boolean, currentPosition: Pair<Double, Double>? = null) {",)
mp.write_text(t, encoding="utf-8")
print("part1 ok", len(t))
