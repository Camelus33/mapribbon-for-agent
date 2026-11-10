"""Checks a /v1/route answer for the expressway's two ends against the bundled route built offline from the same OSM.

usage: python3 -I tools/e2e/check_route.py <route.json> <bundled anchors.json>
"""
import json
import sys

route = json.load(open(sys.argv[1], encoding="utf-8"))
bundle = json.load(open(sys.argv[2], encoding="utf-8"))
m = route["match"]
ours = route["anchorSet"]["anchors"]
failed = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        failed.append(what)


tolerance = max(1000, round(bundle["lengthM"] * 0.03))
check(m["verdict"] == "SAME", f"verdict {m['verdict']} (navi {m.get('naviDistanceM')} m, ours {m['lengthM']} m)")
check(abs(m["lengthM"] - bundle["lengthM"]) <= tolerance, f"length {m['lengthM']} m within {tolerance} m of {bundle['lengthM']} m")
theirs = {a["name"] for a in bundle["anchors"] if a["type"] == "TUNNEL"}
mine = {a["name"] for a in ours if a["type"] == "TUNNEL"}
check(len(theirs & mine) >= 12, f"tunnels {len(theirs & mine)} of {len(theirs)} bundled (missing {sorted(theirs - mine)})")
check(any(a["name"] == "서양양IC" for a in ours), "서양양IC on the route")
check(all(a["id"][:2] in ("T:", "J:", "G:", "C:", "S:") for a in ours), "route-independent anchor ids")
sys.exit(1 if failed else 0)
