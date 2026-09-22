#!/bin/sh

# Surya (POCO X3 NFC) has exactly two CPU clusters: policy0 = cpu0-5, policy6 = cpu6-7.
# Only nodes that exist on the stock kernel are written: adrenoboost, sched_bore/sched_burst and
# sched_util_clamp are absent and are therefore not touched, and thermal is left to the ROM.
# The Scene scheduling scripts apply this same tuning through kernel_tuning() in
# assets/powercfg/sm6150/powercfg-utils.sh; keep both in sync when changing values.
setProfile() {
    # CPU
    echo powersave > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor
    echo powersave > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor

    # GPU
    # Cap the GPU at the lowest power level of the runtime table instead of a hardcoded index.
    num_pwrlevels=`cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null`
    min_pwrlevel=`expr ${num_pwrlevels:-7} - 1`
    echo powersave > /sys/class/kgsl/kgsl-3d0/devfreq/governor
    echo $min_pwrlevel > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
    echo $min_pwrlevel > /sys/class/kgsl/kgsl-3d0/min_pwrlevel
    echo 1 > /sys/class/kgsl/kgsl-3d0/throttling

    # VM
    echo 100 > /proc/sys/vm/swappiness
    echo 10 > /proc/sys/vm/dirty_ratio
}

{ setProfile; echo "[$(date '+%Y-%m-%d %H:%M:%S')] Powersave mode applied"; } 2>&1 | tee -a /sdcard/RvKernel-Manager/kernel-profile/kernel-profile.log
