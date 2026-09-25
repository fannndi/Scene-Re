#!/system/bin/sh

# Base (init) tuning for the POCO X3 NFC (surya) - Snapdragon 732G (SM7150-AC).
# Sourced by active.sh / conservative.sh after powercfg-utils.sh, so the
# guarded helpers (write_node / set_value / snap_cpu_freq) are available.
# Every write checks the node first, so kernel variants that do not expose a
# tunable are skipped instead of failing.

if ! command -v write_node > /dev/null 2>&1; then
  write_node() {
    [[ -e "$2" ]] || return 0
    chmod 0664 "$2" 2> /dev/null
    echo "$1" > "$2" 2> /dev/null
  }
  set_value() {
    [[ -f "$2" ]] || return 0
    [[ "$(cat "$2" 2> /dev/null)" = "$1" ]] && return 0
    chmod 0664 "$2" 2> /dev/null
    echo "$1" > "$2" 2> /dev/null
  }
  snap_cpu_freq() { echo "$2"; }
fi

# Capture the boot-stock state first: mode "off" restores exactly this, and
# the snapshot must happen before any Scene tuning. The props are cleared by a
# reboot, which re-triggers the snapshot on the next boot.
if command -v snapshot_boot_stock > /dev/null 2>&1; then
  snapshot_boot_stock
fi

# CPU hotplug / core control
for index in 0 1 2 3 4 5 6 7; do
  write_node 1 "/sys/devices/system/cpu/cpu$index/online"
done

write_node 6 /sys/devices/system/cpu/cpu0/core_ctl/min_cpus
write_node 0 /sys/devices/system/cpu/cpu0/core_ctl/enable

# Core control parameters on gold
write_node "1 1" /sys/devices/system/cpu/cpu6/core_ctl/not_preferred
write_node 0 /sys/devices/system/cpu/cpu6/core_ctl/min_cpus
write_node 85 /sys/devices/system/cpu/cpu6/core_ctl/busy_up_thres
write_node 65 /sys/devices/system/cpu/cpu6/core_ctl/busy_down_thres
write_node 20 /sys/devices/system/cpu/cpu6/core_ctl/offline_delay_ms
write_node 1 /sys/devices/system/cpu/cpu6/core_ctl/enable

# Scheduler (percentages; the kernel validates 1..100 and up > down)
set_value 65 /proc/sys/kernel/sched_downmigrate
set_value 71 /proc/sys/kernel/sched_upmigrate
set_value 85 /proc/sys/kernel/sched_group_downmigrate
set_value 100 /proc/sys/kernel/sched_group_upmigrate
set_value 1 /proc/sys/kernel/sched_walt_rotate_big_tasks

# sched_load_boost as -6 is equivalent to target load 85 (per-cpu tunable)
set_value -6 /sys/devices/system/cpu/cpu6/sched_load_boost
set_value -6 /sys/devices/system/cpu/cpu7/sched_load_boost
set_value 85 /sys/devices/system/cpu/cpu6/cpufreq/schedutil/hispeed_load

# Input boost (frequencies snapped to the kernel OPP table)
boost_silver="$(snap_cpu_freq 0 1708800)"
boost_gold="$(snap_cpu_freq 6 2304000)"
write_node "0:$(snap_cpu_freq 0 1324800)" /sys/module/cpu_boost/parameters/input_boost_freq
write_node 40 /sys/module/cpu_boost/parameters/input_boost_ms
write_node "0:$boost_silver 1:$boost_silver 2:$boost_silver 3:$boost_silver 4:$boost_silver 5:$boost_silver 6:$boost_gold 7:0" /sys/module/cpu_boost/parameters/powerkey_input_boost_freq
write_node 400 /sys/module/cpu_boost/parameters/powerkey_input_boost_ms
write_node Y /sys/module/cpu_boost/parameters/sched_boost_on_powerkey_input

write_node 0 /sys/module/lpm_levels/parameters/sleep_disabled

# VM
set_value 5 /proc/sys/vm/dirty_background_ratio
set_value 50 /proc/sys/vm/overcommit_ratio
set_value 100 /proc/sys/vm/swap_ratio
set_value 100 /proc/sys/vm/vfs_cache_pressure
set_value 10 /proc/sys/vm/dirty_ratio
set_value 3 /proc/sys/vm/page-cluster
set_value 1000 /proc/sys/vm/dirty_expire_centisecs
set_value 2000 /proc/sys/vm/dirty_writeback_centisecs

# Block read-ahead (the storage device is discovered, not hardcoded)
for node in /sys/block/sd*/queue/read_ahead_kb; do
  set_value 256 "$node"
done

set_value 0 /sys/module/lowmemorykiller/parameters/enable_adaptive_lmk

# cpuset
write_node 0-1 /dev/cpuset/background/cpus
write_node 0-4 /dev/cpuset/system-background/cpus
write_node 6-7 /dev/cpuset/foreground/boost/cpus
write_node 0-7 /dev/cpuset/foreground/cpus
write_node 0-7 /dev/cpuset/top-app/cpus

set_value 10000000 /proc/sys/kernel/sched_latency_ns
set_value 2000000 /proc/sys/kernel/sched_min_granularity_ns
