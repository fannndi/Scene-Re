#!/system/bin/sh

# powercfg helpers for the POCO X3 NFC (surya) - Snapdragon 732G (SM7150-AC).
# Kernel: msm-4.14 (Xiaomi surya A10 / CLO LA.UM.9.1 A11+), ro.board.platform=sm6150.
#
# Nothing below assumes a fixed node name or OPP table: CPU/GPU frequencies are
# snapped to what the running kernel advertises, governors are picked from
# scaling_available_governors / available_governors, and every write is guarded
# by node existence, so the same profile works across kernel variants.
#
# Stock CPU OPP tables (SM7150-AC):
#   policy0 - 6x Kryo 470 Silver:
#     300000 576000 768000 1017600 1248000 1324800 1497600 1612800 1708800 1804800
#   policy6 - 2x Kryo 470 Gold:
#     300000 652800 806400 979200 1094400 1209600 1324800 1555200 1708800
#     1843200 1939200 2169600 2208000 2304000
#   kgsl pwrlevels - Adreno 618: 825 800 650 565 430 355 267 180 MHz
#
# Kernel notes (verified against the surya A10 and CLO A11 trees):
#   sched_upmigrate / sched_downmigrate take one percentage (1..100) and need
#   upmigrate > downmigrate; sched_group_*migrate are percentages as well.
#   There is no sched_boost_top_app node.
#   cpu-boost: input_boost_freq / input_boost_ms / sched_boost_on_input exist
#   in both trees; the powerkey_* parameters are guarded.
#   kgsl: min/max/default_pwrlevel, num_pwrlevels, bus_split, force_clk_on;
#   adrenoboost only exists on some custom kernels (guarded elsewhere).
#   UFS clkscale/clkgate/hibern8 attributes are vendor patches (guarded).
#   DDR/LLCC bandwidth devfreq nodes are discovered by glob.

gpu_dir="/sys/class/kgsl/kgsl-3d0"
gpu_freqs="$(cat "$gpu_dir/devfreq/available_frequencies" 2>/dev/null)"
[[ -z "$gpu_freqs" ]] && gpu_freqs="$(cat "$gpu_dir/gpu_available_frequencies" 2>/dev/null)"
gpu_max_freq=""
gpu_min_freq=""
gpu_min_pl=0
gpu_max_pl=0
num_pwrlevels="$(cat "$gpu_dir/num_pwrlevels" 2>/dev/null)"

for freq in $gpu_freqs; do
  if [[ -z "$gpu_max_freq" || $freq -gt $gpu_max_freq ]]; then
    gpu_max_freq=$freq
  fi
  if [[ -z "$gpu_min_freq" || $freq -lt $gpu_min_freq ]]; then
    gpu_min_freq=$freq
  fi
done

if [[ -n "$num_pwrlevels" ]]; then
  gpu_min_pl=`expr $num_pwrlevels - 1`
fi
if [[ "$gpu_min_pl" -lt 0 ]]; then
  gpu_min_pl=0
fi

# --- generic helpers --------------------------------------------------------

write_node() {
  # $1 = value, $2 = node; unsupported nodes are skipped silently
  [[ -e "$2" ]] || return 0
  chmod 0664 "$2" 2> /dev/null
  echo "$1" > "$2" 2> /dev/null
}

set_value() {
  value=$1
  path=$2
  if [[ -f $path ]]; then
    current_value="$(cat $path)"
    if [[ ! "$current_value" = "$value" ]]; then
      chmod 0664 "$path" 2> /dev/null
      echo "$value" > "$path" 2> /dev/null
    fi
  fi
}

max_of() {
  local m=""
  local f
  for f in $1; do
    [[ -z "$m" || $f -gt $m ]] && m=$f
  done
  echo "$m"
}

min_of() {
  local m=""
  local f
  for f in $1; do
    [[ -z "$m" || $f -lt $m ]] && m=$f
  done
  echo "$m"
}

# $1 = cpufreq policy (0|6); echoes a governor the kernel advertises
pick_cpu_governor() {
  local avail
  local g
  avail="$(cat /sys/devices/system/cpu/cpufreq/policy$1/scaling_available_governors 2>/dev/null)"
  for g in schedutil interactive ondemand conservative powersave performance; do
    if [[ -z "$avail" || " $avail " == *" $g "* ]]; then
      echo "$g"
      return
    fi
  done
  echo ""
}

# $1 = cpufreq policy, $2 = target kHz; echoes the nearest supported OPP
snap_cpu_freq() {
  local policy="$1"
  local target="$2"
  local avail
  local best="" bestdiff="" f diff
  if [[ "$target" -eq 0 ]]; then
    echo 0
    return
  fi
  avail="$(cat /sys/devices/system/cpu/cpufreq/policy$policy/scaling_available_frequencies 2>/dev/null)"
  if [[ -z "$avail" ]]; then
    echo "$target"
    return
  fi
  for f in $avail; do
    diff=$(( f > target ? f - target : target - f ))
    if [[ -z "$best" ]] || [[ $diff -lt $bestdiff ]]; then
      best=$f
      bestdiff=$diff
    fi
  done
  echo "${best:-$target}"
}

# --- boot-stock snapshot (mode "off" restore target) ------------------------
# Captured once per boot from powercfg-base.sh, before Scene tunes anything.
# The path list must stay stable between snapshot and restore: indices always
# advance, existence is checked separately, so a node that appears or
# disappears later (schedutil tuning files follow the governor) cannot shift
# the mapping.

stock_paths() {
    local p
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
        [[ -d "$p" ]] || continue
        echo "$p/scaling_min_freq"
        echo "$p/scaling_max_freq"
        echo "$p/scaling_governor"
        echo "$p/schedutil/down_rate_limit_us"
        echo "$p/schedutil/up_rate_limit_us"
        echo "$p/schedutil/hispeed_freq"
        echo "$p/schedutil/hispeed_load"
    done
    for p in /sys/devices/system/cpu/cpu0/core_ctl /sys/devices/system/cpu/cpu6/core_ctl; do
        [[ -d "$p" ]] || continue
        echo "$p/enable"
        echo "$p/min_cpus"
        echo "$p/max_cpus"
        echo "$p/busy_up_thres"
        echo "$p/busy_down_thres"
        echo "$p/offline_delay_ms"
        echo "$p/not_preferred"
        echo "$p/task_thres"
    done
    echo "/proc/sys/kernel/sched_upmigrate"
    echo "/proc/sys/kernel/sched_downmigrate"
    echo "/proc/sys/kernel/sched_group_upmigrate"
    echo "/proc/sys/kernel/sched_group_downmigrate"
    echo "/proc/sys/kernel/sched_walt_rotate_big_tasks"
    echo "/proc/sys/kernel/sched_boost"
    echo "/proc/sys/kernel/sched_latency_ns"
    echo "/proc/sys/kernel/sched_min_granularity_ns"
    echo "/sys/devices/system/cpu/cpu6/sched_load_boost"
    echo "/sys/devices/system/cpu/cpu7/sched_load_boost"
    echo "/sys/module/cpu_boost/parameters/input_boost_freq"
    echo "/sys/module/cpu_boost/parameters/input_boost_ms"
    echo "/sys/module/cpu_boost/parameters/powerkey_input_boost_freq"
    echo "/sys/module/cpu_boost/parameters/powerkey_input_boost_ms"
    echo "/sys/module/cpu_boost/parameters/sched_boost_on_powerkey_input"
    echo "/sys/module/cpu_boost/parameters/sched_boost_on_input"
    echo "/sys/module/lpm_levels/parameters/sleep_disabled"
    echo "/proc/sys/vm/dirty_background_ratio"
    echo "/proc/sys/vm/dirty_ratio"
    echo "/proc/sys/vm/overcommit_ratio"
    echo "/proc/sys/vm/swap_ratio"
    echo "/proc/sys/vm/vfs_cache_pressure"
    echo "/proc/sys/vm/page-cluster"
    echo "/proc/sys/vm/dirty_expire_centisecs"
    echo "/proc/sys/vm/dirty_writeback_centisecs"
    echo "/sys/module/lowmemorykiller/parameters/enable_adaptive_lmk"
    for p in /sys/block/sd*; do
        [[ -d "$p/queue" ]] || continue
        echo "$p/queue/read_ahead_kb"
        echo "$p/queue/nr_requests"
    done
    echo "/dev/cpuset/background/cpus"
    echo "/dev/cpuset/system-background/cpus"
    echo "/dev/cpuset/foreground/cpus"
    echo "/dev/cpuset/foreground/boost/cpus"
    echo "/dev/cpuset/top-app/cpus"
    echo "/dev/stune/top-app/schedtune.prefer_idle"
    echo "/dev/stune/top-app/schedtune.boost"
    echo "/sys/class/kgsl/kgsl-3d0/devfreq/governor"
    echo "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
    echo "/sys/class/kgsl/kgsl-3d0/min_pwrlevel"
    echo "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
    echo "/sys/class/kgsl/kgsl-3d0/default_pwrlevel"
    echo "/sys/class/kgsl/kgsl-3d0/bus_split"
    echo "/sys/class/kgsl/kgsl-3d0/force_clk_on"
    for p in /sys/class/devfreq/*cpu-llcc-ddr-bw /sys/class/devfreq/*cpu-cpu-llcc-bw; do
        [[ -d "$p" ]] || continue
        echo "$p/min_freq"
        echo "$p/max_freq"
    done
    for p in /sys/devices/platform/soc/*.ufshc /sys/devices/platform/*.ufshc; do
        [[ -d "$p" ]] || continue
        echo "$p/clkscale_enable"
        echo "$p/clkgate_enable"
        echo "$p/hibern8_on_idle_enable"
    done
    for p in /sys/class/devfreq/*.ufshc; do
        [[ -d "$p" ]] || continue
        echo "$p/min_freq"
    done
}

# $1 = node, $2 = prop name; props are cleared by a reboot, so the snapshot
# always belongs to the current boot.
stock_store() {
    local i=0 path
    for path in $(stock_paths); do
        if [[ -e "$path" ]]; then
            setprop "vtools.stock.$i" "$(cat "$path" 2> /dev/null)"
        else
            setprop "vtools.stock.$i" ""
        fi
        i=$((i + 1))
    done
    setprop vtools.stock.ready 1
}

snapshot_boot_stock() {
    [[ "$(getprop vtools.stock.ready)" = "1" ]] && return 0
    stock_store
}

restore_boot_stock() {
    [[ "$(getprop vtools.stock.ready)" = "1" ]] || return 0
    local i=0 path val
    for path in $(stock_paths); do
        val="$(getprop "vtools.stock.$i")"
        if [[ -n "$val" ]] && [[ -e "$path" ]]; then
            chmod 0664 "$path" 2> /dev/null
            echo "$val" > "$path" 2> /dev/null
        fi
        i=$((i + 1))
    done
}

# --- CPU / GPU reset --------------------------------------------------------

core_online=(1 1 1 1 1 1 1 1)
set_core_online() {
  for index in 0 1 2 3 4 5 6 7; do
    local node="/sys/devices/system/cpu/cpu$index/online"
    [[ -e "$node" ]] || continue
    core_online[$index]=`cat $node`
    write_node 1 "$node"
  done
}

reset_basic_governor() {
  set_core_online

  local gov0 gov6 gpu_gov avail g
  gov0="$(pick_cpu_governor 0)"
  gov6="$(pick_cpu_governor 6)"
  [[ -n "$gov0" ]] && set_value "$gov0" /sys/devices/system/cpu/cpufreq/policy0/scaling_governor
  [[ -n "$gov6" ]] && set_value "$gov6" /sys/devices/system/cpu/cpufreq/policy6/scaling_governor

  avail="$(cat "$gpu_dir/devfreq/available_governors" 2>/dev/null)"
  gpu_gov=""
  for g in msm-adreno-tz msm-adreno-tz-v2 simple_ondemand; do
    if [[ -z "$avail" || " $avail " == *" $g "* ]]; then
      gpu_gov="$g"
      break
    fi
  done
  [[ -n "$gpu_gov" ]] && set_value "$gpu_gov" "$gpu_dir/devfreq/governor"

  [[ -n "$gpu_min_freq" ]] && write_node "$gpu_min_freq" "$gpu_dir/devfreq/min_freq"
  if [[ -n "$num_pwrlevels" ]]; then
    write_node "$gpu_min_pl" "$gpu_dir/min_pwrlevel"
    write_node "$gpu_max_pl" "$gpu_dir/max_pwrlevel"
  fi
}

# --- DDR / LLCC bandwidth (devfreq, discovered by glob) ---------------------

devfreq_performance() {
  bw_max_always
}

devfreq_restore() {
  bw_min
}

bw_min() {
  local path
  local min
  for path in /sys/class/devfreq/*cpu-llcc-ddr-bw /sys/class/devfreq/*cpu-cpu-llcc-bw; do
    [[ -f "$path/available_frequencies" ]] || continue
    min="$(min_of "$(cat $path/available_frequencies)")"
    [[ -n "$min" ]] && set_value "$min" "$path/min_freq"
  done
}

bw_max_always() {
  local path
  local max
  for path in /sys/class/devfreq/*cpu-llcc-ddr-bw /sys/class/devfreq/*cpu-cpu-llcc-bw; do
    [[ -f "$path/available_frequencies" ]] || continue
    max="$(max_of "$(cat $path/available_frequencies)")"
    [[ -n "$max" ]] || continue
    set_value "$max" "$path/min_freq"
    set_value "$max" "$path/max_freq"
  done
}

# --- CPU frequency / boost --------------------------------------------------

set_input_boost_freq() {
  local c0 c1 ms
  c0="$(snap_cpu_freq 0 "$1")"
  c1="$(snap_cpu_freq 6 "$2")"
  ms="$3"
  write_node "0:$c0 1:$c0 2:$c0 3:$c0 4:$c0 5:$c0 6:$c1 7:$c1" /sys/module/cpu_boost/parameters/input_boost_freq
  write_node "$ms" /sys/module/cpu_boost/parameters/input_boost_ms
  if [[ "$ms" -gt 0 ]]; then
    write_node 1 /sys/module/cpu_boost/parameters/sched_boost_on_input
  else
    write_node 0 /sys/module/cpu_boost/parameters/sched_boost_on_input
  fi
}

set_cpu_freq() {
  write_node "0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295" /sys/module/msm_performance/parameters/cpu_max_freq
  write_node "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0" /sys/module/msm_performance/parameters/cpu_min_freq

  set_value "$(snap_cpu_freq 0 $1)" /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq
  set_value "$(snap_cpu_freq 0 $2)" /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
  set_value "$(snap_cpu_freq 6 $3)" /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq
  set_value "$(snap_cpu_freq 6 $4)" /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq
}

ufshc_perf() {
  local dir devfreq avail
  for dir in /sys/devices/platform/soc/*.ufshc /sys/devices/platform/*.ufshc; do
    [[ -d "$dir" ]] || continue
    if [[ "$1" == "on" ]]; then
      write_node 0 "$dir/clkscale_enable"
      write_node 0 "$dir/clkgate_enable"
      write_node 0 "$dir/hibern8_on_idle_enable"
    else
      write_node 1 "$dir/clkscale_enable"
      write_node 1 "$dir/clkgate_enable"
      write_node 1 "$dir/hibern8_on_idle_enable"
    fi
  done
  for devfreq in /sys/class/devfreq/*.ufshc; do
    [[ -d "$devfreq" ]] || continue
    avail="$(cat "$devfreq/available_frequencies" 2>/dev/null)"
    if [[ "$1" == "on" ]]; then
      set_value "$(max_of "$avail")" "$devfreq/min_freq"
    else
      set_value "$(min_of "$avail")" "$devfreq/min_freq"
    fi
  done
}

# --- scheduler / cpuset -----------------------------------------------------

sched_config() {
  set_value "$1" /proc/sys/kernel/sched_downmigrate
  set_value "$2" /proc/sys/kernel/sched_upmigrate
}

sched_limit() {
  set_value "$1" /sys/devices/system/cpu/cpufreq/policy0/schedutil/down_rate_limit_us
  set_value "$2" /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us
  set_value "$3" /sys/devices/system/cpu/cpufreq/policy6/schedutil/down_rate_limit_us
  set_value "$4" /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us
}

cpu6_core_ctl() {
  local dir=/sys/devices/system/cpu/cpu6/core_ctl
  [[ -d "$dir" ]] || return 0
  if [[ "$1" == "on" ]]; then
    write_node 10 "$dir/offline_delay_ms"
    write_node "1 1" "$dir/not_preferred"
    write_node 1 "$dir/enable"
    write_node 2 "$dir/max_cpus"
    write_node 0 "$dir/min_cpus"
    write_node 2 "$dir/task_thres"
    write_node 30 "$dir/busy_down_thres"
    write_node 50 "$dir/busy_up_thres"
  else
    write_node 0 "$dir/enable"
  fi
}

cpu0_core_ctl() {
  local dir=/sys/devices/system/cpu/cpu0/core_ctl
  [[ -d "$dir" ]] || return 0
  if [[ "$1" == "on" ]]; then
    write_node 50 "$dir/offline_delay_ms"
    write_node "0 1 1 1 1 1" "$dir/not_preferred"
    write_node 1 "$dir/enable"
    write_node 6 "$dir/max_cpus"
    write_node 1 "$dir/min_cpus"
    write_node 5 "$dir/busy_down_thres"
    write_node 15 "$dir/busy_up_thres"
  else
    write_node 0 "$dir/enable"
  fi
}

ctl_on() {
  local dir=/sys/devices/system/cpu/$1/core_ctl
  [[ -d "$dir" ]] || return 0
  write_node 1 "$dir/enable"
  if [[ "$2" != "" ]]; then
    write_node "$2" "$dir/min_cpus"
  else
    write_node 0 "$dir/min_cpus"
  fi
}

ctl_off() {
  local dir=/sys/devices/system/cpu/$1/core_ctl
  [[ -d "$dir" ]] || return 0
  write_node 0 "$dir/enable"
}

set_hispeed_freq() {
  set_value "$(snap_cpu_freq 0 $1)" /sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq
  set_value "$(snap_cpu_freq 6 $2)" /sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_freq
}

sched_boost() {
  set_value "$1" /proc/sys/kernel/sched_boost
}

stune_top_app() {
  [[ -d /dev/stune/top-app ]] || return 0
  write_node "$1" /dev/stune/top-app/schedtune.prefer_idle
  write_node "$2" /dev/stune/top-app/schedtune.boost
}

cpuset() {
  [[ -d /dev/cpuset ]] || return 0
  write_node "$1" /dev/cpuset/background/cpus
  write_node "$2" /dev/cpuset/system-background/cpus
  write_node "$3" /dev/cpuset/foreground/cpus
  write_node "$4" /dev/cpuset/top-app/cpus
}

# GPU MinPowerLevel To Up
gpu_pl_up() {
  [[ -d "$gpu_dir" ]] || return 0
  local offset="$1"
  if [[ "$offset" != "" ]] && [[ ! "$offset" -gt "$gpu_min_pl" ]]; then
    write_node `expr $gpu_min_pl - $offset` "$gpu_dir/min_pwrlevel"
  elif [[ "$offset" -gt "$gpu_min_pl" ]]; then
    write_node 0 "$gpu_dir/min_pwrlevel"
  else
    write_node "$gpu_min_pl" "$gpu_dir/min_pwrlevel"
  fi
}
