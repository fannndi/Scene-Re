#!/usr/bin/env bash
# Generate an agent-friendly UI map: screenshots + element JSON per screen.
#
#   bash tools/ui-map.sh            # all screens
#   bash tools/ui-map.sh automation # one screen
#
# How navigation works (all coordinate-free against *live* state):
#  - portrait is locked for the run (uiautomator bounds are orientation-specific),
#  - every parent restore cold-starts the app explicitly with
#    `am start ActivityMain --ei select_tab N` — never `monkey`, because the
#    debug build ships LeakCanary with its own LAUNCHER activity,
#  - children are tapped from a FRESH uiautomator dump (with scroll retries),
#    because the debug build's activities are not exported (am start is denied)
#    and items below the fold are absent from the saved maps.
#  - each capture is skipped unless the expected component actually resumed.
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
mkdir -p /tmp/opencode

pkg=com.omarea.vtools
main=com.omarea.vtools/com.omarea.ui.activity.ActivityMain
sleep_short=2

# uiautomator bounds follow the current rotation; the maps are 1080x2400.
ROT_PREV="$("$ADB" shell settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')"
lock_portrait() {
  "$ADB" shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
  "$ADB" shell settings put system user_rotation 0 >/dev/null 2>&1
  sleep 2
}
unlock_rotation() {
  [ -n "${ROT_PREV:-}" ] && "$ADB" shell settings put system accelerometer_rotation "$ROT_PREV" >/dev/null 2>&1
}
trap unlock_rotation EXIT

# Only the RESUMED activity counts: grepping the whole dumpsys matches the
# background ActivityRecord of every task and made the back-loop believe main
# was already in front (children never popped).
focused() {
  "$ADB" shell dumpsys activity activities 2>/dev/null \
    | grep -m1 "mResumedActivity" | grep -q "$1"
}
back() { "$ADB" shell input keyevent 4; sleep "$sleep_short"; }

# Land on a known tab. A cold start bounces through ActivityStartSplash, and
# sending the select_tab intent while the splash is still on top creates a
# second ActivityMain instance (whose Tuner cards never re-attach) — so wait
# for main to actually be resumed, THEN deliver the tab intent (singleTop →
# onNewIntent).
tab() {
  # Deliberately NO force-stop: a cold process renders the Tuner without its
  # XML cards (pre-existing bug, reproduced on the Fase-2 build too), and the
  # warm path keeps the maps representative. Splash is only waited out when
  # the process did start fresh.
  # Pop any child screen first: `am start` does NOT always bring main forward
  # when a child sits on top of it in the same task (observed on this device),
  # while BACK does. Then deliver the tab intent (singleTop -> onNewIntent).
  local i
  for i in 1 2 3 4; do
    focused "ActivityMain" && break
    "$ADB" shell input keyevent 4 >/dev/null 2>&1
    sleep 1.5
  done
  "$ADB" shell am start -n "$main" >/dev/null 2>&1
  for i in $(seq 1 30); do
    focused "ActivityStartSplash" || break
    sleep 1
  done
  sleep 2
  "$ADB" shell am start -n "$main" --ei select_tab "$1" >/dev/null 2>&1
  sleep 3
}

# Fresh hierarchy → /tmp/opencode/live-ui.xml (always drop the previous file,
# a failed pull must never be read as the current screen).
dump_live() {
  rm -f /tmp/opencode/live-ui.xml
  "$ADB" shell rm -f /sdcard/scene-ui.xml >/dev/null 2>&1
  local i
  for i in 1 2 3; do
    "$ADB" shell uiautomator dump /sdcard/scene-ui.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/scene-ui.xml /tmp/opencode/live-ui.xml >/dev/null 2>&1
    [ -s /tmp/opencode/live-ui.xml ] && return 0
    sleep 1
  done
  return 1
}

# Tap a selector ("id:part" or plain text, case-insensitive substring) on the
# LIVE screen; returns 1 when not found.
tap_live() {
  dump_live || return 1
  local coords
  coords=$(python3 - "/tmp/opencode/live-ui.xml" "$1" <<'PY'
import html, re, sys
xml = html.unescape(open(sys.argv[1], encoding="utf-8", errors="ignore").read())
sel = sys.argv[2]
# "id:part" → resource-id match; anything else is plain text. Never use
# partition() here: without a colon it would yield an EMPTY needle that
# matches every node (and taps the top-most one, i.e. the tab bar).
if sel.lower().startswith("id:"):
    mode, needle = "id", sel[3:].lower()
else:
    mode, needle = "text", sel.lower()
best = None
for m in re.finditer(r"<node[^>]*>", xml):
    tag = m.group(0)
    def attr(name):
        am = re.search(name + r'="([^"]*)"', tag)
        return am.group(1) if am else ""
    rid, text, desc = attr("resource-id"), attr("text"), attr("content-desc")
    clickable = attr("clickable") == "true"
    enabled = attr("enabled") == "true"
    if not enabled:
        continue
    hay = rid if mode == "id" else (text + " " + desc)
    if needle not in hay.lower():
        continue
    b = re.findall(r"\d+", attr("bounds"))
    if len(b) != 4:
        continue
    cx, cy = (int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2
    cand = (not clickable, cy, cx)   # clickable first, then top-most
    if best is None or cand < best[0]:
        best = (cand, cx, cy)
if best:
    print(best[1], best[2])
PY
)
  [ -z "$coords" ] && return 1
  # shellcheck disable=SC2086
  "$ADB" shell input tap $coords
  sleep "$sleep_short"
  return 0
}

# tap_scrolled <selector> — try, scroll down, retry (below-the-fold items).
tap_scrolled() {
  local i
  # Long screens (settings/tuner) need several swipes to reach the bottom.
  for i in $(seq 1 9); do
    tap_live "$1" && return 0
    "$ADB" shell input swipe 540 1800 540 500 250 >/dev/null 2>&1
    sleep 1
  done
  return 1
}

capture() {
  local name="$1"
  "$ADB" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  "$ADB" shell rm -f /sdcard/scene-ui.xml >/dev/null 2>&1
  local attempt
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

# Bring a parent screen back to the foreground (children leave it behind).
restore() {
  case "$1" in
    home) tab 0 ;;
    tuner)
      tab 1
      # Pre-existing bug: the FIRST Tuner view of a fresh process renders only
      # the profile card (its XML cards never attach). A tab round-trip
      # recreates the fragment view — verified workaround.
      if ! dump_live || ! grep -q 'id/nav_cpu_control' /tmp/opencode/live-ui.xml; then
        tab 0
        tab 1
      fi
      ;;
    tools) tab 2 ;;
    settings) tab 0; tap_scrolled "id:action_settings" ;;
    powerutil) tab 2; tap_scrolled "Power util" ;;
    *) tab 0 ;;
  esac
}

# Component that must be resumed after restoring a given parent.
parent_component() {
  case "$1" in
    settings) echo ActivityOtherSettings ;;
    charge) echo ActivityCharge ;;
    *) echo ActivityMain ;;
  esac
}

# go <parent> <selector> <screen> <component> [wait]
go() {
  local parent="$1" sel="$2" name="$3" comp="$4" wait="${5:-5}"
  echo "[ui-map] $name"
  restore "$parent"
  if ! focused "$(parent_component "$parent")"; then
    echo "  (skipped: restore $parent failed — $(parent_component "$parent") not in foreground)"
    return 1
  fi
  if ! tap_scrolled "$sel"; then
    echo "  (skipped: '$sel' not reachable on $parent)"
    return 1
  fi
  sleep "$wait"
  if ! focused "${comp##*.}"; then
    echo "  (skipped: ${comp##*.} not in foreground)"
    back
    return 1
  fi
  capture "$name"
}

want() { [ -z "${SCREEN:-}" ] || [ "$1" = "$SCREEN" ]; }

SCREEN="${1:-}"
lock_portrait

# Root tabs first.
if want home; then echo "[ui-map] home"; tab 0; capture home; fi
if want tuner; then echo "[ui-map] tuner"; tab 1; capture tuner; fi
if want tools; then echo "[ui-map] tools"; tab 2; capture tools; fi

# --- children of the Tools tab ---
if want fpschart; then go tools "FPS record" fpschart com.omarea.ui.activity.ActivityFpsChart 6; fi
if want benchmark; then go tools "Benchmark" benchmark com.omarea.ui.activity.ActivityBenchmark 6; fi
if want powerutil; then go tools "Power util" powerutil com.omarea.ui.activity.ActivityPowerUtilization 6; fi
if want swap; then go tools "Swap" swap com.omarea.ui.activity.ActivitySwap 6; fi
if want miuithermal; then go tools "MIUI thermal" miuithermal com.omarea.ui.activity.ActivityMiuiThermal 6; fi
if want automation; then go tools "Automation" automation com.omarea.ui.activity.ActivityAutomation 6; fi
if want tweaks; then go tools "Kernel tweaks" tweaks com.omarea.ui.activity.ActivityTweaks 8; fi
if want diagnostics; then go tools "Diagnostics" diagnostics com.omarea.ui.activity.ActivityDiagnostics 8; fi

# --- children of the Tuner tab ---
if want cpucontrol; then go tuner "id:nav_cpu_control" cpucontrol com.omarea.ui.activity.ActivityCpuControl 6; fi
if want appconfig; then
  echo "[ui-map] appconfig"
  restore tuner
  if tap_scrolled "id:nav_app_profiles"; then
    sleep 2
    # "Dynamic response is off" warning may appear first — confirm it.
    tap_live "id:btn_confirm" || true
    sleep 5
    if focused ActivityAppConfig2; then capture appconfig
    else echo "  (skipped: ActivityAppConfig2 not in foreground)"; back; fi
  else
    echo "  (skipped: id:nav_app_profiles not reachable on tuner)"
  fi
fi

# --- top bar + nested screens ---
if want settings; then go home "id:action_settings" settings com.omarea.ui.activity.ActivityOtherSettings 5; fi
if want about; then go settings "About & help" about com.omarea.ui.activity.ActivityAbout 5; fi
if want chargehw; then go powerutil "Hardware" chargehw com.omarea.ui.activity.ActivityChargeController 6; fi

if want appdetails; then
  echo "[ui-map] appdetails"
  "$ADB" shell am start -n "$pkg/com.omarea.ui.activity.ActivityAppDetails" --es app com.whatsapp >/dev/null 2>&1
  sleep 6
  if focused ActivityAppDetails; then capture appdetails; else echo "  (skipped)"; back; fi
fi

echo "[ui-map] done -> $OUT/"
