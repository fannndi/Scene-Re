package com.omarea.core.mode

import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellLog

/**
 * Applies Scene CPU/GPU/thermal-scheduler mode profiles natively from Kotlin,
 * mirroring assets/powercfg/sm6150/{powercfg-base,utils,active,conservative}.sh
 * so the app no longer depends on those shell files at runtime.
 *
 * Not replicated: the per-app `adjustment_by_top_app` table and the external
 * `scene-scheduler` binary (those stay available through the script path).
 */
object NativeModeApplier {

    private const val GPU = "/sys/class/kgsl/kgsl-3d0"

    @Volatile
    var supported: Boolean? = null
        private set

    fun isSupported(): Boolean {
        val cached = supported
        if (cached != null) return cached
        val ok = KeepShellPublic.doCmdSync(
            "[ -d /sys/devices/system/cpu/cpufreq/policy0 ] && " +
                    "[ -d /sys/devices/system/cpu/cpufreq/policy6 ] && " +
                    "[ -d /sys/module/msm_performance/parameters ] && echo yes"
        ).contains("yes")
        supported = ok
        return ok
    }

    /** Lowest (min) GPU power level index = num_pwrlevels - 1, read live. */
    private fun gpuMinPl(): Int =
        KeepShellPublic.doCmdSync("cat $GPU/num_pwrlevels 2>/dev/null")
            .trim().toIntOrNull()?.minus(1) ?: 6

    private fun applyLines(lines: List<String>) {
        if (lines.isEmpty()) return
        // Execute as one shell block; guarded writes (2>/dev/null) keep it resilient.
        KeepShellPublic.doCmdSync(lines.joinToString("\n"))
    }

    private fun write(path: String, value: String): String =
        "set_value $value $path"

    /** Mirrors powercfg-base.sh + reset_basic_governor(). */
    fun init() {
        applyLines(
            listOf(
                // base: core control
                "echo 0 > /sys/devices/system/cpu/cpu0/core_ctl/enable",
                "echo 1 1 > /sys/devices/system/cpu/cpu6/core_ctl/not_preferred",
                "echo 0 > /sys/devices/system/cpu/cpu6/core_ctl/min_cpus",
                "echo 85 > /sys/devices/system/cpu/cpu6/core_ctl/busy_up_thres",
                "echo 65 > /sys/devices/system/cpu/cpu6/core_ctl/busy_down_thres",
                "echo 20 > /sys/devices/system/cpu/cpu6/core_ctl/offline_delay_ms",
                "echo 1 > /sys/devices/system/cpu/cpu6/core_ctl/enable",
                // base: b.L scheduler
                "echo 65 > /proc/sys/kernel/sched_downmigrate",
                "echo 71 > /proc/sys/kernel/sched_upmigrate",
                "echo 85 > /proc/sys/kernel/sched_group_downmigrate",
                "echo 100 > /proc/sys/kernel/sched_group_upmigrate",
                "echo 1 > /proc/sys/kernel/sched_walt_rotate_big_tasks",
                "echo -6 > /sys/devices/system/cpu/cpu6/sched_load_boost",
                "echo -6 > /sys/devices/system/cpu/cpu7/sched_load_boost",
                "echo 85 > /sys/devices/system/cpu/cpu6/cpufreq/schedutil/hispeed_load",
                // base: boost defaults
                "echo \"0:1324800\" > /sys/module/cpu_boost/parameters/input_boost_freq",
                "echo 40 > /sys/module/cpu_boost/parameters/input_boost_ms",
                "echo \"0:1708800 1:1708800 2:1708800 3:1708800 4:1708800 5:1708800 6:2208000 7:0\" > /sys/module/cpu_boost/parameters/powerkey_input_boost_freq",
                "echo 400 > /sys/module/cpu_boost/parameters/powerkey_input_boost_ms",
                // base: cores online + lpm
                "echo 0 > /sys/module/lpm_levels/parameters/sleep_disabled",
                *(0..7).map { "echo 1 > /sys/devices/system/cpu/cpu$it/online" }.toTypedArray(),
                // base: vm
                "echo 5 > /proc/sys/vm/dirty_background_ratio",
                "echo 30 > /proc/sys/vm/overcommit_ratio",
                "echo 100 > /proc/sys/vm/swap_ratio",
                "echo 100 > /proc/sys/vm/vfs_cache_pressure",
                "echo 25 > /proc/sys/vm/dirty_ratio",
                "echo 3 > /proc/sys/vm/page-cluster",
                "echo 4000 > /proc/sys/vm/dirty_expire_centisecs",
                "echo 6000 > /proc/sys/vm/dirty_writeback_centisecs",
                "echo 256 > /sys/block/sda/queue/read_ahead_kb",
                // base: cpuset defaults
                "echo 0-1 > /dev/cpuset/background/cpus",
                "echo 0-4 > /dev/cpuset/system-background/cpus",
                "echo 6-7 > /dev/cpuset/foreground/boost/cpus",
                "echo 0-7 > /dev/cpuset/foreground/cpus",
                "echo 0-7 > /dev/cpuset/top-app/cpus",
                "echo 10000000 > /proc/sys/kernel/sched_latency_ns",
                "echo 2000000 > /proc/sys/kernel/sched_min_granularity_ns",
                "echo 1 > /proc/sys/kernel/sched_prefer_sync_wakee_to_waker",
                // reset_basic_governor
                "echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
                "echo schedutil > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor",
                "echo msm-adreno-tz > $GPU/devfreq/governor",
                "cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies | awk '{print \$1}' > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
                "echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
            )
        )
    }

    private fun inputBoost(c0: Int, c1: Int, ms: Int): List<String> {
        val cores = (0..7).joinToString(" ") { i -> "$i:${if (i < 6) c0 else c1}" }
        return listOf(
            "echo \"$cores\" > /sys/module/cpu_boost/parameters/input_boost_freq",
            "echo $ms > /sys/module/cpu_boost/parameters/input_boost_ms",
            "echo ${if (ms > 0) 1 else 0} > /sys/module/cpu_boost/parameters/sched_boost_on_input"
        )
    }

    private fun cpuFreq(min0: Int, max0: Int, min6: Int, max6: Int): List<String> =
        listOf(
            "echo \"0:4294967295 1:4294967295 2:4294967295 3:4294967295 4:4294967295 5:4294967295 6:4294967295 7:4294967295\" > /sys/module/msm_performance/parameters/cpu_max_freq",
            "echo \"0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0\" > /sys/module/msm_performance/parameters/cpu_min_freq",
            write("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "$min0"),
            write("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", "$max0"),
            write("/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "$min0"),
            write("/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq", "$min6"),
            write("/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq", "$min6"),
            write("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", "$max6")
        )

    private fun schedConfig(down: Int, up: Int, gDown: Int, gUp: Int): List<String> = listOf(
        "echo $down > /proc/sys/kernel/sched_downmigrate",
        "echo $up > /proc/sys/kernel/sched_upmigrate",
        "echo $gDown > /proc/sys/kernel/sched_group_downmigrate",
        "echo $gUp > /proc/sys/kernel/sched_group_upmigrate"
    )

    private fun schedLimit(d0: Int, u0: Int, d6: Int, u6: Int): List<String> = listOf(
        "echo $d0 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/down_rate_limit_us",
        "echo $u0 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us",
        "echo $d6 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/down_rate_limit_us",
        "echo $u6 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/up_rate_limit_us"
    )

    private fun schedBoost(topApp: Int, boost: Int): List<String> = listOf(
        "echo $topApp > /proc/sys/kernel/sched_boost_top_app",
        "echo $boost > /proc/sys/kernel/sched_boost"
    )

    private fun stuneTopApp(preferIdle: Int, boost: Int): List<String> = listOf(
        "echo $preferIdle > /dev/stune/top-app/schedtune.prefer_idle",
        "echo $boost > /dev/stune/top-app/schedtune.boost"
    )

    private fun cpuset(bg: String, sysBg: String, fg: String, topApp: String): List<String> = listOf(
        "echo $bg > /dev/cpuset/background/cpus",
        "echo $sysBg > /dev/cpuset/system-background/cpus",
        "echo $fg > /dev/cpuset/foreground/cpus",
        "echo $topApp > /dev/cpuset/top-app/cpus"
    )

    private fun hispeed(p0: Int, p6: Int): List<String> = listOf(
        "echo $p0 > /sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq",
        "echo $p6 > /sys/devices/system/cpu/cpufreq/policy6/schedutil/hispeed_freq"
    )

    private fun coreCtl0(state: String): List<String> =
        if (state == "on") listOf(
            "echo 50 > /sys/devices/system/cpu/cpu0/core_ctl/offline_delay_ms",
            "echo 0 1 1 1 1 1 > /sys/devices/system/cpu/cpu0/core_ctl/not_preferred",
            "echo 1 > /sys/devices/system/cpu/cpu0/core_ctl/enable",
            "echo 6 > /sys/devices/system/cpu/cpu0/core_ctl/max_cpus",
            "echo 1 > /sys/devices/system/cpu/cpu0/core_ctl/min_cpus",
            "echo 5 > /sys/devices/system/cpu/cpu0/core_ctl/busy_down_thres",
            "echo 15 > /sys/devices/system/cpu/cpu0/core_ctl/busy_up_thres"
        ) else listOf("echo 0 > /sys/devices/system/cpu/cpu0/core_ctl/enable")

    private fun coreCtl6(state: String): List<String> =
        if (state == "on") listOf(
            "echo 10 > /sys/devices/system/cpu/cpu6/core_ctl/offline_delay_ms",
            "echo 1 1 > /sys/devices/system/cpu/cpu6/core_ctl/not_preferred",
            "echo 1 > /sys/devices/system/cpu/cpu6/core_ctl/enable",
            "echo 2 > /sys/devices/system/cpu/cpu6/core_ctl/max_cpus",
            "echo 0 > /sys/devices/system/cpu/cpu6/core_ctl/min_cpus",
            "echo 2 > /sys/devices/system/cpu/cpu6/core_ctl/task_thres",
            "echo 30 > /sys/devices/system/cpu/cpu6/core_ctl/busy_down_thres",
            "echo 50 > /sys/devices/system/cpu/cpu6/core_ctl/busy_up_thres"
        ) else listOf("echo 0 > /sys/devices/system/cpu/cpu6/core_ctl/enable")

    private fun gpuPlUp(offset: Int, gpuMinPl: Int): String =
        when {
            offset <= gpuMinPl -> "echo ${gpuMinPl - offset} > $GPU/min_pwrlevel"
            else -> "echo $gpuMinPl > $GPU/min_pwrlevel"
        }

    private fun gpuPlDown(offset: Int, gpuMinPl: Int): String =
        if (offset <= gpuMinPl) "echo $offset > $GPU/max_pwrlevel"
        else "echo $gpuMinPl > $GPU/max_pwrlevel"

    private fun devfreqBw(max: Boolean): List<String> {
        val paths = listOf(
            "/sys/class/devfreq/soc:qcom,cpu-llcc-ddr-bw",
            "/sys/class/devfreq/soc:qcom,cpu-cpu-llcc-bw"
        )
        return paths.flatMap { p ->
            if (max) listOf(
                "cat $p/available_frequencies | awk '{print \$NF}' > $p/min_freq",
                "cat $p/available_frequencies | awk '{print \$NF}' > $p/max_freq",
                "cat $p/available_frequencies | awk '{print \$NF}' > $p/min_freq"
            ) else listOf(
                "cat $p/available_frequencies | awk '{print \$1}' > $p/min_freq"
            )
        }
    }

    private fun ufshc(on: Boolean): List<String> = if (on) listOf(
        "echo 0 > /sys/devices/platform/soc/1d84000.ufshc/clkscale_enable",
        "echo 0 > /sys/devices/platform/soc/1d84000.ufshc/clkgate_enable",
        "echo 0 > /sys/devices/platform/soc/1d84000.ufshc/hibern8_on_idle_enable",
        "echo 300000000 > /sys/class/devfreq/1d84000.ufshc/min_freq"
    ) else listOf(
        "echo 1 > /sys/devices/platform/soc/1d84000.ufshc/clkscale_enable",
        "echo 1 > /sys/devices/platform/soc/1d84000.ufshc/clkgate_enable",
        "echo 1 > /sys/devices/platform/soc/1d84000.ufshc/hibern8_on_idle_enable",
        "echo 37500000 > /sys/class/devfreq/1d84000.ufshc/min_freq"
    )

    /** Applies one of ModeSwitcher.POWERSAVE / BALANCE / PERFORMANCE / FAST / PEDESTAL. */
    fun apply(mode: String) {
        if (!isSupported()) {
            ShellLog.log("NativeModeApplier.apply($mode)", "unsupported device", error = true)
            return
        }

        val lines = ArrayList<String>()
        val gpuMinPl = gpuMinPl()

        when (mode) {
            "powersave" -> {
                lines += cpuFreq(5000, 1612800, 5000, 1555200)
                lines += inputBoost(0, 0, 0)
                lines += hispeed(1248000, 806400)
                lines += schedBoost(0, 0) + stuneTopApp(0, 0)
                lines += coreCtl0("off") + coreCtl6("on")
                lines += schedConfig(75, 92, 380, 500)
                lines += schedLimit(0, 0, 500, 1000)
                lines += cpuset("0-1", "0-3", "0-3", "0-7")
                lines += ufshc(false)
                lines += gpuPlDown(4, gpuMinPl)
            }
            "balance" -> {
                lines += cpuFreq(5000, 1708800, 5000, 1843200)
                lines += inputBoost(0, 0, 0)
                lines += hispeed(1248000, 1209600)
                lines += schedBoost(1, 0) + stuneTopApp(0, 0)
                lines += coreCtl0("off") + coreCtl6("off")
                lines += schedConfig(68, 82, 300, 400)
                lines += schedLimit(0, 0, 0, 0)
                lines += cpuset("0-1", "0-3", "0-5", "0-7")
                lines += ufshc(false)
            }
            "performance" -> {
                lines += cpuFreq(300000, 2500000, 300000, 2304000)
                lines += inputBoost(1804800, 1939200, 120)
                lines += gpuPlUp(1, gpuMinPl)
                lines += schedBoost(1, 0) + stuneTopApp(0, 0)
                lines += coreCtl0("off") + coreCtl6("off")
                lines += schedConfig(60, 78, 300, 400)
                lines += schedLimit(2000, 1000, 0, 0)
                lines += cpuset("0-1", "0-3", "0-5", "0-7")
                lines += ufshc(true)
            }
            "fast" -> {
                lines += cpuFreq(1708800, 2500000, 1209600, 2304000)
                lines += inputBoost(1804800, 1939200, 500)
                lines += gpuPlUp(2, gpuMinPl)
                lines += schedBoost(1, 2) + stuneTopApp(1, 20)
                lines += coreCtl0("off") + coreCtl6("off")
                lines += schedConfig(50, 75, 300, 400)
                lines += schedLimit(5000, 2000, 0, 0)
                lines += cpuset("0-1", "0-3", "0-5", "0-7")
                lines += ufshc(true)
            }
            "pedestal" -> {
                lines += cpuFreq(1804800, 2500000, 2304000, 2304000)
                lines += inputBoost(0, 0, 0)
                lines += gpuPlUp(4, gpuMinPl)
                lines += schedBoost(1, 2) + stuneTopApp(1, 100)
                lines += coreCtl0("off") + coreCtl6("off")
                lines += schedConfig(57, 75, 300, 400)
                lines += schedLimit(8000, 8000, 0, 0)
                lines += cpuset("0-1", "0-3", "0-7", "0-7")
                lines += ufshc(true)
            }
            else -> return
        }

        applyLines(lines)
    }
}
