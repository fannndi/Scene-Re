#!/usr/bin/env bash
# rom-stock-check.sh — compare live tuning nodes against the ROM stock table
# (MIUI 14 surya, init.qcom.post_boot.sh moorea block) or against the app's
# pre-engine snapshot.
#
# Usage:
#   bash tools/rom-stock-check.sh              # against the built-in stock table
#   bash tools/rom-stock-check.sh --snapshot   # against /sdcard/Scene/debug/stock-snapshot.json
#
# Exit code: 0 when everything matches, 1 otherwise (mismatch list on stdout).
set -uo pipefail

ADB=${ADB:-adb}
SU=${SU:-"adb shell su -c"}

# node=value pairs: the state the ROM leaves at boot on soc_id 365/366.
STOCK=$(cat <<'EOF'
/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq=576000
/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq=1248000
/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq=652800
/sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_freq=1324800
/sys/module/cpu_boost/parameters/input_boost_ms=120
/sys/module/cpu_boost/parameters/powerkey_input_boost_ms=400
/sys/devices/system/cpu/cpu0/core_ctl/min_cpus=4
/sys/devices/system/cpu/cpu0/core_ctl/busy_up_thres=60
/sys/devices/system/cpu/cpu6/core_ctl/enable=0
/proc/sys/kernel/sched_downmigrate=65
/proc/sys/kernel/sched_upmigrate=71
/proc/sys/kernel/sched_group_downmigrate=85
/proc/sys/kernel/sched_group_upmigrate=100
/dev/cpuset/background/cpus=0-2
/dev/cpuset/system-background/cpus=0-3
/dev/cpuset/foreground/cpus=0-2,4-7
/dev/cpuset/foreground/boost/cpus=4-7
/dev/cpuset/top-app/cpus=0-7
/sys/module/lowmemorykiller/parameters/minfree=18432,23040,27648,96768,276480,362880
/proc/sys/vm/dirty_background_ratio=10
/proc/sys/vm/dirty_ratio=20
/proc/sys/vm/overcommit_ratio=50
/proc/sys/vm/vfs_cache_pressure=100
/proc/sys/vm/swap_ratio=100
/sys/class/thermal/thermal_message/sconfig=0
EOF
)

MODE=${1:-}
if [ "$MODE" = "--snapshot" ]; then
    echo "snapshot mode: values are compared against the app's captured stock" >&2
    echo "(the json dump is parsed with python3 on the host)" >&2
    JSON=/tmp/opencode/stock-snapshot.json
    $ADB pull /sdcard/Scene/debug/stock-snapshot.json "$JSON" >/dev/null 2>&1 || {
        echo "cannot pull /sdcard/Scene/debug/stock-snapshot.json — open the app once with the engine ON" >&2
        exit 2
    }
    STOCK=$(python3 - "$JSON" <<'PY'
import json, sys
obj = json.load(open(sys.argv[1]))
for node, value in obj.get("nodes", {}).items():
    print(f"{node}={value}")
PY
)
fi

fail=0
while IFS='=' read -r node want; do
    [ -z "$node" ] && continue
    have=$($SU "cat '$node' 2>/dev/null" | tr -d '\r' | head -1)
    if [ "$have" = "$want" ]; then
        printf '  ok   %s = %s\n' "$node" "$have"
    else
        printf '  DIFF %s: live=%s stock=%s\n' "$node" "$have" "$want"
        fail=1
    fi
done <<< "$STOCK"

if [ "$fail" -eq 0 ]; then
    echo "all checked nodes match the ROM stock state"
else
    echo
    echo "mismatches found — engine OFF should restore the ROM state."
    echo "see docs/ROM-HARMONY.md (stock snapshot / release profile)."
fi
exit $fail
