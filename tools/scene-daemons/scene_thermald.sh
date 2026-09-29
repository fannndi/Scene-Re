#!/system/bin/sh
# ============================================================================
# scene_thermald — lightweight replacement for Xiaomi mi_thermald
# ----------------------------------------------------------------------------
# Replaces: /vendor/bin/mi_thermald  (MIUI thermal daemon)
#
# What the original does (from RE):
#   - loads encrypted thermal config from /data/vendor/thermal/config/
#   - raises scaling_MIN_FREQ per CPU (the annoyance this replaces)
#   - clamps scaling_MAX_FREQ per CPU, cooling devices cur_state
#   - writes cpu_limits / sconfig / boost / board_sensor_temp
#
# What this replacement does:
#   - watches battery temperature every 5s
#   - only ever LOWERS scaling_max_freq when hot (never touches
#     scaling_min_freq, never disables cores, never changes governor)
#   - releases the clamp when the device cools down (hysteresis -2C)
#   - kernel hardware thermal trips remain the final safety net
#
# Control:
#   touch /data/local/tmp/scene_thermald.stop   -> exits within 5s
#   cat /data/local/tmp/scene_thermald.state    -> not used; check log below
#   log: /sdcard/Scene/scene_thermald.log
# ============================================================================

LOG=/sdcard/Scene/scene_thermald.log
STOP=/data/local/tmp/scene_thermald.stop
INTERVAL=5

log() {
    echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOG" 2>/dev/null
}

clamp_max() {
    # clamp_max <max_khz>
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
        if [ -f "$p/scaling_max_freq" ]; then
            cur=$(cat "$p/scaling_max_freq" 2>/dev/null)
            if [ -n "$cur" ] && [ "$cur" -gt "$1" ] 2>/dev/null; then
                chmod 0664 "$p/scaling_max_freq" 2>/dev/null
                echo "$1" > "$p/scaling_max_freq" 2>/dev/null
            fi
        fi
    done
}

log "scene_thermald started (pid $$)"

while true; do
    if [ -f "$STOP" ]; then
        log "stop file found -> exit (freqs left as-is)"
        rm -f "$STOP"
        exit 0
    fi

    TEMP=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
    if [ -z "$TEMP" ]; then
        sleep $INTERVAL
        continue
    fi
    # milli-Celsius -> decide state (hysteresis via state file)
    T=$((TEMP / 10))

    PREV_FILE=/data/local/tmp/scene_thermald.state
    PREV=$(cat "$PREV_FILE" 2>/dev/null)

    if [ "$T" -ge 47 ]; then
        STATE=critical
        LIMIT=1248000
    elif [ "$T" -ge 44 ]; then
        STATE=hot
        LIMIT=1612800
    elif [ "$T" -ge 40 ]; then
        STATE=warm
        LIMIT=1843200
    else
        STATE=normal
        LIMIT=""
    fi

    # hysteresis: stay in current state until temp drops 2C below entry
    if [ "$STATE" != "$PREV" ]; then
        case "$PREV:$STATE" in
            warm:normal)   if [ "$T" -ge 38 ]; then STATE=warm; LIMIT=1843200; fi ;;
            hot:warm)      if [ "$T" -ge 42 ]; then STATE=hot;   LIMIT=1612800; fi ;;
            critical:hot)  if [ "$T" -ge 45 ]; then STATE=critical; LIMIT=1248000; fi ;;
        esac
    fi

    if [ "$STATE" = "normal" ]; then
        if [ "$PREV" != "normal" ] && [ -n "$PREV" ]; then
            log "TEMP ${T}C -> normal, releasing clamp"
            # re-apply profile max: re-run current profile max via Scene mode re-apply
            # simplest safe release: set both clusters to their full OPPs
            clamp_max 1804800
            chmod 0664 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
            echo 2304000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null
        fi
    else
        if [ "$STATE" != "$PREV" ] || [ -z "$PREV" ]; then
            log "TEMP ${T}C -> $STATE, clamping scaling_max to $LIMIT"
        fi
        clamp_max "$LIMIT"
    fi

    echo "$STATE" > "$PREV_FILE" 2>/dev/null
    sleep $INTERVAL
done
