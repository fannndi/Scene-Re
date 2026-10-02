#!/usr/bin/env bash
# node-watch.sh — print the engine-owned nodes every N seconds.
#
# Use it while interacting with the device (camera, app launch, game) to see
# which actor writes what and whether changes are transient (perf hints) or
# persistent (a daemon fighting the profile).
#
# Usage: bash tools/node-watch.sh [interval_seconds] [count]
set -uo pipefail

ADB=${ADB:-adb}
INTERVAL=${1:-5}
COUNT=${2:-0}

NODES="
/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq
/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq
/sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_freq
/sys/module/cpu_boost/parameters/input_boost_freq
/sys/module/cpu_boost/parameters/input_boost_ms
/sys/devices/system/cpu/cpu0/core_ctl/enable
/sys/devices/system/cpu/cpu6/core_ctl/enable
/proc/sys/kernel/sched_upmigrate
/proc/sys/kernel/sched_downmigrate
/dev/cpuset/foreground/cpus
/dev/cpuset/top-app/cpus
/sys/module/lowmemorykiller/parameters/minfree
/sys/class/kgsl/kgsl-3d0/max_pwrlevel
/sys/class/kgsl/kgsl-3d0/default_pwrlevel
/sys/class/thermal/thermal_message/sconfig
/sys/module/msm_performance/parameters/cpu_min_freq
/sys/module/msm_performance/parameters/cpu_max_freq
"

SCRIPT=$(for n in $NODES; do
    echo "printf '%s = ' \"$n\"; cat '$n' 2>/dev/null | head -1"
done)

i=0
while :; do
    i=$((i + 1))
    echo "=== $(date '+%H:%M:%S') (sample $i) ==="
    adb shell su -c "$SCRIPT"
    [ "$COUNT" -gt 0 ] && [ "$i" -ge "$COUNT" ] && break
    sleep "$INTERVAL"
done
