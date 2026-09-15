import re
from pathlib import Path
mp = Path(r"E:\Projects\RouteStory\app\src\main\java\com\alexander\routestory\ui\screens\RouteMapPlaceholder.kt")
t = mp.read_text(encoding="utf-8")
old_layers = "        style.addLayer(\n            CircleLayer(PHOTO_LAYER, PHOTO_SOURCE).withProperties("
assert old_layers in t
t = t.replace(old_layers, "        style.addLayer(\n            CircleLayer(ME_LAYER, ME_SOURCE).withProperties(\n                PropertyFactory.circleColor(meColor),\n                PropertyFactory.circleRadius(MARKER_RADIUS + 2f),\n                PropertyFactory.circleStrokeWidth(MARKER_STROKE + 1f),\n                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)\n            )\n        )\n        style.addLayer(\n            CircleLayer(PHOTO_LAYER, PHOTO_SOURCE).withProperties(", 1)
old_tail = "        routeSource = route\r\n        photosSource = shots\r\n    }"
assert old_tail in t, "tail2 missing"
t = t.replace(old_tail, "        routeSource = route\r\n        photosSource = shots\r\n        meSource = meSrc\r\n        updateMe()\r\n    }\r\n\r\n    fun updateMe() {\r\n        val source = meSource ?: return\r\n        val pos = me\r\n        runCatching {\r\n            source.setGeoJson(\r\n                if (pos == null) FeatureCollection.fromFeatures(emptyArray<Feature>())\r\n                else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(pos.second, pos.first)))\r\n            )\r\n        }\r\n    }", 1)
mp.write_text(t, encoding="utf-8")
print("ok3")
