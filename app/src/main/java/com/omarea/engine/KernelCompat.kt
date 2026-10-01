package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import org.json.JSONObject

/**
 * Kernel/ROM capability registry + lock table.
 *
 * Every tuning feature the app writes is declared here with the node(s) that
 * prove it exists. A batch probe (one root-shell round trip) checks them all
 * against the *running* kernel/ROM; anything missing is **locked**:
 *
 *  - [ProfileApplier] already reports missing nodes per apply (`SCENE_MISSING`),
 *  - [ParameterCatalog] marks locked paths,
 *  - the Tweaks screen and diagnostics show the lock list,
 *  - `docs/KERNEL.md` keeps the port wishlist for kernel patches.
 *
 * This keeps tuning.json stable across kernels: a key whose kernel support is
 * absent is inert and reported, never silently half-applied.
 *
 * Responsibility: feature registry, probe script/parse, cached snapshot.
 * Non-goals: executing tuning, per-op skipping (ProfileApplier owns that).
 */
object KernelCompat {

    enum class Axis { KERNEL, ROM }

    data class Feature(
        val id: String,
        val label: String,
        val axis: Axis,
        /** Paths probed for existence; suffix `#nonempty` also requires content. */
        val probes: List<String>,
        /** Sysfs/proc path prefixes used to map plan paths back to this feature. */
        val nodes: List<String>,
        /** Tuning-JSON key prefixes (e.g. `gpu.throttling`) for catalog mapping. */
        val keys: List<String> = emptyList(),
        /** What a kernel patch must provide when this is locked. */
        val hint: String = ""
    )

    val features: List<Feature> = listOf(
        // ------------------------------------------------------------ kernel
        Feature(
            "core_ctl", "core_ctl hotplug", Axis.KERNEL,
            listOf("/sys/devices/system/cpu/cpu6/core_ctl/enable"),
            listOf("/core_ctl"),
            keys = listOf("core_ctl.")
        ),
        Feature(
            "sched_walt", "WALT migration tunables", Axis.KERNEL,
            listOf("/proc/sys/kernel/sched_upmigrate"),
            listOf(
                "/proc/sys/kernel/sched_upmigrate",
                "/proc/sys/kernel/sched_downmigrate",
                "/proc/sys/kernel/sched_group_upmigrate",
                "/proc/sys/kernel/sched_group_downmigrate",
                "/proc/sys/kernel/sched_walt_rotate_big_tasks",
                "/proc/sys/kernel/sched_little_cluster_coloc_fmin_khz",
                "/proc/sys/kernel/sched_boost"
            ),
            keys = listOf(
                "sched.downmigrate", "sched.upmigrate",
                "sched.group_downmigrate", "sched.group_upmigrate",
                "sched.walt_rotate_big_tasks", "sched.little_cluster_coloc_fmin_khz",
                "sched.boost"
            )
        ),
        Feature(
            "sched_boost_top_app", "top-app sched boost", Axis.KERNEL,
            listOf("/proc/sys/kernel/sched_boost_top_app"),
            listOf("/proc/sys/kernel/sched_boost_top_app"),
            keys = listOf("sched.boost_top_app"),
            hint = "sysctl sched_boost_top_app (community kernels have it; vanilla tree does not)"
        ),
        Feature(
            "sched_kernel_basic", "kernel sched timings", Axis.KERNEL,
            listOf("/proc/sys/kernel/sched_latency_ns"),
            listOf(
                "/proc/sys/kernel/sched_latency_ns",
                "/proc/sys/kernel/sched_min_granularity_ns",
                "/proc/sys/kernel/sched_wakeup_granularity_ns"
            ),
            keys = listOf(
                "sched.sched_latency_ns",
                "sched.sched_min_granularity_ns",
                "sched.sched_wakeup_granularity_ns"
            )
        ),
        Feature(
            "cpu_boost", "input boost", Axis.KERNEL,
            listOf("/sys/module/cpu_boost/parameters/input_boost_freq"),
            listOf(
                "/sys/module/cpu_boost/parameters/input_boost_freq",
                "/sys/module/cpu_boost/parameters/input_boost_ms",
                "/sys/module/cpu_boost/parameters/sched_boost_on_input"
            ),
            keys = listOf("input_boost.")
        ),
        Feature(
            "cpu_boost_powerkey", "power-key boost", Axis.KERNEL,
            listOf("/sys/module/cpu_boost/parameters/powerkey_input_boost_freq"),
            listOf(
                "/sys/module/cpu_boost/parameters/powerkey_input_boost_freq",
                "/sys/module/cpu_boost/parameters/powerkey_input_boost_ms",
                "/sys/module/cpu_boost/parameters/sched_boost_on_powerkey_input"
            ),
            keys = listOf("powerkey_input_boost.")
        ),
        Feature(
            "cpu_boost_prefer_idle", "boost prefer-idle", Axis.KERNEL,
            listOf("/sys/module/cpu_boost/parameters/sched_prefer_idle"),
            listOf("/sys/module/cpu_boost/parameters/sched_prefer_idle")
        ),
        Feature(
            "msm_performance", "msm_performance locks", Axis.KERNEL,
            listOf("/sys/module/msm_performance/parameters/cpu_max_freq"),
            listOf("/sys/module/msm_performance/parameters")
        ),
        Feature(
            "schedutil_tunables", "schedutil tunables", Axis.KERNEL,
            listOf("/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq"),
            listOf("/schedutil/")
        ),
        Feature(
            "kgsl_pwrlevels", "GPU pwrlevels", Axis.KERNEL,
            listOf("/sys/class/kgsl/kgsl-3d0/default_pwrlevel"),
            listOf(
                "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
                "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
                "/sys/class/kgsl/kgsl-3d0/default_pwrlevel",
                "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel"
            ),
            keys = listOf(
                "gpu.min_pwrlevel", "gpu.max_pwrlevel",
                "gpu.default_pwrlevel", "gpu.thermal_pwrlevel"
            )
        ),
        Feature(
            "kgsl_throttling", "GPU thermal throttling", Axis.KERNEL,
            listOf("/sys/class/kgsl/kgsl-3d0/throttling"),
            listOf("/sys/class/kgsl/kgsl-3d0/throttling"),
            keys = listOf("gpu.throttling")
        ),
        Feature(
            "lpm_levels", "LPM sleep control", Axis.KERNEL,
            listOf("/sys/module/lpm_levels/parameters/sleep_disabled"),
            listOf("/sys/module/lpm_levels/parameters/sleep_disabled"),
            keys = listOf("lpm_sleep_disabled")
        ),
        Feature(
            "vm_basic", "VM tunables", Axis.KERNEL,
            listOf("/proc/sys/vm/dirty_background_ratio"),
            listOf(
                "/proc/sys/vm/dirty_background_ratio",
                "/proc/sys/vm/dirty_ratio",
                "/proc/sys/vm/overcommit_ratio",
                "/proc/sys/vm/vfs_cache_pressure",
                "/proc/sys/vm/swap_ratio"
            ),
            keys = listOf("vm.")
        ),
        Feature(
            "vm_page_cluster", "vm.page_cluster (legacy)", Axis.KERNEL,
            listOf("/proc/sys/vm/page_cluster"),
            listOf("/proc/sys/vm/page_cluster"),
            keys = listOf("vm.page_cluster"),
            hint = "removed from modern kernels; no patch needed, keep the key inert"
        ),
        Feature(
            "read_ahead", "block read-ahead", Axis.KERNEL,
            listOf("/sys/block/sda/queue/read_ahead_kb"),
            listOf("/sys/block/sda/queue/read_ahead_kb"),
            keys = listOf("vm.read_ahead_kb")
        ),
        Feature(
            "cpuset_std", "standard cpusets", Axis.KERNEL,
            listOf("/dev/cpuset/top-app/cpus"),
            listOf(
                "/dev/cpuset/background/cpus",
                "/dev/cpuset/system-background/cpus",
                "/dev/cpuset/foreground/cpus",
                "/dev/cpuset/top-app/cpus",
                "/dev/cpuset/foreground/boost/cpus"
            ),
            keys = listOf(
                "cpuset.background", "cpuset.system-background",
                "cpuset.foreground", "cpuset.top-app"
            )
        ),
        Feature(
            "cpuset_miui", "MIUI extra cpusets", Axis.KERNEL,
            listOf("/dev/cpuset/game/cpus"),
            listOf(
                "/dev/cpuset/game",
                "/dev/cpuset/gamelite",
                "/dev/cpuset/vr",
                "/dev/cpuset/background/untrustedapp"
            ),
            keys = listOf("cpuset.game", "cpuset.gamelite", "cpuset.vr")
        ),
        Feature(
            "cores_online", "CPU online control", Axis.KERNEL,
            listOf("/sys/devices/system/cpu/cpu6/online"),
            (0..7).map { "/sys/devices/system/cpu/cpu$it/online" },
            keys = listOf("cores_online.")
        ),
        Feature(
            "stune", "schedtune", Axis.KERNEL,
            listOf("/dev/stune/top-app/schedtune.boost"),
            listOf("/dev/stune/"),
            keys = listOf("sched.top_app_prefer_idle", "sched.top_app_boost")
        ),
        Feature(
            "thermal_message", "thermal_message mailboxes", Axis.KERNEL,
            listOf("/sys/class/thermal/thermal_message/sconfig"),
            listOf(
                "/sys/class/thermal/thermal_message/sconfig",
                "/sys/class/thermal/thermal_message/temp_state",
                "/sys/class/thermal/thermal_message/board_sensor"
            ),
            keys = listOf("thermal_sconfig")
        ),
        Feature(
            "thermal_message_cpu_limits", "thermal cpu_limits", Axis.KERNEL,
            listOf("/sys/class/thermal/thermal_message/cpu_limits"),
            listOf("/sys/class/thermal/thermal_message/cpu_limits")
        ),
        Feature(
            "lmk", "low-memory killer", Axis.KERNEL,
            listOf("/sys/module/lowmemorykiller/parameters/minfree"),
            listOf("/sys/module/lowmemorykiller/parameters")
        ),
        Feature(
            "ufs_control", "UFS power knobs", Axis.KERNEL,
            listOf("/sys/devices/platform/soc/1d84000.ufshc/clkscale_enable"),
            listOf(
                "/sys/devices/platform/soc/1d84000.ufshc/clkscale_enable",
                "/sys/devices/platform/soc/1d84000.ufshc/clkgate_enable",
                "/sys/devices/platform/soc/1d84000.ufshc/hibern8_on_idle_enable",
                "/sys/class/devfreq/1d84000.ufshc"
            ),
            keys = listOf("ufs")
        ),
        Feature(
            "ufs_health", "UFS health descriptor", Axis.KERNEL,
            listOf("/sys/devices/platform/soc/1d84000.ufshc/health_descriptor/life_time_estimation_a#nonempty"),
            listOf("/sys/devices/platform/soc/1d84000.ufshc/health_descriptor"),
            hint = "UFS health descriptor support (removed by the current kernel patch)"
        ),
        Feature(
            "bus_dcvs", "bus DCVS domains", Axis.KERNEL,
            listOf("/sys/devices/system/cpu/bus_dcvs/DDR"),
            listOf("/sys/devices/system/cpu/bus_dcvs"),
            hint = "bus_dcvs driver (DDR/DDRQOS/L3/LLCC with min/max/boost_freq; stock MIUI kernel has it)"
        ),
        Feature(
            "ddr_fixed", "DDR fixed frequency", Axis.KERNEL,
            listOf("/dev/scene/ddr_frequency_mhz"),
            listOf("/dev/scene/ddr_frequency_mhz", "/dev/scene/debug/qcom_aoss/ddr_frequency_mhz"),
            hint = "scene DDR fixed-freq nodes (/dev/scene/ddr_frequency_mhz readback, /dev/scene/debug/qcom_aoss/ddr_frequency_mhz write in MHz)"
        ),
        Feature(
            "perfmgr", "MIUI perfmgr", Axis.KERNEL,
            listOf("/sys/module/perfmgr/parameters/perfmgr_enable"),
            listOf("/sys/module/perfmgr/parameters"),
            hint = "perfmgr module (perfmgr_enable 0/1; MIUI stock kernel has it)"
        ),
        Feature(
            "fpsgo", "fpsgo frame stats", Axis.KERNEL,
            listOf("/sys/kernel/fpsgo/fstb/fpsgo_status"),
            listOf("/sys/kernel/fpsgo"),
            hint = "fpsgo fstb table (text: header starting 'tid' with 'name' and 'currentFPS' columns)"
        ),
        Feature(
            "measured_fps", "drm measured_fps", Axis.KERNEL,
            listOf(
                "/sys/class/drm/sde-crtc-0/measured_fps",
                "/sys/class/graphics/fb0/measured_fps"
            ),
            listOf(
                "/sys/class/drm/sde-crtc-0/measured_fps",
                "/sys/class/graphics/fb0/measured_fps"
            )
        ),
        Feature(
            "migt_glk", "migt GLK clamp", Axis.KERNEL,
            listOf("/sys/module/migt/parameters/glk_maxfreq"),
            listOf("/sys/module/migt/parameters/glk_maxfreq"),
            hint = "migt param glk_maxfreq (lahaina-era ThermalDisguise writes '0 0 0')"
        ),
        Feature(
            "msm_thermal", "MSM thermal control", Axis.KERNEL,
            listOf("/sys/module/msm_thermal/core_control/enabled"),
            listOf(
                "/sys/module/msm_thermal/core_control/enabled",
                "/sys/module/msm_thermal/parameters/enabled",
                "/sys/module/msm_thermal/vdd_restriction/enabled"
            ),
            hint = "msm_thermal module (core_control / vdd_restriction / parameters.enabled)"
        ),
        Feature(
            "uclamp", "sched uclamp", Axis.KERNEL,
            listOf("/proc/sys/kernel/sched_util_clamp_min"),
            listOf(
                "/proc/sys/kernel/sched_util_clamp_min",
                "/proc/sys/kernel/sched_util_clamp_max",
                "/proc/sys/kernel/sched_util_clamp_min_rt_default"
            ),
            hint = "util-clamp sysctls (SCHED_UTIL_CLAMP; newer community kernels)"
        ),
        Feature(
            "adrenoboost", "Adreno boost", Axis.KERNEL,
            listOf("/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"),
            listOf("/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost"),
            hint = "kgsl adrenoboost devfreq attr (0-3; off-duty GPU boost)"
        ),
        Feature(
            "charging_pause", "charge pause nodes", Axis.KERNEL,
            listOf("/sys/class/power_supply/battery/battery_charging_enabled"),
            listOf(
                "/sys/class/power_supply/battery/battery_charging_enabled",
                "/sys/class/power_supply/battery/input_suspend",
                "/sys/class/qcom-battery/input_suspend"
            )
        ),
        Feature(
            "fastcharge", "fast-charge limit", Axis.KERNEL,
            listOf("/sys/class/power_supply/battery/constant_charge_current_max"),
            listOf(
                "/sys/class/power_supply/battery/constant_charge_current_max",
                "/sys/class/power_supply/main/constant_charge_current_max",
                "/sys/class/power_supply/battery/constant_charge_current"
            )
        ),
        Feature(
            "usb_pd", "USB PD control", Axis.KERNEL,
            listOf("/sys/class/power_supply/usb/pd_allowed"),
            listOf("/sys/class/power_supply/usb/pd_allowed", "/sys/class/power_supply/usb/pd_active")
        ),
        // ----------------------------------------------------------------- rom
        Feature(
            "mi_thermald", "mi_thermald daemon", Axis.ROM,
            listOf("/system/vendor/bin/mi_thermald"),
            listOf("/system/vendor/bin/mi_thermald")
        ),
        Feature(
            "thermal_engine", "thermal-engine (unused)", Axis.ROM,
            listOf("/system/vendor/bin/thermal-engine"),
            listOf("/system/vendor/bin/thermal-engine")
        ),
        Feature(
            "perf_hal", "Qualcomm perf HAL", Axis.ROM,
            listOf("/system/vendor/bin/hw/vendor.qti.hardware.perf@2.2-service"),
            listOf("/system/vendor/bin/hw/vendor.qti.hardware.perf@2.2-service")
        ),
        Feature(
            "perf_configs", "perf boost configs", Axis.ROM,
            listOf("/system/vendor/etc/perf/perfboostsconfig.xml"),
            listOf(
                "/system/vendor/etc/perf/perfboostsconfig.xml",
                "/system/vendor/etc/perf/perfconfigstore.xml"
            )
        ),
        Feature(
            "powerhint", "power hint config", Axis.ROM,
            listOf("/system/vendor/etc/powerhint.xml"),
            listOf("/system/vendor/etc/powerhint.xml")
        ),
        Feature(
            "perfservice", "perfservice helper", Axis.ROM,
            listOf("/system_ext/bin/perfservice"),
            listOf("/system_ext/bin/perfservice")
        ),
        Feature(
            "thermal_data", "mi_thermald data dir", Axis.ROM,
            listOf("/data/vendor/thermal"),
            listOf("/data/vendor/thermal")
        ),
        Feature(
            "apatch_tools", "APatch tooling", Axis.ROM,
            listOf("/data/adb/ap/bin/magiskpolicy"),
            listOf("/data/adb/ap/bin/magiskpolicy")
        )
    )

    /** CONFIG_* flags decoded from /proc/config.gz (when present). */
    val configKeys = listOf(
        "CPU_BOOST", "MSM_PERFORMANCE", "SCHED_WALT", "SCHED_CORE_CTL",
        "ANDROID_LOW_MEMORY_KILLER", "MIGT", "MSM_THERMAL",
        "CPU_FREQ_GOV_SCHEDUTIL", "THERMAL_WRITABLE_TRIPS",
        "QTI_THERMAL_LIMITS_DCVS", "ZRAM"
    )

    data class Snapshot(
        val kernel: String,
        val available: Set<String>,
        val configs: Map<String, String>
    ) {
        fun isLocked(id: String) = id !in available
        val locked: List<Feature> get() = features.filter { isLocked(it.id) }
        val availableCount: Int get() = features.count { !isLocked(it.id) }
    }

    // ------------------------------------------------------------- probing
    /** One round-trip probe script (markers reuse [TweakCommands.parseSections]). */
    fun probeScript(): String {
        val sb = StringBuilder()
        sb.append("echo \"@@kernel@@\"\nuname -r\n")
        sb.append("echo \"@@features@@\"\n")
        for (f in features) {
            sb.append("v=0\n")
            for (probe in f.probes) {
                val path = probe.removeSuffix("#nonempty")
                val test = if (probe.endsWith("#nonempty")) "[ -s \"$path\" ]" else "[ -e \"$path\" ]"
                sb.append("$test && v=1\n")
            }
            sb.append("echo \"${f.id}=\$v\"\n")
        }
        sb.append("echo \"@@configs@@\"\n")
        sb.append("zcat /proc/config.gz 2>/dev/null | grep -E \"^CONFIG_(${configKeys.joinToString("|")})=\"\n")
        return sb.toString()
    }

    /** Pure parser for [probeScript] output. */
    fun parse(output: String): Snapshot {
        val sections = TweakCommands.parseSections(output)
        val available = sections["features"].orEmpty().lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim().takeIf { line.substring(idx + 1).trim() == "1" }
        }.toSet()
        val configs = sections["configs"].orEmpty().lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
        val kernel = sections["kernel"].orEmpty().lines().firstOrNull()?.trim().orEmpty()
        return Snapshot(kernel, available, configs)
    }

    // ------------------------------------------------------------- caching
    private const val PREFS = "scene_compat"
    private const val KEY_KERNEL = "kernel"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_CONFIGS = "configs"

    /** Cached snapshot; probes once when empty. */
    fun snapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kernel = prefs.getString(KEY_KERNEL, "").orEmpty()
        if (kernel.isEmpty()) return refresh(context)
        val available = prefs.getStringSet(KEY_AVAILABLE, emptySet()) ?: emptySet()
        @Suppress("UNCHECKED_CAST")
        val configs = JSONObject(prefs.getString(KEY_CONFIGS, "{}") ?: "{}").let { obj ->
            buildMap {
                obj.keys().forEach { k -> put(k, obj.optString(k)) }
            }
        }
        return Snapshot(kernel, available, configs)
    }

    /** Runs the probe and updates the cache. */
    fun refresh(context: Context): Snapshot {
        val snap = try {
            parse(RootShell.run(probeScript()))
        } catch (ex: Exception) {
            ShellLog.log("KernelCompat.refresh", ex.message ?: "error", error = true)
            return snapshot(context)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configs = JSONObject().apply { snap.configs.forEach { (k, v) -> put(k, v) } }
        prefs.edit()
            .putString(KEY_KERNEL, snap.kernel)
            .putStringSet(KEY_AVAILABLE, snap.available)
            .putString(KEY_CONFIGS, configs.toString())
            .apply()
        ShellLog.log(
            "KernelCompat",
            "kernel ${snap.kernel}: ${snap.availableCount}/${features.size} features available",
            error = snap.locked.isNotEmpty()
        )
        return snap
    }

    // ---------------------------------------------------------- path mapping
    /** Locked feature owning [path] (sysfs path or tuning-JSON key), or null. */
    fun lockedFeatureForPath(path: String, locked: Set<String>): Feature? {
        var best: Feature? = null
        var bestLen = -1
        for (feature in features) {
            if (feature.id !in locked) continue
            for (prefix in feature.nodes + feature.keys) {
                if ((path == prefix || path.startsWith(prefix)) && prefix.length > bestLen) {
                    best = feature
                    bestLen = prefix.length
                }
            }
        }
        return best
    }

    /** Short report line for diagnostics/UI. */
    fun report(snapshot: Snapshot): String {
        val locked = snapshot.locked
        if (locked.isEmpty()) return "all ${features.size} features available"
        return "locked ${locked.size}/${features.size}: " +
            locked.joinToString(", ") { it.id } +
            locked.filter { it.hint.isNotEmpty() }
                .take(3)
                .joinToString(prefix = " — port: ", separator = "; ") { "${it.id} (${it.hint})" }
    }
}
