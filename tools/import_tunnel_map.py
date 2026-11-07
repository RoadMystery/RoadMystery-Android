"""Fetch an OSM tunnel way and derive a demo detection corridor (requires shapely).

Map data and derived geometry: OpenStreetMap contributors, ODbL 1.0.
This 30 m buffer is an application detection tolerance, not a surveyed tunnel width.
"""
import json
import math
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

from shapely.geometry import LineString

ROOT = Path(__file__).resolve().parents[1]
WAY = "37930783"
URL = f"https://api.openstreetmap.org/api/0.6/way/{WAY}/full"
root = ET.fromstring(urlopen(URL, timeout=30).read())
way = next(w for w in root.findall("way") if w.attrib["id"] == WAY)
tags = {t.attrib["k"]: t.attrib["v"] for t in way.findall("tag")}
assert tags.get("tunnel") == "yes" and tags.get("highway")
nodes = {n.attrib["id"]: (float(n.attrib["lat"]), float(n.attrib["lon"])) for n in root.findall("node")}
points = [nodes[n.attrib["ref"]] for n in way.findall("nd")]
lat0, lon0 = points[0]
sx, sy = 111320 * math.cos(math.radians(lat0)), 111320
line = LineString([((lon - lon0) * sx, (lat - lat0) * sy) for lat, lon in points])
polygon = line.buffer(30, cap_style="flat", join_style="round")
assert polygon.is_valid and polygon.geom_type == "Polygon"
def geo(xy):
    return {"latitude": round(lat0 + xy[1] / sy, 8), "longitude": round(lon0 + xy[0] / sx, 8)}
data = {
    "name": tags.get("tunnel:name", tags["name"]),
    "source": f"https://www.openstreetmap.org/way/{WAY}",
    "sourceApi": URL,
    "osmVersion": int(way.attrib["version"]),
    "osmTimestamp": way.attrib["timestamp"],
    "attribution": "© OpenStreetMap contributors · ODbL 1.0",
    "license": "https://www.openstreetmap.org/copyright",
    "bufferMeters": 30,
    "centerline": [{"latitude": lat, "longitude": lon} for lat, lon in points],
    "regions": [{"id": f"osm-way-{WAY}", "tag": "tunnel", "polygon": [geo(p) for p in polygon.exterior.coords[:-1]]}],
}
target = ROOT / "app/src/main/assets/maps/namsan3_tunnel.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
coords = list(line.coords)
def extend(a, b):
    dx, dy = a[0] - b[0], a[1] - b[1]
    length = math.hypot(dx, dy)
    return (a[0] + dx / length * 150, a[1] + dy / length * 150)
route = {"source": data["source"], "outsideBefore": geo(extend(coords[0], coords[1])),
         "inside": geo(line.interpolate(0.5, normalized=True).coords[0]),
         "outsideAfter": geo(extend(coords[-1], coords[-2]))}
routepath = ROOT / "docs/f09/verification/gps-route.json"
routepath.write_text(json.dumps(route, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"name": data["name"], "way": WAY, "lengthMeters": round(line.length), "route": route}, ensure_ascii=False))
