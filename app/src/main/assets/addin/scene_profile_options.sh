#!/system/bin/sh

# Scene profile options applier.
#
# Runs after every powercfg mode switch and applies the extra tuning layer that
# the platform scripts do not own: frequency limiter, lite mode, governor and
# I/O scheduler preferences, game process priority, and optional system tweaks.
#
# Environment (all optional, set by ProfileOptions.kt):
#   SCENE_MODE            init|powersave|balance|performance|fast|restore
#   SCENE_LIMIT_PERCENT   0 = off, otherwise 20..100 (% of cpuinfo_max_freq)
#   SCENE_LITE            1 = skip min-frequency pinning on performance modes
#   SCENE_GOVERNOR        e.g. schedutil / walt / performance (empty = leave)
#   SCENE_IOSCHED         e.g. none / mq-deadline / kyber / bfq (empty = leave)
#   SCENE_PID             1 = raise game process priority
#   SCENE_GAME_PKG        package whose PIDs get prioritised
#   SCENE_EXTRA_TWEAKS    1 = apply low-risk network/VM/IO extras
#   SCENE_GAME_DOWNSCALE  0 = off, otherwise 50..100 (% resolution scale)
#   SCENE_GAME_FPS        0 = off, otherwise target frame rate (30..120)
#   SCENE_GAME_RESET      1 = drop the game mode overlay (game left)
#   SCENE_SDK             Build.VERSION.SDK_INT
#   SCENE_RESET           1 = undo limiter/lite pinning (mode left / disabled)

BUSYBOX="${BUSYBOX:-busybox}"

write_val() {
    local node="$1"
    local value="$2"
    if [[ -e "$node" ]]; then
        chmod 0664 "$node" 2> /dev/null
        echo "$value" > "$node" 2> /dev/null
    fi
}

read_val() {
    if [[ -e "$1" ]]; then
        cat "$1" 2> /dev/null
    fi
}

# $1 = space separated list, $2 = target; echoes the closest entry
nearest_freq() {
    local best=""
    local bestdiff=""
    local f diff
    for f in $1; do
        diff=$(( f > $2 ? f - $2 : $2 - f ))
        if [[ -z "$best" ]] || [[ $diff -lt $bestdiff ]]; then
            best=$f
            bestdiff=$diff
        fi
    done
    echo "$best"
}

# Snapshot the stock min/max once per cluster so the loop can be undone.
backup_freq() {
    local policy="$1"
    local name="$(basename "$policy")"
    local prop_max="vtools.scene.freq.bak.max.$name"
    local prop_min="vtools.scene.freq.bak.min.$name"
    if [[ "$(getprop $prop_max)" = "" ]]; then
        setprop $prop_max "$(read_val "$policy/cpuinfo_max_freq")"
    fi
    if [[ "$(getprop $prop_min)" = "" ]]; then
        setprop $prop_min "$(read_val "$policy/cpuinfo_min_freq")"
    fi
}

apply_freq_limit() {
    local percent="$1"
    local policy maxf target avail curmin name
    [[ -z "$percent" ]] && return 0
    [[ "$percent" = "0" ]] && return 0
    [[ "$percent" -lt 20 ]] && percent=20
    [[ "$percent" -gt 100 ]] && percent=100

    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        backup_freq "$policy"
        maxf="$(read_val "$policy/cpuinfo_max_freq")"
        [[ -z "$maxf" ]] && continue
        target=$(( maxf / 100 * percent ))
        avail="$(read_val "$policy/scaling_available_frequencies")"
        if [[ -n "$avail" ]]; then
            target="$(nearest_freq "$avail" "$target")"
        fi
        curmin="$(read_val "$policy/scaling_min_freq")"
        if [[ -n "$curmin" ]] && [[ "$curmin" -gt "$target" ]]; then
            write_val "$policy/scaling_min_freq" "$target"
        fi
        write_val "$policy/scaling_max_freq" "$target"
    done
}

restore_freq() {
    local policy name prop_max prop_min
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        prop_max="$(getprop vtools.scene.freq.bak.max.$name)"
        prop_min="$(getprop vtools.scene.freq.bak.min.$name)"
        [[ -n "$prop_max" ]] && write_val "$policy/scaling_max_freq" "$prop_max"
        [[ -n "$prop_min" ]] && write_val "$policy/scaling_min_freq" "$prop_min"
    done
}

# Lite mode: undo the min-frequency pinning that performance scripts apply.
restore_min_freq() {
    local policy name prop_min
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        prop_min="$(getprop vtools.scene.freq.bak.min.$name)"
        [[ -n "$prop_min" ]] && write_val "$policy/scaling_min_freq" "$prop_min"
    done
}

apply_governor() {
    local gov="$1"
    local policy avail
    [[ -z "$gov" ]] && return 0
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        avail="$(read_val "$policy/scaling_available_governors")"
        case " $avail " in
            *" $gov "*) write_val "$policy/scaling_governor" "$gov" ;;
        esac
    done
}

restore_governor() {
    local policy name prop
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        prop="$(getprop vtools.scene.gov.bak.$name)"
        [[ -n "$prop" ]] && write_val "$policy/scaling_governor" "$prop"
    done
}

backup_governor() {
    local policy name prop
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        prop="vtools.scene.gov.bak.$name"
        if [[ "$(getprop $prop)" = "" ]]; then
            setprop $prop "$(read_val "$policy/scaling_governor")"
        fi
    done
}

apply_iosched() {
    local sched="$1"
    local dev avail
    [[ -z "$sched" ]] && return 0
    for dev in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda /sys/block/sdb /sys/block/sdc; do
        [[ -d "$dev/queue" ]] || continue
        avail="$(read_val "$dev/queue/scheduler")"
        case "$avail" in
            *"[$sched]"*) continue ;;
            *"$sched"*) write_val "$dev/queue/scheduler" "$sched" ;;
        esac
    done
}

restore_iosched() {
    local dev name prop current
    for dev in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda /sys/block/sdb /sys/block/sdc; do
        [[ -d "$dev/queue" ]] || continue
        name="$(basename "$dev")"
        prop="$(getprop vtools.scene.iosched.bak.$name)"
        [[ -z "$prop" ]] && continue
        current="$(read_val "$dev/queue/scheduler")"
        case "$current" in
            *"[$prop]"*) continue ;;
        esac
        write_val "$dev/queue/scheduler" "$prop"
    done
}

backup_iosched() {
    local dev name prop current
    for dev in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda /sys/block/sdb /sys/block/sdc; do
        [[ -d "$dev/queue" ]] || continue
        name="$(basename "$dev")"
        prop="vtools.scene.iosched.bak.$name"
        if [[ "$(getprop $prop)" = "" ]]; then
            current="$(read_val "$dev/queue/scheduler")"
            # Keep only the active entry, e.g. "mq-deadline [bfq] kyber" -> bfq.
            current="$(echo "$current" | tr ' ' '\n' | sed -n 's/^\[\(.*\)\]$/\1/p')"
            [[ -n "$current" ]] && setprop $prop "$current"
        fi
    done
}

apply_game_priority() {
    local pkg="$1"
    local pid
    [[ -z "$pkg" ]] && return 0
    for pid in $(pidof "$pkg" 2> /dev/null); do
        if command -v renice > /dev/null 2>&1; then
            renice -n -20 -p "$pid" > /dev/null 2>&1
        else
            $BUSYBOX renice -n -20 -p "$pid" > /dev/null 2>&1
        fi
        if command -v ionice > /dev/null 2>&1; then
            ionice -c 1 -n 0 -p "$pid" > /dev/null 2>&1
        else
            $BUSYBOX ionice -c 1 -n 0 -p "$pid" > /dev/null 2>&1
        fi
    done
}

# Per-game resolution downscale / target FPS through the platform Game Mode API.
# Android 13+ exposes the overlay controls; Android 12 only has the game mode.
apply_game_mode() {
    local pkg="$1"
    local downscale="$2"
    local fps="$3"
    [[ -z "$pkg" ]] && return 0
    [[ "$downscale" = "0" ]] && [[ "$fps" = "0" ]] && return 0

    local args=""
    if [[ "$downscale" != "0" ]]; then
        args="$args --downscale $downscale"
    fi
    if [[ "$fps" != "0" ]]; then
        args="$args --fps $fps"
    fi

    if [[ "${SCENE_SDK:-0}" -ge 33 ]]; then
        cmd game set --mode 2 $args "$pkg" > /dev/null 2>&1
    elif [[ "${SCENE_SDK:-0}" -ge 31 ]]; then
        cmd game mode 2 "$pkg" > /dev/null 2>&1
    fi
}

reset_game_mode() {
    if [[ "${SCENE_SDK:-0}" -ge 31 ]]; then
        cmd game reset --mode 2 > /dev/null 2>&1
    fi
}

apply_sched_lib() {
    # Qualcomm kernels with CONFIG_SCHED_LIB accept a space separated list in one
    # write; boosting the common game engines gives them scheduler preference.
    local libs="libunity.so libil2cpp.so libUE4.so libmain.so libflutter.so libgodot_android.so libcocos2djs.so libmonobdwgc-2.0.so"
    if [[ -e /proc/sys/kernel/sched_lib_name ]]; then
        echo "$libs" > /proc/sys/kernel/sched_lib_name 2> /dev/null
        write_val /proc/sys/kernel/sched_lib_mask_force 255
    fi
}

choose_tcp_cc() {
    # Prefer low-latency congestion control when the kernel ships one.
    local avail cc
    avail="$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2> /dev/null)"
    for cc in bbr3 bbr2 bbrplus bbr westwood cubic; do
        case " $avail " in
            *" $cc "*)
                write_val /proc/sys/net/ipv4/tcp_congestion_control "$cc"
                return
                ;;
        esac
    done
}

apply_extra_tweaks() {
    local block
    write_val /proc/sys/net/ipv4/tcp_fastopen 3
    write_val /proc/sys/net/ipv4/tcp_low_latency 1
    write_val /proc/sys/net/ipv4/tcp_ecn 1
    write_val /proc/sys/vm/page-cluster 0
    write_val /proc/sys/vm/stat_interval 15
    for block in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda; do
        [[ -d "$block/queue" ]] || continue
        write_val "$block/queue/iostats" 0
        write_val "$block/queue/add_random" 0
    done
    apply_sched_lib
    choose_tcp_cc
}

case "$SCENE_MODE" in
    restore)
        restore_freq
        exit 0
        ;;
esac

if [[ "$SCENE_RESET" = "1" ]]; then
    restore_freq
    restore_governor
    restore_iosched
    reset_game_mode
    exit 0
fi

if [[ -z "$SCENE_GOVERNOR" ]]; then
    restore_governor
else
    backup_governor
    apply_governor "$SCENE_GOVERNOR"
fi

if [[ -z "$SCENE_IOSCHED" ]]; then
    restore_iosched
else
    backup_iosched
    apply_iosched "$SCENE_IOSCHED"
fi

if [[ "$SCENE_LIMIT_PERCENT" = "0" ]]; then
    restore_freq
else
    apply_freq_limit "$SCENE_LIMIT_PERCENT"
fi

if [[ "$SCENE_LITE" = "1" ]]; then
    case "$SCENE_MODE" in
        performance|fast) restore_min_freq ;;
    esac
fi

if [[ "$SCENE_PID" = "1" ]] && [[ -n "$SCENE_GAME_PKG" ]]; then
    apply_game_priority "$SCENE_GAME_PKG"
fi

if [[ "$SCENE_GAME_RESET" = "1" ]]; then
    reset_game_mode
elif [[ -n "$SCENE_GAME_PKG" ]]; then
    apply_game_mode "$SCENE_GAME_PKG" "${SCENE_GAME_DOWNSCALE:-0}" "${SCENE_GAME_FPS:-0}"
fi

if [[ "$SCENE_EXTRA_TWEAKS" = "1" ]]; then
    apply_extra_tweaks
fi

exit 0
