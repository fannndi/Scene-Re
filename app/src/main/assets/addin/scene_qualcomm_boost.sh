#!/system/bin/sh

# Scene Qualcomm (bus_dcvs / devfreq / kgsl) boost applier.
#
# Concept adapted from Encore Tweaks' Snapdragon profile (Apache-2.0),
# reimplemented for a module-less root environment.
#
# Runs after scene_profile_options.sh on every mode switch:
#   performance|fast -> max bus + DRAM latency nodes, GPU pinned high
#   lite mode        -> mid floor on the same nodes, GPU left to scale up
#   balance|init     -> unlock (kernel defaults)
#   powersave        -> unlock, plus an optional GPU low pin
#
# Environment (set by ProfileOptions.kt):
#   SCENE_MODE        init|powersave|balance|performance|fast|restore
#   SCENE_LITE        1 = mid floor instead of max on performance modes
#   SCENE_QCOM_BUS    1 = manage devfreq latency + bus_dcvs nodes
#   SCENE_QCOM_GPU    1 = manage the kgsl devfreq / power levels
#   SCENE_QCOM_GPU_PS 1 = pin the GPU low while in powersave mode

# Shared read/write/frequency helpers (write_val, read_val, max_freq,
# min_freq, mid_freq) live in the common lib sourced by both option scripts.
. "$(dirname "$0")/scene_tune_lib.sh"

# $1 = devfreq directory, $2 = max|mid|min|unlock
set_devfreq() {
    local path="$1"
    local action="$2"
    local avail="$path/available_frequencies"
    local max min mid
    [[ -f "$avail" ]] || return 0
    max="$(max_freq "$avail")"
    min="$(min_freq "$avail")"
    if [[ -z "$max" ]] || [[ -z "$min" ]]; then
        return 0
    fi
    case "$action" in
        max)
            write_val "$path/max_freq" "$max"
            write_val "$path/min_freq" "$max"
            ;;
        mid)
            mid="$(mid_freq "$avail")"
            write_val "$path/max_freq" "$max"
            write_val "$path/min_freq" "${mid:-$min}"
            ;;
        min)
            write_val "$path/min_freq" "$min"
            write_val "$path/max_freq" "$min"
            ;;
        *)
            write_val "$path/max_freq" "$max"
            write_val "$path/min_freq" "$min"
            ;;
    esac
}

# $1 = bus_dcvs component directory, $2 = max|mid|min|unlock
# Three layouts are written, guarded by file existence: Encore's direct
# hw_max_freq/hw_min_freq, AZenith's one-level-deeper max_freq/min_freq, and
# plain max_freq/min_freq directly under the component.
set_bus_component() {
    local path="$1"
    local action="$2"
    local avail="$path/available_frequencies"
    local max min mid tmax tmin sub
    [[ -f "$avail" ]] || return 0
    max="$(max_freq "$avail")"
    min="$(min_freq "$avail")"
    if [[ -z "$max" ]] || [[ -z "$min" ]]; then
        return 0
    fi
    case "$action" in
        max)
            tmax="$max"
            tmin="$max"
            ;;
        mid)
            mid="$(mid_freq "$avail")"
            tmax="$max"
            tmin="${mid:-$min}"
            ;;
        min)
            tmax="$min"
            tmin="$min"
            ;;
        *)
            tmax="$max"
            tmin="$min"
            ;;
    esac
    write_val "$path/hw_max_freq" "$tmax"
    write_val "$path/hw_min_freq" "$tmin"
    write_val "$path/max_freq" "$tmax"
    write_val "$path/min_freq" "$tmin"
    for sub in "$path"/*/; do
        [[ -d "$sub" ]] || continue
        write_val "$sub/max_freq" "$tmax"
        write_val "$sub/min_freq" "$tmin"
    done
}

# $1 = devfreq directory, $2 = governor name or "unlock"
set_devfreq_governor() {
    local path="$1"
    local target="$2"
    local key node avail
    node="$path/governor"
    [[ -e "$node" ]] || return 0
    # Never write a governor the kernel does not advertise.
    if [[ "$target" != "unlock" ]]; then
        avail="$(read_val "$path/available_governors")"
        if [[ -n "$avail" ]] && [[ " $avail " != *" $target "* ]]; then
            return 0
        fi
    fi
    # Android property names accept only [A-Za-z0-9_.], so sanitise the node
    # name (device-tree names contain '-' and ',').
    key="vtools.scene.devfreq.gov.bak.$(basename "$path" | tr -c 'A-Za-z0-9._' '_')"
    if [[ "$target" = "unlock" ]]; then
        local bak
        bak="$(getprop $key)"
        [[ -n "$bak" ]] && write_val "$node" "$bak"
        return 0
    fi
    if [[ "$(getprop $key)" = "" ]]; then
        setprop $key "$(read_val "$node")"
    fi
    write_val "$node" "$target"
}

# Per-mode devfreq governor selection (AZenith binprofiles snapdragon.rs):
# balance keeps the power friendly defaults, performance forces the governor
# up, powersave takes it down.
apply_devfreq_governors() {
    local state="$1"
    local path target
    for path in /sys/class/devfreq/*cpu-ddr-latfloor*; do
        [[ -d "$path" ]] || continue
        case "$state" in
            perf) target="performance" ;;
            powersave) target="powersave" ;;
            unlock) target="unlock" ;;
            *) target="compute" ;;
        esac
        set_devfreq_governor "$path" "$target"
    done
    for path in /sys/class/devfreq/*cpu*-lat; do
        [[ -d "$path" ]] || continue
        case "$state" in
            perf) target="performance" ;;
            powersave) target="powersave" ;;
            unlock) target="unlock" ;;
            *) target="mem_latency" ;;
        esac
        set_devfreq_governor "$path" "$target"
    done
    for path in /sys/class/devfreq/*cpu-cpu-ddr-bw /sys/class/devfreq/*cpu-llcc-ddr-bw /sys/class/devfreq/*cpu-cpu-llcc-bw; do
        [[ -d "$path" ]] || continue
        case "$state" in
            perf) target="performance" ;;
            powersave) target="powersave" ;;
            unlock) target="unlock" ;;
            *) target="bw_hwmon" ;;
        esac
        set_devfreq_governor "$path" "$target"
    done
    for path in /sys/class/devfreq/*gpubw*; do
        [[ -d "$path" ]] || continue
        case "$state" in
            perf) target="performance" ;;
            powersave) target="powersave" ;;
            unlock) target="unlock" ;;
            *) target="bw_vbif" ;;
        esac
        set_devfreq_governor "$path" "$target"
    done
}

# $1 = kgsl dir, $2 = 0..3 or "unlock" (AZenith adrenoboost levels).
set_adrenoboost() {
    local gpu="$1"
    local value="$2"
    local node bak
    for node in "$gpu/devfreq/adrenoboost" "$gpu/adrenoboost"; do
        [[ -e "$node" ]] || continue
        if [[ "$value" = "unlock" ]]; then
            bak="$(getprop vtools.scene.gpu.adrenoboost.bak)"
            [[ -n "$bak" ]] && write_val "$node" "$bak"
        else
            if [[ "$(getprop vtools.scene.gpu.adrenoboost.bak)" = "" ]]; then
                setprop vtools.scene.gpu.adrenoboost.bak "$(read_val "$node")"
            fi
            write_val "$node" "$value"
        fi
    done
}

list_latency_nodes() {
    local path
    for path in /sys/class/devfreq/*memlat* /sys/class/devfreq/*latfloor* /sys/class/devfreq/*ddr-lat*; do
        [[ -d "$path" ]] && echo "$path"
    done
}

list_bus_components() {
    local component
    for component in DDR DDRQOS LLCC L3; do
        [[ -d "/sys/devices/system/cpu/bus_dcvs/$component" ]] && echo "/sys/devices/system/cpu/bus_dcvs/$component"
    done
}

# The kgsl power levels are device specific, snapshot them once so a later
# unlock restores exactly what the kernel had.
backup_pwrlevel() {
    local gpu="$1"
    local prop
    prop="vtools.scene.gpu.pwrlevel.bak.min"
    if [[ "$(getprop $prop)" = "" ]]; then
        setprop $prop "$(read_val "$gpu/min_pwrlevel")"
    fi
    prop="vtools.scene.gpu.pwrlevel.bak.max"
    if [[ "$(getprop $prop)" = "" ]]; then
        setprop $prop "$(read_val "$gpu/max_pwrlevel")"
    fi
}

restore_pwrlevel() {
    local gpu="$1"
    local min max
    min="$(getprop vtools.scene.gpu.pwrlevel.bak.min)"
    max="$(getprop vtools.scene.gpu.pwrlevel.bak.max)"
    [[ -n "$min" ]] && write_val "$gpu/min_pwrlevel" "$min"
    [[ -n "$max" ]] && write_val "$gpu/max_pwrlevel" "$max"
}

mode="${SCENE_MODE:-balance}"
lite="${SCENE_LITE:-0}"
bus_enabled="${SCENE_QCOM_BUS:-0}"
gpu_enabled="${SCENE_QCOM_GPU:-0}"
gpu_powersave="${SCENE_QCOM_GPU_PS:-0}"

bus_action="unlock"
dcvs_action="unlock"
gov_state="unlock"
gpu_action="unlock"

case "$mode" in
    performance|fast)
        if [[ "$lite" = "1" ]]; then
            bus_action="mid"
            dcvs_action="mid"
            gpu_action="mid"
        else
            bus_action="max"
            dcvs_action="max"
            gpu_action="max"
        fi
        ;;
    powersave)
        # Latency node clocks stay free (the devfreq governor does the work),
        # the DCVS components drop to their lowest OPP like AZenith does.
        dcvs_action="min"
        if [[ "$gpu_powersave" = "1" ]]; then
            gpu_action="min"
        fi
        ;;
esac

if [[ "$bus_enabled" = "1" ]]; then
    case "$mode" in
        performance|fast) gov_state="perf" ;;
        powersave) gov_state="powersave" ;;
        *) gov_state="balance" ;;
    esac
fi

# Pinned-state flags: the release/unlock paths only write when we actually
# pinned something earlier, so a disabled boost never stomps manual kr-script
# DDR/bus_dcvs tuning or values the platform profiles own.
BUS_PIN="vtools.scene.boost.bus"
GPU_PIN="vtools.scene.boost.gpu"

for path in $(list_latency_nodes); do
    if [[ "$bus_enabled" = "1" ]]; then
        set_devfreq "$path" "$bus_action"
    elif [[ "$(getprop $BUS_PIN)" = "1" ]]; then
        set_devfreq "$path" unlock
    fi
done

for path in $(list_bus_components); do
    if [[ "$bus_enabled" = "1" ]]; then
        set_bus_component "$path" "$dcvs_action"
    elif [[ "$(getprop $BUS_PIN)" = "1" ]]; then
        set_bus_component "$path" unlock
    fi
done

if [[ "$bus_enabled" = "1" ]]; then
    setprop $BUS_PIN 1
elif [[ "$(getprop $BUS_PIN)" = "1" ]]; then
    # The devfreq governor snapshots are per node and restore themselves.
    setprop $BUS_PIN ""
fi

apply_devfreq_governors "$gov_state"

gpu="/sys/class/kgsl/kgsl-3d0"
if [[ -d "$gpu" ]]; then
    if [[ "$gpu_enabled" = "1" ]]; then
        setprop $GPU_PIN 1
        case "$gpu_action" in
            max)
                backup_pwrlevel "$gpu"
                set_devfreq "$gpu/devfreq" max
                write_val "$gpu/min_pwrlevel" 0
                write_val "$gpu/max_pwrlevel" 0
                write_val "$gpu/bus_split" 0
                write_val "$gpu/force_clk_on" 1
                set_adrenoboost "$gpu" 3
                ;;
            mid)
                set_devfreq "$gpu/devfreq" mid
                restore_pwrlevel "$gpu"
                write_val "$gpu/bus_split" 1
                write_val "$gpu/force_clk_on" 0
                set_adrenoboost "$gpu" 1
                ;;
            min)
                set_devfreq "$gpu/devfreq" min
                restore_pwrlevel "$gpu"
                write_val "$gpu/bus_split" 1
                write_val "$gpu/force_clk_on" 0
                set_adrenoboost "$gpu" 0
                ;;
            *)
                set_devfreq "$gpu/devfreq" unlock
                restore_pwrlevel "$gpu"
                write_val "$gpu/bus_split" 1
                write_val "$gpu/force_clk_on" 0
                set_adrenoboost "$gpu" unlock
                ;;
        esac
    elif [[ "$(getprop $GPU_PIN)" = "1" ]]; then
        set_devfreq "$gpu/devfreq" unlock
        restore_pwrlevel "$gpu"
        write_val "$gpu/bus_split" 1
        write_val "$gpu/force_clk_on" 0
        set_adrenoboost "$gpu" unlock
        setprop $GPU_PIN ""
    fi
fi

exit 0
