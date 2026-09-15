import re
from pathlib import Path
mp = Path(r"E:\Projects\RouteStory\app\src\main\java\com\alexander\routestory\ui\screens\RouteMapPlaceholder.kt")
t = mp.read_text(encoding="utf-8")
t = t.replace("        routeSource = null\r\n        photosSource = null\r\n    }", "        routeSource = null\r\n        photosSource = null\r\n        meSource = null\r\n    }")
t = t.replace("        routeSource = null\n        photosSource = null\n    }", "        routeSource = null\n        photosSource = null\n        meSource = null\n    }")
t = t.replace("fun attachStyle(style: Style, lineColor: Int, markerColor: Int) {", "fun attachStyle(style: Style, lineColor: Int, markerColor: Int, meColor: Int) {")
t = t.replace("val shots = GeoJsonSource(PHOTO_SOURCE, empty)\n        style.addSource(route)\n        style.addSource(shots)",
              "val shots = GeoJsonSource(PHOTO_SOURCE, empty)\n        val meSrc = GeoJsonSource(ME_SOURCE, empty)\n        style.addSource(route)\n        style.addSource(shots)\n        style.addSource(meSrc)")
mp.write_text(t, encoding="utf-8")
print("ok")
