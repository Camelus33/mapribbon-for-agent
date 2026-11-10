#!/usr/bin/env bash
# End to end on this machine: the ribbon server draws a test navigation route, the app in the emulator shows it, then
# simulated cars' knots (marked simulated) turn into ribbons on that route.
#
# Needs: JDK 17 (JAVA_HOME), ribbon-server/data/south-korea-latest.osm.pbf (./gradlew :ribbon-server:downloadOsm),
# a running Pleos Connect emulator. Screenshots and answers go to out/e2e-<time>/.
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
OUT=out/e2e-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
DATA=ribbon-server/data
SERVER=http://127.0.0.1:8787
[ -f "$DATA/south-korea-latest.osm.pbf" ] || { echo "No OSM file: run ./gradlew :ribbon-server:downloadOsm first"; exit 2; }

# APK=release builds the release variant (signed with the key in keystore.properties when there is one), for an
# emulator that already has a release-signed build installed: a debug build cannot replace it.
VARIANT=${APK:-debug}
if [ "$VARIANT" = release ]; then ./gradlew -q :ribbon-server:installDist :app:assembleRelease; else ./gradlew -q :ribbon-server:installDist :app:assembleDebug; fi

started=""
if ! curl -sf "$SERVER/v1/health" >/dev/null; then
  ribbon-server/build/install/ribbon-server/bin/ribbon-server --data "$DATA" > "$OUT/server.log" 2>&1 &
  started=$!
fi
cleanup() { [ -n "$started" ] && kill "$started" 2>/dev/null; "$ADB" reverse --remove tcp:8787 2>/dev/null || true; }
trap cleanup EXIT
echo "waiting for the ribbon server (the first start imports Korea: minutes)"
until curl -s "$SERVER/v1/health" | grep -q '"ready":true'; do sleep 5; done

# 1. The server alone: the expressway's two ends give the bundled route.
ORIGIN=37.914718,128.290595; HEADING=61; DEST=38.063314,128.596721; NAVI_M=34508
curl -sf -X POST -H 'Content-Type: application/json' \
  -d "{\"origin\":{\"lat\":37.914718,\"lon\":128.290595},\"originHeadingDeg\":$HEADING,\"destination\":{\"lat\":38.063314,\"lon\":128.596721},\"naviDistanceM\":$NAVI_M,\"destinationName\":\"양양JC\",\"source\":\"TEST\"}" \
  "$SERVER/v1/route" > "$OUT/route.json"
python3 -I tools/e2e/check_route.py "$OUT/route.json" app/src/main/assets/routes/SY60E/anchors.json

# 2. The app: the same route typed in as a test navigation route.
"$ADB" reverse tcp:8787 tcp:8787
"$ADB" install -r -g "app/build/outputs/apk/$VARIANT/app-$VARIANT.apk" >/dev/null || {
  echo "Install failed. If the signatures differ, run with APK=release, or uninstall the app first."; exit 1; }
send_route() {
  "$ADB" shell am start -n kr.tedin.maptalkdrive/.MainActivity --es navi_origin "$ORIGIN" --ef navi_heading "$HEADING" \
    --es navi_dest "$DEST" --ei navi_distance_m "$NAVI_M" --es navi_dest_name '%EC%96%91%EC%96%91JC' >/dev/null
}
send_route
sleep 8
"$ADB" exec-out screencap -p > "$OUT/1-route.png"
echo "screen 1: test start → 양양JC, 'test input', 'same as navigation', every place ○, no simulated-data chip"

# 3. Simulated cars over the same route (marked simulated on the server), then the ribbons on screen.
./gradlew -q :ribbon-server:simulate --args="--from $ORIGIN --heading $HEADING --to $DEST --navi-distance $NAVI_M --scenario app/src/main/assets/scenarios/local-weather.json" | tee "$OUT/simulate.txt"
send_route
sleep 6
"$ADB" exec-out screencap -p > "$OUT/2-ribbons.png"
user=$("$ADB" shell am get-current-user | tr -d '\r')
"$ADB" shell "content query --user $user --uri content://kr.tedin.maptalkdrive.ribbons/caution" | tee "$OUT/caution.txt"
echo "screen 2: the simulated-data chip, rain at the Inje-Yangyang tunnel exit, heavy rain at 26.0–27.3 km"
echo "out: $OUT"
