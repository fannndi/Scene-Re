#!/usr/bin/env bash
# (device-side self-elevates to root via Magisk su)
# ============================================================================
# Scene debug bridge — pulls a diagnostics snapshot from the connected device
# through adb + su and prints an LLM/agent-friendly Markdown report.
#
# Usage:
#   bash tools/scene-debug.sh [logcat_lines]     (default 120)
#
# Used by an AI agent inside the chat harness: run it, read the output,
# reason about device state. Read-only: nothing on the device is modified
# except a temporary script under /data/local/tmp which is removed after.
# ============================================================================
set -uo pipefail

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="adb"
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  else
    echo "ERROR: adb not found (set ADB=/path/to/adb)" >&2
    exit 1
  fi
fi

LINES="${1:-120}"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "ERROR: no device connected (adb devices)" >&2
  exit 1
fi

TMP_REMOTE="/data/local/tmp/.scene-debug.sh"
TMP_LOCAL="$(mktemp /tmp/scene-debug-XXXXXX.sh)"
trap 'rm -f "$TMP_LOCAL"' EXIT

cat > "$TMP_LOCAL" <<'DEVICE_SCRIPT'
#!/system/bin/sh
# Self-elevate to root (Magisk) — needed for kgsl / msm_performance / thermal_message
LINES="${1:-120}"
if [ "$(id -u)" != "0" ]; then
  exec su -c "sh $0 $LINES" 2>/dev/null
fi

echo "## Device & build"
echo '```'
echo "model   : $(getprop ro.product.model)"
echo "device  : $(getprop ro.product.device)"
echo "board   : $(getprop ro.board.platform)"
echo "brand   : $(getprop ro.product.brand)"
echo "android : $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
echo "miui    : $(getprop ro.miui.ui.version.name)"
echo "build   : $(getprop ro.build.display.id)"
echo "kernel  : $(uname -r)"
echo "uid     : $(id -u 2>/dev/null)"
echo "magisk  : $(magisk -v 2>/dev/null | head -1)"
echo '```'

echo
echo "## App (Scene)"
echo '```'
echo "version : $(dumpsys package com.omarea.vtools 2>/dev/null | grep -m1 versionName | tr -d ' ')"
echo "pid     : $(pidof com.omarea.vtools 2>/dev/null)"
echo "mode    : $(getprop vtools.powercfg)"
echo "mode_app: $(getprop vtools.powercfg_app)"
echo "chg_stop: $(getprop vtools.bp)"
echo '```'

echo
echo "## CPU (cpufreq / core_ctl / boost)"
echo '```'
echo "online : $(cat /sys/devices/system/cpu/online 2>/dev/null)"
for p in /sys/devices/system/cpu/cpufreq/policy*; do
  echo "--- $(basename "$p")"
  echo "  governor : $(cat "$p/scaling_governor" 2>/dev/null)"
  echo "  cur      : $(cat "$p/scaling_cur_freq" 2>/dev/null)"
  echo "  min/max  : $(cat "$p/scaling_min_freq" 2>/dev/null) / $(cat "$p/scaling_max_freq" 2>/dev/null)"
  echo "  avail    : $(cat "$p/scaling_available_frequencies" 2>/dev/null)"
  echo "  hispeed  : $(cat "$p/schedutil/hispeed_freq" 2>/dev/null)"
done
echo "core_ctl cpu0: enable=$(cat /sys/devices/system/cpu/cpu0/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu0/core_ctl/min_cpus 2>/dev/null)"
echo "core_ctl cpu6: enable=$(cat /sys/devices/system/cpu/cpu6/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu6/core_ctl/min_cpus 2>/dev/null)"
echo "msm_perf max : $(cat /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null)"
echo "input_boost  : $(cat /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null) / $(cat /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null)ms"
echo '```'

echo
echo "## GPU (kgsl Adreno)"
echo '```'
echo "governor : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)"
echo "cur      : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null)"
echo "min/max  : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null) / $(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null)"
echo "avail    : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)"
echo "pwrlevel : min=$(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null) max=$(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null) num=$(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null)"
echo "busy     : $(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null)"
echo '```'

echo
echo "## Thermal"
echo '```'
for z in /sys/class/thermal/thermal_zone*; do
  echo "$(cat "$z/type" 2>/dev/null): $(cat "$z/temp" 2>/dev/null)"
done
echo "sconfig           : $(cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null)"
echo "board_sensor_temp : $(cat /sys/class/thermal/thermal_message/board_sensor_temp 2>/dev/null)"
echo '```'

echo
echo "## Memory & swap"
echo '```'
grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree|Cached" /proc/meminfo 2>/dev/null
echo "zram mm_stat : $(cat /sys/block/zram0/mm_stat 2>/dev/null)"
echo "swappiness   : $(cat /proc/sys/vm/swappiness 2>/dev/null)"
echo '```'

echo
echo "## Battery"
echo '```'
b=/sys/class/power_supply/battery
echo "capacity    : $(cat "$b/capacity" 2>/dev/null)"
echo "status      : $(cat "$b/status" 2>/dev/null)"
echo "current_now : $(cat "$b/current_now" 2>/dev/null)"
echo "voltage_now : $(cat "$b/voltage_now" 2>/dev/null)"
echo "temp        : $(cat "$b/temp" 2>/dev/null)"
echo "chg_enabled : $(cat "$b/battery_charging_enabled" 2>/dev/null)"
echo "cc_max      : $(cat "$b/constant_charge_current_max" 2>/dev/null)"
echo '```'

echo
echo "## Cpuset & stune"
echo '```'
echo "background        : $(cat /dev/cpuset/background/cpus 2>/dev/null)"
echo "system-background : $(cat /dev/cpuset/system-background/cpus 2>/dev/null)"
echo "foreground        : $(cat /dev/cpuset/foreground/cpus 2>/dev/null)"
echo "top-app           : $(cat /dev/cpuset/top-app/cpus 2>/dev/null)"
echo "sched_boost       : $(cat /proc/sys/kernel/sched_boost 2>/dev/null)"
echo "top-app idle/boost: $(cat /dev/stune/top-app/schedtune.prefer_idle 2>/dev/null) / $(cat /dev/stune/top-app/schedtune.boost 2>/dev/null)"
echo '```'

echo
echo "## UFS health"
echo '```'
echo "life_a : $(cat /sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_a 2>/dev/null)"
echo "life_b : $(cat /sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_b 2>/dev/null)"
echo '```'

echo
echo "## Logcat (errors, last ${LINES} lines)"
echo '```'
logcat -d -t "$LINES" '*:E' 2>/dev/null | tail -n "$LINES"
echo '```'
DEVICE_SCRIPT

"$ADB" push "$TMP_LOCAL" "$TMP_REMOTE" >/dev/null 2>&1
"$ADB" shell "sh $TMP_REMOTE $LINES; rm -f $TMP_REMOTE"
