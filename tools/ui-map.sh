#!/usr/bin/env bash
# Generate an agent-friendly UI map: screenshots + element JSON per screen.
#
#   bash tools/ui-map.sh            # all screens
#   bash tools/ui-map.sh tuner      # one screen
#
# Output: docs/ui/<screen>.png + docs/ui/<screen>.json
# Then use tools/agent-tap.sh to tap elements by text/id.
set -u
cd "$(dirname "$0")/.."

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then ADB="$HOME/Android/Sdk/platform-tools/adb"
  else echo "adb not found"; exit 1; fi
fi

OUT=docs/ui
mkdir -p "$OUT"

pkg=com.omarea.vtools
sleep_short=2
sleep_load=4

launch() { "$ADB" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep "$sleep_load"; }
tap() { "$ADB" shell input tap "$1" "$2"; sleep "$sleep_short"; }
tuner() { tap 200 180; }
tools() { tap 359 156; }
back() { "$ADB" shell input keyevent 4; sleep "$sleep_short"; }

capture() {
  local name="$1"
  "$ADB" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  "$ADB" shell rm -f /sdcard/scene-ui.xml >/dev/null 2>&1
  for attempt in 1 2 3; do
    "$ADB" shell uiautomator dump /sdcard/scene-ui.xml >/dev/null 2>&1
    if "$ADB" shell "[ -f /sdcard/scene-ui.xml ] && echo yes" 2>/dev/null | grep -q yes; then
      break
    fi
    sleep 2
  done
  "$ADB" pull /sdcard/scene-ui.xml "$OUT/$name.uix.xml" >/dev/null 2>&1
  python3 tools/uix2json.py "$OUT/$name.uix.xml" "$OUT/$name.json" || echo "  (parse failed: $name)"
}

want() { [ -z "${SCREEN:-}" ] || [ "$1" = "$SCREEN" ]; }

SCREEN="${1:-}"

if want home; then echo "[ui-map] home"; launch; capture home; fi
if want tuner; then echo "[ui-map] tuner"; launch; tuner; capture tuner; fi
if want tools; then echo "[ui-map] tools"; launch; tools; capture tools; fi
if want diagnostics; then echo "[ui-map] diagnostics"; launch; tools; tap 798 914; sleep 10; capture diagnostics; fi
if want tweaks; then echo "[ui-map] tweaks"; launch; tools; tap 323 929; sleep 12; capture tweaks; fi
if want cpucontrol; then echo "[ui-map] cpucontrol"; launch; tuner; tap 764 1487; sleep 8; capture cpucontrol; fi
if want appdetails; then echo "[ui-map] appdetails"; "$ADB" shell am start -n "$pkg/.ui.activity.ActivityAppDetails" --es app com.whatsapp >/dev/null 2>&1; sleep 8; capture appdetails; fi
if want appconfig; then echo "[ui-map] appconfig"; "$ADB" shell am start -n "$pkg/.ui.activity.ActivityAppConfig2" >/dev/null 2>&1; sleep 3; capture appconfig; fi

echo "[ui-map] done -> $OUT/"
