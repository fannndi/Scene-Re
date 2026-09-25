#!/system/bin/sh

# Scene profile options applier.
#
# Runs after every powercfg mode switch and applies the extra tuning layer that
# the platform scripts do not own: frequency limiter, lite mode, governor and
# I/O scheduler preferences, game process priority, and optional system tweaks.
#
# Environment (all optional, set by ProfileOptions.kt):
#   SCENE_MODE            init|powersave|balance|performance|fast
#   SCENE_LIMIT_PERCENT   0 = off, otherwise 20..100 (% of cpuinfo_max_freq)
#   SCENE_GPU_LIMIT       0 = off, otherwise 20..100 (% of the top Adreno OPP)
#   SCENE_LITE            1 = floor the minimum frequency at the middle OPP on performance modes
#   SCENE_GUARD_ONLY      1 = only apply/release the thermal guard layer, then exit
#   SCENE_GUARD           1 = cap CPU/GPU while the battery runs hot
#   SCENE_GUARD_PERCENT   cap used by the thermal guard (default 70)
#   SCENE_GOVERNOR        e.g. schedutil / walt / performance (empty = leave)
#   SCENE_IOSCHED         e.g. none / mq-deadline / kyber / bfq (empty = leave)
#   SCENE_PID             1 = raise game process priority
#   SCENE_GAME_PKG        package whose PIDs get prioritised
#   SCENE_EXTRA_TWEAKS    1 = apply the reversible kernel/network/VM/IO extras
#   SCENE_GAME_DOWNSCALE  0 = off, otherwise 50..100 (% resolution scale)
#   SCENE_GAME_FPS        0 = off, otherwise target frame rate (30..120)
#   SCENE_GAME_RESET      1 = drop the game mode overlay (game left)
#   SCENE_DROP_CACHES     1 = clear the page cache when a game starts
#   SCENE_GOV_TUNES       1 = WALT / schedhorizon response tuning (AZenith)
#   SCENE_STOP_TRACE      1 = drop buffered traces and stop framework tracing
#   SCENE_STOP_LOGGERS    1 = stop the log/trace/stat logger services
#   SCENE_SDK             Build.VERSION.SDK_INT
#   SCENE_RESET           1 = undo limiter/lite pinning (mode left / disabled)
#
# Tunables touched by SCENE_EXTRA_TWEAKS are snapshotted into
# vtools.scene.tweak.bak.* on first use and restored when the option is off.

BUSYBOX="${BUSYBOX:-busybox}"

# Shared read/write/tunable helpers (write_val, read_val, nearest_freq,
# mid_freq, snapshot_tunable, apply_tunable, restore_tunable) live in the
# common lib sourced by both option scripts.
. "$(dirname "$0")/scene_tune_lib.sh"

# Thermal guard fast path: the session tracker toggles the guard through props
# and runs only this section, leaving every other layer untouched.
if [[ "$SCENE_GUARD_ONLY" = "1" ]]; then
    if [[ "$SCENE_GUARD" = "1" ]]; then
        apply_thermal_guard "${SCENE_GUARD_PERCENT:-$(getprop vtools.scene.guard.percent)}"
    else
        restore_thermal_guard
    fi
    exit 0
fi

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
        name="$(basename "$policy")"
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
        # Keep an active guard's restore target in sync with the new limiter.
        if [[ -n "$(getprop vtools.scene.guard.bak.max.$name)" ]]; then
            setprop vtools.scene.guard.bak.max.$name "$target"
        fi
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
        # A guard still active must restore to the released value.
        if [[ -n "$prop_max" ]] && [[ -n "$(getprop vtools.scene.guard.bak.max.$name)" ]]; then
            setprop vtools.scene.guard.bak.max.$name "$prop_max"
        fi
    done
}

# --- GPU limiter (kgsl devfreq, snapped to the kernel OPP table) ------------

GPU_DIR="/sys/class/kgsl/kgsl-3d0"
GPU_CAP_PROP="vtools.scene.gpu.cap"

gpu_avail_freqs() {
    local avail
    avail="$(read_val "$GPU_DIR/devfreq/available_frequencies")"
    [[ -z "$avail" ]] && avail="$(read_val "$GPU_DIR/gpu_available_frequencies")"
    echo "$avail"
}

# $1 = percent; echoes the nearest supported Adreno OPP
gpu_cap_freq() {
    local percent="$1"
    local avail maxf target
    avail="$(gpu_avail_freqs)"
    [[ -z "$avail" ]] && return 0
    maxf="$(echo "$avail" | tr ' ' '\n' | grep -v '^[[:space:]]*$' | sort -n | tail -n 1)"
    [[ -z "$maxf" ]] && return 0
    target=$(( maxf / 100 * percent ))
    nearest_freq "$avail" "$target"
}

gpu_backup_freq() {
    local prop_max="vtools.scene.gpufreq.bak.max"
    local prop_min="vtools.scene.gpufreq.bak.min"
    if [[ "$(getprop $prop_max)" = "" ]]; then
        setprop $prop_max "$(read_val "$GPU_DIR/devfreq/max_freq")"
    fi
    if [[ "$(getprop $prop_min)" = "" ]]; then
        setprop $prop_min "$(read_val "$GPU_DIR/devfreq/min_freq")"
    fi
}

apply_gpu_limit() {
    local percent="$1"
    local target curmin
    [[ -z "$percent" ]] && return 0
    [[ "$percent" = "0" ]] && return 0
    [[ "$percent" -lt 20 ]] && percent=20
    [[ "$percent" -gt 100 ]] && percent=100
    [[ -d "$GPU_DIR" ]] || return 0
    target="$(gpu_cap_freq "$percent")"
    [[ -z "$target" ]] && return 0
    gpu_backup_freq
    curmin="$(read_val "$GPU_DIR/devfreq/min_freq")"
    if [[ -n "$curmin" ]] && [[ "$curmin" -gt "$target" ]]; then
        write_val "$GPU_DIR/devfreq/min_freq" "$target"
    fi
    write_val "$GPU_DIR/devfreq/max_freq" "$target"
    setprop vtools.scene.gpu.limit.max "$target"
    setprop "$GPU_CAP_PROP" "$target"
    # Keep an active guard's restore target in sync with the new limiter.
    if [[ -n "$(getprop vtools.scene.guard.bak.gpu.max)" ]]; then
        setprop vtools.scene.guard.bak.gpu.max "$target"
    fi
}

restore_gpu_freq() {
    local prop_max prop_min
    prop_max="$(getprop vtools.scene.gpufreq.bak.max)"
    prop_min="$(getprop vtools.scene.gpufreq.bak.min)"
    [[ -n "$prop_max" ]] && write_val "$GPU_DIR/devfreq/max_freq" "$prop_max"
    [[ -n "$prop_min" ]] && write_val "$GPU_DIR/devfreq/min_freq" "$prop_min"
    if [[ -n "$prop_max" ]] && [[ -n "$(getprop vtools.scene.guard.bak.gpu.max)" ]]; then
        setprop vtools.scene.guard.bak.gpu.max "$prop_max"
    fi
    setprop vtools.scene.gpu.limit.max ""
    setprop "$GPU_CAP_PROP" ""
}

# --- Thermal guard layer ----------------------------------------------------
# Caps CPU/GPU to a percentage of the stock OPP while the battery runs hot.
# The pre-guard caps are snapshotted once and restored on release, so the
# user limiter (or the kernel default) comes back untouched.

guard_cpu_target() {
    local policy="$1"
    local percent="$2"
    local maxf avail target curmax
    maxf="$(read_val "$policy/cpuinfo_max_freq")"
    [[ -z "$maxf" ]] && return 0
    target=$(( maxf / 100 * percent ))
    avail="$(read_val "$policy/scaling_available_frequencies")"
    [[ -n "$avail" ]] && target="$(nearest_freq "$avail" "$target")"
    curmax="$(read_val "$policy/scaling_max_freq")"
    if [[ -n "$curmax" ]] && [[ "$curmax" -lt "$target" ]]; then
        target="$curmax"
    fi
    echo "$target"
}

apply_thermal_guard() {
    local percent="$1"
    local policy name prop cur target
    [[ -z "$percent" ]] && percent=70
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        target="$(guard_cpu_target "$policy" "$percent")"
        [[ -z "$target" ]] && continue
        prop="vtools.scene.guard.bak.max.$name"
        if [[ "$(getprop $prop)" = "" ]]; then
            cur="$(read_val "$policy/scaling_max_freq")"
            [[ -n "$cur" ]] && setprop $prop "$cur"
        fi
        write_val "$policy/scaling_max_freq" "$target"
    done
    if [[ -d "$GPU_DIR" ]]; then
        target="$(gpu_cap_freq "$percent")"
        if [[ -n "$target" ]]; then
            if [[ "$(getprop vtools.scene.guard.bak.gpu.max)" = "" ]]; then
                cur="$(read_val "$GPU_DIR/devfreq/max_freq")"
                [[ -n "$cur" ]] && setprop vtools.scene.guard.bak.gpu.max "$cur"
            fi
            write_val "$GPU_DIR/devfreq/max_freq" "$target"
            setprop "$GPU_CAP_PROP" "$target"
        fi
    fi
}

restore_thermal_guard() {
    local policy name prop limit
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        prop="$(getprop vtools.scene.guard.bak.max.$name)"
        if [[ -n "$prop" ]]; then
            write_val "$policy/scaling_max_freq" "$prop"
            setprop vtools.scene.guard.bak.max.$name ""
        fi
    done
    if [[ -n "$(getprop vtools.scene.guard.bak.gpu.max)" ]]; then
        write_val "$GPU_DIR/devfreq/max_freq" "$(getprop vtools.scene.guard.bak.gpu.max)"
        setprop vtools.scene.guard.bak.gpu.max ""
    fi
    # Re-assert the user GPU limiter when one is configured, otherwise release.
    limit="$(getprop vtools.scene.gpu.limit.max)"
    if [[ -n "$limit" ]]; then
        write_val "$GPU_DIR/devfreq/max_freq" "$limit"
        setprop "$GPU_CAP_PROP" "$limit"
    else
        setprop "$GPU_CAP_PROP" ""
    fi
}

# Drop an active guard's restore targets: called when the mode changes so a
# later release restores the new mode's caps instead of the old mode's.
clear_guard_snapshots() {
    local policy name
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        setprop vtools.scene.guard.bak.max.$name ""
    done
    setprop vtools.scene.guard.bak.gpu.max ""
}

# Encore-style lite: floor the minimum frequency at the middle OPP instead of
# releasing it to the stock minimum, so light load stays responsive without
# full pinning. Policies that do not advertise their frequencies fall back to
# the stock minimum, and the floor is never higher than the active cap.
apply_lite_min_freq() {
    local policy name avail mid curmax prop stockmin
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        avail="$policy/scaling_available_frequencies"
        if [[ ! -f "$avail" ]]; then
            prop="vtools.scene.freq.bak.min.$name"
            stockmin="$(getprop $prop)"
            [[ -n "$stockmin" ]] && write_val "$policy/scaling_min_freq" "$stockmin"
            continue
        fi
        mid="$(mid_freq "$avail")"
        [[ -z "$mid" ]] && continue
        backup_freq "$policy"
        curmax="$(read_val "$policy/scaling_max_freq")"
        if [[ -n "$curmax" ]] && [[ "$mid" -gt "$curmax" ]]; then
            mid="$curmax"
        fi
        write_val "$policy/scaling_min_freq" "$mid"
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
    # List merged from Scene's original set and Encore Tweaks (Apache-2.0).
    local libs="libunity.so libil2cpp.so libUE4.so libmain.so libflutter.so libgodot_android.so libcocos2djs.so libmonobdwgc-2.0.so libgdx.so libgdx-box2d.so libminecraftpe.so libLive2DCubismCore.so libyuzu-android.so libryujinx.so libcitra-android.so libhdr_pro_engine.so libandroidx.graphics.path.so libeffect.so"
    if [[ -e /proc/sys/kernel/sched_lib_name ]]; then
        echo "$libs" > /proc/sys/kernel/sched_lib_name 2> /dev/null
        write_val /proc/sys/kernel/sched_lib_mask_force 255
    fi
}

restore_sched_lib() {
    # The kernel default is an empty boost list.
    write_val /proc/sys/kernel/sched_lib_name ""
    write_val /proc/sys/kernel/sched_lib_mask_force 0
}

choose_tcp_cc() {
    # Prefer low-latency congestion control when the kernel ships one.
    local avail cc
    avail="$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2> /dev/null)"
    for cc in bbr3 bbr2 bbrplus bbr westwood cubic; do
        case " $avail " in
            *" $cc "*)
                if [[ "$(getprop vtools.scene.tweak.bak.tcp_cc)" = "" ]]; then
                    setprop vtools.scene.tweak.bak.tcp_cc "$(read_val /proc/sys/net/ipv4/tcp_congestion_control)"
                fi
                write_val /proc/sys/net/ipv4/tcp_congestion_control "$cc"
                return
                ;;
        esac
    done
}

# +---------------------------------------------------------------+
# | Reversible kernel tunables                                    |
# +---------------------------------------------------------------+

apply_sched_features() {
    local node="/sys/kernel/debug/sched_features"
    local mode="${SCENE_MODE:-balance}"
    [[ -d /sys/kernel/debug ]] || return 0
    mount -t debugfs none /sys/kernel/debug 2> /dev/null
    [[ -w "$node" ]] || return 0
    case "$mode" in
        powersave)
            write_val "$node" NO_NEXT_BUDDY
            write_val "$node" NO_TTWU_QUEUE
            ;;
        performance|fast)
            write_val "$node" NEXT_BUDDY
            write_val "$node" NO_TTWU_QUEUE
            ;;
        *)
            write_val "$node" NEXT_BUDDY
            write_val "$node" TTWU_QUEUE
            ;;
    esac
}

restore_sched_features() {
    local node="/sys/kernel/debug/sched_features"
    [[ -w "$node" ]] || return 0
    write_val "$node" NEXT_BUDDY
    write_val "$node" TTWU_QUEUE
}

# Both Encore Tweaks and AZenith force the generic step_wise policy so no
# vendor user-space policy can hold a zone back during games.
apply_thermal_policies() {
    local zone
    for zone in /sys/class/thermal/thermal_zone*; do
        [[ -d "$zone" ]] || continue
        apply_tunable "thermal_policy.$(basename "$zone")" "$zone/policy" step_wise
    done
}

restore_thermal_policies() {
    local zone
    for zone in /sys/class/thermal/thermal_zone*; do
        [[ -d "$zone" ]] || continue
        restore_tunable "thermal_policy.$(basename "$zone")" "$zone/policy"
    done
}

apply_kernel_tunables() {
    apply_tunable split_lock /proc/sys/kernel/split_lock_mitigate 0
    apply_tunable perf_cpu_time /proc/sys/kernel/perf_cpu_time_max_percent 3
    apply_tunable schedstats /proc/sys/kernel/sched_schedstats 0
    apply_tunable autogroup /proc/sys/kernel/sched_autogroup_enabled 0
    apply_tunable child_runs_first /proc/sys/kernel/sched_child_runs_first 1
    apply_tunable nr_migrate /proc/sys/kernel/sched_nr_migrate 32
    apply_tunable migration_cost /proc/sys/kernel/sched_migration_cost_ns 50000
    apply_tunable min_granularity /proc/sys/kernel/sched_min_granularity_ns 1000000
    apply_tunable wakeup_granularity /proc/sys/kernel/sched_wakeup_granularity_ns 1500000
    # Frosty-style extras: shorter CFS period without per-CPU scaling, quieter
    # kernel logging and crash dumps, no NMI watchdog, more inotify slots.
    apply_tunable sched_latency /proc/sys/kernel/sched_latency_ns 5000000
    apply_tunable sched_tunable_scaling /proc/sys/kernel/sched_tunable_scaling 0
    apply_tunable nmi_watchdog /proc/sys/kernel/nmi_watchdog 0
    apply_tunable timer_migration /proc/sys/kernel/timer_migration 0
    apply_tunable printk_devkmsg /proc/sys/kernel/printk_devkmsg off
    apply_tunable ramdumps /sys/module/subsystem_restart/parameters/enable_ramdumps 0
    apply_tunable mini_ramdumps /sys/module/subsystem_restart/parameters/enable_mini_ramdumps 0
    apply_tunable inotify_watches /proc/sys/fs/inotify/max_user_watches 262144
    apply_tunable inotify_instances /proc/sys/fs/inotify/max_user_instances 512
    apply_tunable task_cpustats /proc/sys/kernel/task_cpustats_enable 0
    apply_tunable compaction /proc/sys/vm/compaction_proactiveness 0
    apply_tunable tcp_timestamps /proc/sys/net/ipv4/tcp_timestamps 0
    apply_tunable spi_crc /sys/module/mmc_core/parameters/use_spi_crc 0
    apply_tunable panic /proc/sys/kernel/panic 0
    apply_tunable panic_on_oops /proc/sys/kernel/panic_on_oops 0
    apply_tunable panic_on_warn /proc/sys/kernel/panic_on_warn 0
    apply_tunable softlockup_panic /proc/sys/kernel/softlockup_panic 0
    apply_sched_features
    apply_thermal_policies
}

restore_kernel_tunables() {
    restore_tunable split_lock /proc/sys/kernel/split_lock_mitigate
    restore_tunable perf_cpu_time /proc/sys/kernel/perf_cpu_time_max_percent
    restore_tunable schedstats /proc/sys/kernel/sched_schedstats
    restore_tunable autogroup /proc/sys/kernel/sched_autogroup_enabled
    restore_tunable child_runs_first /proc/sys/kernel/sched_child_runs_first
    restore_tunable nr_migrate /proc/sys/kernel/sched_nr_migrate
    restore_tunable migration_cost /proc/sys/kernel/sched_migration_cost_ns
    restore_tunable min_granularity /proc/sys/kernel/sched_min_granularity_ns
    restore_tunable wakeup_granularity /proc/sys/kernel/sched_wakeup_granularity_ns
    restore_tunable sched_latency /proc/sys/kernel/sched_latency_ns
    restore_tunable sched_tunable_scaling /proc/sys/kernel/sched_tunable_scaling
    restore_tunable nmi_watchdog /proc/sys/kernel/nmi_watchdog
    restore_tunable timer_migration /proc/sys/kernel/timer_migration
    restore_tunable printk_devkmsg /proc/sys/kernel/printk_devkmsg
    restore_tunable ramdumps /sys/module/subsystem_restart/parameters/enable_ramdumps
    restore_tunable mini_ramdumps /sys/module/subsystem_restart/parameters/enable_mini_ramdumps
    restore_tunable inotify_watches /proc/sys/fs/inotify/max_user_watches
    restore_tunable inotify_instances /proc/sys/fs/inotify/max_user_instances
    restore_tunable task_cpustats /proc/sys/kernel/task_cpustats_enable
    restore_tunable compaction /proc/sys/vm/compaction_proactiveness
    restore_tunable tcp_timestamps /proc/sys/net/ipv4/tcp_timestamps
    restore_tunable spi_crc /sys/module/mmc_core/parameters/use_spi_crc
    restore_tunable panic /proc/sys/kernel/panic
    restore_tunable panic_on_oops /proc/sys/kernel/panic_on_oops
    restore_tunable panic_on_warn /proc/sys/kernel/panic_on_warn
    restore_tunable softlockup_panic /proc/sys/kernel/softlockup_panic
    restore_sched_features
    restore_thermal_policies
    local cc
    cc="$(getprop vtools.scene.tweak.bak.tcp_cc)"
    [[ -n "$cc" ]] && write_val /proc/sys/net/ipv4/tcp_congestion_control "$cc"
    restore_sched_lib
}

# The vendor battery_saver kernel module (custom kernels) is disabled while
# the profile is not powersave and enabled again in powersave, mirroring
# Encore's per-profile handling. Written as digits or Y/N depending on what
# the module exposes.
apply_battery_saver_module() {
    local want="$1"
    local node="/sys/module/battery_saver/parameters/enabled"
    [[ -e "$node" ]] || return 0
    snapshot_tunable battery_saver "$node"
    if grep -q '[0-9]' "$node" 2> /dev/null; then
        write_val "$node" "$want"
    elif [[ "$want" = "1" ]]; then
        write_val "$node" Y
    else
        write_val "$node" N
    fi
}

# Mode-aware tunables, matching Encore's performance / balance / powersave
# split instead of one global value.
apply_mode_tunables() {
    local mode="${SCENE_MODE:-balance}"
    local block vfs read_ahead nr stune_prefer stune_boost saver
    case "$mode" in
        performance|fast)
            vfs=80
            read_ahead=32
            nr=32
            stune_prefer=1
            stune_boost=1
            saver=0
            ;;
        light)
            # Light games: keep top-app on idle CPUs, no stune boost.
            vfs=100
            read_ahead=64
            nr=48
            stune_prefer=1
            stune_boost=0
            saver=0
            ;;
        powersave)
            vfs=120
            read_ahead=128
            nr=64
            stune_prefer=1
            stune_boost=0
            saver=1
            ;;
        *)
            vfs=120
            read_ahead=128
            nr=64
            stune_prefer=0
            stune_boost=0
            saver=0
            ;;
    esac
    apply_tunable vfs_pressure /proc/sys/vm/vfs_cache_pressure "$vfs"
    apply_tunable stune_prefer_idle /dev/stune/top-app/schedtune.prefer_idle "$stune_prefer"
    apply_tunable stune_boost /dev/stune/top-app/schedtune.boost "$stune_boost"
    # AZenith: the power-efficient workqueue pool is off while gaming and on
    # again for the frugal profiles.
    if [[ "$mode" = "performance" || "$mode" = "fast" ]]; then
        apply_tunable wq_power_efficient /sys/module/workqueue/parameters/power_efficient N
    else
        apply_tunable wq_power_efficient /sys/module/workqueue/parameters/power_efficient Y
    fi
    for block in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda; do
        [[ -d "$block/queue" ]] || continue
        apply_tunable "read_ahead.$(basename "$block")" "$block/queue/read_ahead_kb" "$read_ahead"
        apply_tunable "nr_requests.$(basename "$block")" "$block/queue/nr_requests" "$nr"
    done
    apply_battery_saver_module "$saver"
}

restore_mode_tunables() {
    local block
    restore_tunable vfs_pressure /proc/sys/vm/vfs_cache_pressure
    restore_tunable stune_prefer_idle /dev/stune/top-app/schedtune.prefer_idle
    restore_tunable stune_boost /dev/stune/top-app/schedtune.boost
    restore_tunable wq_power_efficient /sys/module/workqueue/parameters/power_efficient
    restore_tunable battery_saver /sys/module/battery_saver/parameters/enabled
    for block in /sys/block/mmcblk0 /sys/block/mmcblk1 /sys/block/sda; do
        [[ -d "$block/queue" ]] || continue
        restore_tunable "read_ahead.$(basename "$block")" "$block/queue/read_ahead_kb"
        restore_tunable "nr_requests.$(basename "$block")" "$block/queue/nr_requests"
    done
}

# Governor response tuning (AZenith walttunes / schedtunes): richer WALT
# response curves and schedhorizon ramp hints. schedutil rate limits stay with
# the powercfg profiles, which already tune them per mode.
apply_gov_tunes() {
    local policy name avail selected count highest second tloads cur n i delay walt sh
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        avail="$(read_val "$policy/scaling_available_frequencies")"
        [[ -z "$avail" ]] && continue

        selected="$(echo "$avail" | tr ' ' '\n' | grep -v '^[[:space:]]*$' | sort -rn | head -n 6 | tr '\n' ' ' | sed 's/ $//')"
        count="$(echo "$selected" | wc -w)"
        [[ "$count" -eq 0 ]] && continue

        walt="$policy/walt"
        if [[ -d "$walt" ]]; then
            highest="$(echo "$selected" | tr ' ' '\n' | head -n 1)"
            second="$(echo "$selected" | tr ' ' '\n' | sed -n 2p)"
            [[ -z "$second" ]] && second="$highest"
            tloads=""
            cur=95
            n=0
            while [[ $n -lt $count ]]; do
                tloads="$tloads $cur"
                cur=$(( cur - 8 ))
                [[ $cur -lt 10 ]] && cur=10
                n=$(( n + 1 ))
            done
            apply_tunable "walt_hispeed_load.$name" "$walt/hispeed_load" 92
            apply_tunable "walt_hispeed_freq.$name" "$walt/hispeed_freq" "$second"
            apply_tunable "walt_rtg_boost.$name" "$walt/rtg_boost_freq" "$highest"
            apply_tunable "walt_target_loads.$name" "$walt/target_loads" "${tloads# }"
            apply_tunable "walt_efficient.$name" "$walt/efficient_freq" "$selected"
            apply_tunable "walt_up_rate.$name" "$walt/up_rate_limit_us" 8000
            apply_tunable "walt_down_rate.$name" "$walt/down_rate_limit_us" 12000
        fi

        sh="$policy/schedhorizon"
        if [[ -d "$sh" ]]; then
            delay=""
            i=1
            while [[ $i -le $count ]]; do
                delay="$delay $(( 50 * i ))"
                i=$(( i + 1 ))
            done
            apply_tunable "sh_up_delay.$name" "$sh/up_delay" "${delay# }"
            apply_tunable "sh_efficient.$name" "$sh/efficient_freq" "$selected"
            if [[ -e "$sh/up_rate_limit_us" ]]; then
                apply_tunable "sh_up_rate.$name" "$sh/up_rate_limit_us" 6500
                apply_tunable "sh_down_rate.$name" "$sh/down_rate_limit_us" 12000
            elif [[ -e "$sh/rate_limit_us" ]]; then
                apply_tunable "sh_rate.$name" "$sh/rate_limit_us" 7000
            fi
        fi
    done
}

restore_gov_tunes() {
    local policy name
    for policy in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$policy" ]] || continue
        name="$(basename "$policy")"
        restore_tunable "walt_hispeed_load.$name" "$policy/walt/hispeed_load"
        restore_tunable "walt_hispeed_freq.$name" "$policy/walt/hispeed_freq"
        restore_tunable "walt_rtg_boost.$name" "$policy/walt/rtg_boost_freq"
        restore_tunable "walt_target_loads.$name" "$policy/walt/target_loads"
        restore_tunable "walt_efficient.$name" "$policy/walt/efficient_freq"
        restore_tunable "walt_up_rate.$name" "$policy/walt/up_rate_limit_us"
        restore_tunable "walt_down_rate.$name" "$policy/walt/down_rate_limit_us"
        restore_tunable "sh_up_delay.$name" "$policy/schedhorizon/up_delay"
        restore_tunable "sh_efficient.$name" "$policy/schedhorizon/efficient_freq"
        restore_tunable "sh_up_rate.$name" "$policy/schedhorizon/up_rate_limit_us"
        restore_tunable "sh_down_rate.$name" "$policy/schedhorizon/down_rate_limit_us"
        restore_tunable "sh_rate.$name" "$policy/schedhorizon/rate_limit_us"
    done
}

# Framework tracing stop (AZenith disabletrace): drop buffered traces, turn
# off the record options and stop the framework tracing sessions.
apply_stop_trace() {
    local t
    for t in /sys/kernel/tracing/trace /sys/kernel/debug/tracing/trace; do
        [[ -e "$t" ]] && : > "$t" 2> /dev/null
    done
    if [[ -e /sys/kernel/tracing/options/overwrite ]]; then
        apply_tunable trace_overwrite /sys/kernel/tracing/options/overwrite 0
        apply_tunable trace_record_tgids /sys/kernel/tracing/options/record-tgids 0
    fi
    cmd window tracing stop 2> /dev/null
    cmd window tracing size 0 2> /dev/null
    cmd input_method tracing stop 2> /dev/null
    cmd accessibility stop-trace 2> /dev/null
}

restore_stop_trace() {
    restore_tunable trace_overwrite /sys/kernel/tracing/options/overwrite
    restore_tunable trace_record_tgids /sys/kernel/tracing/options/record-tgids
}

# Logger services (AZenith disable_logging): stopped while the option is on
# and started again when it turns off. Note that SceneLog / `adb logcat`
# produces nothing while these are stopped. A prop guards the restart so we
# only start services that this script actually stopped.
SCENE_LOGGERS="logd traced statsd tcpdump cnss_diag subsystem_ramdump charge_logger wlan_logging"

apply_stop_loggers() {
    local logger
    for logger in $SCENE_LOGGERS; do
        stop "$logger" 2> /dev/null
    done
    setprop vtools.scene.loggers.stopped 1
}

restore_stop_loggers() {
    local logger
    [[ "$(getprop vtools.scene.loggers.stopped)" = "1" ]] || return 0
    for logger in $SCENE_LOGGERS; do
        start "$logger" 2> /dev/null
    done
    setprop vtools.scene.loggers.stopped 0
}

apply_extra_tweaks() {
    local block
    apply_tunable tcp_fastopen /proc/sys/net/ipv4/tcp_fastopen 3
    apply_tunable tcp_low_latency /proc/sys/net/ipv4/tcp_low_latency 1
    apply_tunable tcp_ecn /proc/sys/net/ipv4/tcp_ecn 1
    apply_tunable page_cluster /proc/sys/vm/page-cluster 0
    apply_tunable stat_interval /proc/sys/vm/stat_interval 15
    for block in /sys/block/*; do
        [[ -d "$block/queue" ]] || continue
        apply_tunable "iostats.$(basename "$block")" "$block/queue/iostats" 0
        apply_tunable "add_random.$(basename "$block")" "$block/queue/add_random" 0
    done
    apply_kernel_tunables
    apply_mode_tunables
    apply_sched_lib
    choose_tcp_cc
}

restore_extra_tweaks() {
    local block
    restore_tunable tcp_fastopen /proc/sys/net/ipv4/tcp_fastopen
    restore_tunable tcp_low_latency /proc/sys/net/ipv4/tcp_low_latency
    restore_tunable tcp_ecn /proc/sys/net/ipv4/tcp_ecn
    restore_tunable page_cluster /proc/sys/vm/page-cluster
    restore_tunable stat_interval /proc/sys/vm/stat_interval
    for block in /sys/block/*; do
        [[ -d "$block/queue" ]] || continue
        restore_tunable "iostats.$(basename "$block")" "$block/queue/iostats"
        restore_tunable "add_random.$(basename "$block")" "$block/queue/add_random"
    done
    restore_kernel_tunables
    restore_mode_tunables
}

if [[ "$SCENE_RESET" = "1" ]]; then
    restore_freq
    setprop vtools.scene.freq.limited ""
    restore_gpu_freq
    setprop vtools.scene.gpu.limited ""
    restore_thermal_guard
    setprop vtools.scene.guard.active 0
    restore_governor
    restore_iosched
    restore_extra_tweaks
    restore_gov_tunes
    restore_stop_trace
    restore_stop_loggers
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

if [[ -z "$SCENE_LIMIT_PERCENT" || "$SCENE_LIMIT_PERCENT" = "0" ]]; then
    # Only undo the cap on the turn-off transition. Restoring on every apply
    # would stomp the platform profile's per-mode frequency limits (the
    # backup holds the hardware max, not what the profile just wrote).
    if [[ "$(getprop vtools.scene.freq.limited)" = "1" ]]; then
        restore_freq
        setprop vtools.scene.freq.limited ""
    fi
else
    apply_freq_limit "$SCENE_LIMIT_PERCENT"
    setprop vtools.scene.freq.limited 1
fi

if [[ -z "$SCENE_GPU_LIMIT" ]] || [[ "$SCENE_GPU_LIMIT" = "0" ]]; then
    # Same rule as the CPU limiter: restore only on the turn-off transition,
    # otherwise every apply would stomp the profile / boost / manual writes.
    if [[ "$(getprop vtools.scene.gpu.limited)" = "1" ]]; then
        restore_gpu_freq
        setprop vtools.scene.gpu.limited ""
    fi
else
    apply_gpu_limit "$SCENE_GPU_LIMIT"
    setprop vtools.scene.gpu.limited 1
fi

if [[ "$SCENE_LITE" = "1" ]]; then
    case "$SCENE_MODE" in
        performance|fast) apply_lite_min_freq ;;
    esac
fi

if [[ "$SCENE_PID" = "1" ]] && [[ -n "$SCENE_GAME_PKG" ]]; then
    apply_game_priority "$SCENE_GAME_PKG"
fi

if [[ "$SCENE_GAME_RESET" = "1" ]]; then
    reset_game_mode
elif [[ -n "$SCENE_GAME_PKG" ]]; then
    apply_game_mode "$SCENE_GAME_PKG" "${SCENE_GAME_DOWNSCALE:-0}" "${SCENE_GAME_FPS:-0}"
    if [[ "$SCENE_DROP_CACHES" = "1" ]]; then
        write_val /proc/sys/vm/drop_caches 3
    fi
fi

if [[ "$SCENE_EXTRA_TWEAKS" = "1" ]]; then
    apply_extra_tweaks
else
    restore_extra_tweaks
fi

if [[ "$SCENE_GOV_TUNES" = "1" ]]; then
    apply_gov_tunes
else
    restore_gov_tunes
fi

if [[ "$SCENE_STOP_TRACE" = "1" ]]; then
    apply_stop_trace
else
    restore_stop_trace
fi

if [[ "$SCENE_STOP_LOGGERS" = "1" ]]; then
    apply_stop_loggers
else
    restore_stop_loggers
fi

# A mode switch re-wrote the frequency caps: refresh an active guard's restore
# targets to the new mode's values (a guard-only run exits earlier and keeps
# its snapshots, and a re-apply on the same mode changes nothing).
prev_mode="$(getprop vtools.scene.mode.last)"
if [[ "$prev_mode" != "${SCENE_MODE:-}" ]]; then
    clear_guard_snapshots
    setprop vtools.scene.mode.last "$SCENE_MODE"
fi

# A mode switch must keep an active thermal guard in place.
if [[ "$(getprop vtools.scene.guard.active)" = "1" ]]; then
    apply_thermal_guard "$(getprop vtools.scene.guard.percent)"
fi

exit 0
