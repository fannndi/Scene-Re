#!/system/bin/sh

# Kernel capability probe for the Kernel Features dialog and the Diagnostics
# bundle. Prints one `key=value` line per probe and never writes to the kernel.
# Every value is either "1"/"0" (available or not) or a short informational
# string (lists are truncated).

kv() { echo "$1=$2"; }
val() { [ -e "$1" ] && cat "$1" 2> /dev/null | head -n 1 | tr -d '\n\r'; }
has() { if [ -e "$1" ]; then echo 1; else echo 0; fi; }
one() { [ -f "$1" ] && cat "$1" 2> /dev/null | tr ' ' '\n' | grep -v '^$' | head -n "${2:-8}" | tr '\n' ' ' | sed 's/ $//'; }
count_glob() { local n=0 f; for f in $1; do [ -e "$f" ] && n=$(( n + 1 )); done; echo "$n"; }

# system
kv system.platform "$(getprop ro.board.platform)"
kv system.device "$(getprop ro.product.device)"
kv system.android "$(getprop ro.build.version.release)"
kv system.kernel "$(uname -r)"
kv system.cores "$(nproc 2> /dev/null)"

# CPU / scheduler
kv cpu.policy0.governor "$(val /sys/devices/system/cpu/cpufreq/policy0/scaling_governor)"
kv cpu.policy0.governors "$(one /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 12)"
kv cpu.policy0.freqs "$(one /sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies 14)"
kv cpu.policy6.governor "$(val /sys/devices/system/cpu/cpufreq/policy6/scaling_governor)"
kv cpu.policy6.governors "$(one /sys/devices/system/cpu/cpufreq/policy6/scaling_available_governors 12)"
kv cpu.policy6.freqs "$(one /sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies 14)"
kv cpu.core_ctl "$(count_glob '/sys/devices/system/cpu/cpu*/core_ctl')"
kv cpu.cpu_boost "$(has /sys/module/cpu_boost/parameters/input_boost_freq)"
kv cpu.msm_performance "$(has /sys/module/msm_performance/parameters/cpu_max_freq)"
kv cpu.sched_boost "$(has /proc/sys/kernel/sched_boost)"
kv cpu.sched_upmigrate "$(val /proc/sys/kernel/sched_upmigrate)"
kv cpu.sched_downmigrate "$(val /proc/sys/kernel/sched_downmigrate)"
kv cpu.sched_group_upmigrate "$(val /proc/sys/kernel/sched_group_upmigrate)"
kv cpu.sched_group_downmigrate "$(val /proc/sys/kernel/sched_group_downmigrate)"
kv cpu.sched_walt_rotate "$(has /proc/sys/kernel/sched_walt_rotate_big_tasks)"
kv cpu.sched_load_boost "$(has /sys/devices/system/cpu/cpu6/sched_load_boost)"
kv cpu.sched_features "$(has /sys/kernel/debug/sched_features)"
kv cpu.sched_lib "$(has /proc/sys/kernel/sched_lib_name)"
kv cpu.stune "$(has /dev/stune/top-app/schedtune.boost)"
kv cpu.cpuset "$(has /dev/cpuset/top-app/cpus)"
kv cpu.walt "$(has /sys/devices/system/cpu/cpufreq/policy0/walt)"
kv cpu.schedhorizon "$(has /sys/devices/system/cpu/cpufreq/policy0/schedhorizon)"
kv cpu.schedutil "$(has /sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq)"

# GPU (kgsl)
kgsl=/sys/class/kgsl/kgsl-3d0
kv gpu.kgsl "$(has $kgsl)"
kv gpu.governor "$(val $kgsl/devfreq/governor)"
kv gpu.governors "$(one $kgsl/devfreq/available_governors 8)"
kv gpu.freqs "$(one $kgsl/devfreq/available_frequencies 10)"
kv gpu.pwrlevels "$(val $kgsl/num_pwrlevels)"
kv gpu.min_pwrlevel "$(val $kgsl/min_pwrlevel)"
kv gpu.max_pwrlevel "$(val $kgsl/max_pwrlevel)"
kv gpu.default_pwrlevel "$(val $kgsl/default_pwrlevel)"
kv gpu.adrenoboost "$(has $kgsl/adrenoboost)"
kv gpu.bus_split "$(has $kgsl/bus_split)"
kv gpu.force_clk_on "$(has $kgsl/force_clk_on)"

# Bus / DRAM bandwidth
kv bus.ddr_bw "$(count_glob '/sys/class/devfreq/*cpu-llcc-ddr-bw')"
kv bus.llcc_bw "$(count_glob '/sys/class/devfreq/*cpu-cpu-llcc-bw')"
kv bus.lat "$(count_glob '/sys/class/devfreq/*cpu*-lat')"
kv bus.latfloor "$(count_glob '/sys/class/devfreq/*latfloor*')"
kv bus.gpubw "$(count_glob '/sys/class/devfreq/*gpubw*')"
kv bus.dcvs "$(count_glob '/sys/devices/system/cpu/bus_dcvs/*')"

# I/O / storage
dev=""
for d in /sys/block/sd* /sys/block/mmcblk*; do
  [ -d "$d/queue" ] && { dev="$d"; break; }
done
kv io.device "$(basename "$dev" 2> /dev/null)"
kv io.scheduler "$(val "$dev/queue/scheduler")"
kv io.read_ahead "$(val "$dev/queue/read_ahead_kb")"
kv io.nr_requests "$(val "$dev/queue/nr_requests")"
kv io.iostats "$(has "$dev/queue/iostats")"
kv io.add_random "$(has "$dev/queue/add_random")"

# UFS
ufs=""
for d in /sys/devices/platform/soc/*.ufshc /sys/devices/platform/*.ufshc; do
  [ -d "$d" ] && { ufs="$d"; break; }
done
kv ufs.device "$(basename "$ufs" 2> /dev/null)"
kv ufs.clkscale "$(has "$ufs/clkscale_enable")"
kv ufs.devfreq "$(count_glob '/sys/class/devfreq/*.ufshc')"

# Thermal
kv thermal.zones "$(count_glob '/sys/class/thermal/thermal_zone*')"
kv thermal.cooling "$(count_glob '/sys/class/thermal/cooling_device*')"
kv thermal.battery_temp "$(val /sys/class/power_supply/battery/temp)"
kv thermal.msg "$(has /sys/class/thermal/thermal_message/board_sensor_temp)"
kv thermal.msg_sconfig "$(val /sys/class/thermal/thermal_message/sconfig)"

# Battery / charging
bypass=""
for p in \
  /sys/class/power_supply/battery/input_suspend \
  /sys/class/qcom-battery/input_suspend \
  /sys/class/power_supply/battery/battery_charging_enabled \
  /sys/class/power_supply/battery/charging_enabled \
  /sys/class/power_supply/battery/charge_disable \
  /sys/class/power_supply/qpnp_adaptive_charge/blocking \
  /sys/class/qcom-battery/restricted_charging \
  /sys/class/power_supply/mca_charge_interface/input_suspend \
  /sys/class/power_supply/battery/constant_charge_current_max; do
  [ -e "$p" ] && { bypass="$p"; break; }
done
kv battery.bypass "$bypass"
kv battery.cycles "$(val /sys/class/power_supply/battery/cycle_count)"
kv battery.full "$(val /sys/class/power_supply/battery/charge_full)"
kv battery.design "$(val /sys/class/power_supply/battery/charge_full_design)"
kv battery.health "$(val /sys/class/power_supply/battery/health)"
kv battery.temp "$(val /sys/class/power_supply/battery/temp)"
kv battery.current_max "$(val /sys/class/power_supply/battery/constant_charge_current_max)"
kv battery.input_limit "$(val /sys/class/power_supply/battery/input_current_limit)"

# Xiaomi extras
kv xiaomi.migt "$(has /sys/module/migt/parameters/glk_maxfreq)"
kv xiaomi.perfmgr "$(has /sys/module/perfmgr/parameters/perfmgr_enable)"
kv xiaomi.game_service "$(pm list packages com.xiaomi.gamecenter.sdk.service 2> /dev/null | head -n 1 | cut -d: -f2)"

# Memory / kernel modules
kv mem.lmk "$(has /sys/module/lowmemorykiller/parameters/enable_adaptive_lmk)"
kv mem.zram "$(count_glob '/sys/block/zram*')"
kv mem.workqueue "$(has /sys/module/workqueue/parameters/power_efficient)"
kv mem.battery_saver "$(has /sys/module/battery_saver/parameters/enabled)"

exit 0
