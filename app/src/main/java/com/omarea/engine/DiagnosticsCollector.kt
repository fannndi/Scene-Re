package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.runtime.BatterySaverMode
import com.omarea.runtime.BypassCharging
import com.omarea.runtime.DndController
import com.omarea.runtime.NoRootMode
import com.omarea.runtime.PostApplyDriftGuard
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Collects a structured snapshot of device + app state, rendered either as
 * LLM-friendly Markdown or JSON. Read-only: nothing here mutates device state.
 */
object DiagnosticsCollector {

    /** Literal dollar sign for shell expressions inside Kotlin raw strings. */
    private const val D = "$"

    /** Read-only charge-state paths for the "Charging (read-only)" section. */
    private val CHARGE_PATHS = listOf(
        "/sys/class/power_supply/usb/real_type",
        "/sys/class/power_supply/usb/present",
        "/sys/class/power_supply/usb/pd_allowed",
        "/sys/class/power_supply/usb/pd_active",
        "/sys/class/power_supply/usb/quick_charge_type",
        "/sys/class/power_supply/usb/voltage_now",
        "/sys/class/power_supply/usb/input_current_now",
        "/sys/class/power_supply/bms/voltage_avg",
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/battery/health",
        "/sys/class/power_supply/battery/battery_charging_enabled",
        "/sys/class/power_supply/battery/input_suspend",
        "/sys/class/power_supply/battery/step_charging_enabled",
        "/sys/class/power_supply/battery/constant_charge_current_max",
        "/sys/class/power_supply/bms/charge_full",
        "/sys/class/power_supply/battery/temp"
    )


    data class Section(val title: String, val body: String, val isCode: Boolean = true)

    private fun appVersion(context: Context): Pair<String, Long> = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "?") to pi.longVersionCode
    } catch (ex: Exception) { "?" to 0L }

    private fun sh(cmd: String): String = RootShell.run(cmd)

    fun collectSections(context: Context): List<Section> {
        val sections = ArrayList<Section>()

        sections += Section(
            "Device & build",
            sh(
                """
                echo "model        : $(getprop ro.product.model)"
                echo "device       : $(getprop ro.product.device)"
                echo "board        : $(getprop ro.board.platform)"
                echo "brand        : $(getprop ro.product.brand)"
                echo "android      : $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
                echo "miui         : $(getprop ro.miui.ui.version.name)"
                echo "build        : $(getprop ro.build.display.id)"
                echo "kernel       : $(uname -r)"
                echo "boot         : $(getprop ro.bootmode) / $(getprop sys.boot_completed)"
                """.trimIndent()
            )
        )

        // Root access: the single most important line for a no-root boot — it
        // says WHY nothing was applied, without faking "applied" evidence.
        sections += Section(
            "Root access",
            buildString {
                appendLine("state        : ${CheckRootStatus.describe(context)}")
                appendLine(
                    "engine       : " +
                        if (ProfileController.isEngineOff(context)) "OFF (stock)" else "ON"
                )
                appendLine("saver_overlay: ${BatterySaverMode.describe(context)}")
                appendLine("dnd_app_mode : ${DndController.describe(context)}")
                appendLine("known_games  : ${com.omarea.engine.GameList.size(context)} packages")
                appendLine("bypass_chg   : ${BypassCharging.describe(context)}")
                NoRootMode.lastEvidence(context)?.let { appendLine("no_root_boot : $it") }
                if (NoRootMode.isRestorePending(context)) {
                    appendLine(
                        if (CheckRootStatus.isAvailable()) {
                            "restore      : pending (root is back — one tap on Home)"
                        } else {
                            "restore      : pending (offered once root is back)"
                        }
                    )
                }
            }.trimEnd(),
            isCode = false
        )

        sections += Section(
            "Root & tools",
            sh(
                """
                echo "id           : $(id -u)"
                echo "which su     : $(which su)"
                echo "busybox      : $(busybox 2>/dev/null | head -1)"
                echo "magisk       : $(magisk -v 2>/dev/null)"
                """.trimIndent()
            )
        )

        val compat = KernelCompat.refresh(context)
        sections += Section(
            "Kernel compatibility",
            buildString {
                appendLine("kernel   : ${compat.kernel}")
                if (compat.privilegeLimited) {
                    // Monitor mode: the probe cannot read the nodes — saying
                    // "locked" here would be a false negative.
                    appendLine("features : not probed (no root) — lock list unavailable")
                } else {
                    appendLine("features : ${compat.availableCount}/${KernelCompat.features.size} available")
                    val locked = compat.locked
                    if (locked.isEmpty()) {
                        appendLine("locked   : none")
                    } else {
                        appendLine("locked   :")
                        for (feature in locked) {
                            appendLine(
                                "  ${feature.id} (${feature.axis.name.lowercase()}): ${feature.label}" +
                                    if (feature.hint.isNotEmpty()) "  → ${feature.hint}" else ""
                            )
                        }
                    }
                    if (compat.configs.isNotEmpty()) {
                        appendLine("config   : " + compat.configs.entries.joinToString(" ") { "${it.key}=${it.value}" })
                    }
                }
            },
            isCode = false
        )

        // Kernel crash evidence: pstore survives a reboot, the dmesg tail
        // catches live faults. Read-only, tail-bounded (Encore's save_logs
        // pattern, docs/ATTRIBUTION.md).
        sections += Section(
            "Kernel crash & log",
            sh(
                """
                echo "pstore       :"
                if [ -d /sys/fs/pstore ] && [ -n "${D}(ls -A /sys/fs/pstore 2>/dev/null)" ]; then
                  for f in /sys/fs/pstore/*; do
                    echo "--- ${D}(basename ${D}f) (${D}(wc -c < ${D}f) bytes)"
                    tail -n 25 "${D}f" 2>/dev/null
                  done
                else
                  echo "  (empty - no kernel crash captured)"
                fi
                echo ""
                echo "dmesg tail   :"
                dmesg 2>/dev/null | tail -n 30
                """.trimIndent()
            )
        )

        sections += Section(
            "Measurement",
            buildString {
                val locale = java.util.Locale.US
                val fps = com.omarea.util.fps.FpsSampler().sample()
                appendLine(
                    "fps          : " + if (fps == null) {
                        "no valid source"
                    } else {
                        String.format(locale, "%.1f (%s, frames=%d, jank=%d, span=%dms)", fps.fps, fps.source, fps.frames, fps.jankFrames, fps.spanMs)
                    }
                )
                val current = com.omarea.util.battery.BatterySampler.sample(context)
                appendLine("current      : median=${current.currentMa}mA avg=${current.averageMa ?: "-"}mA last_raw=${current.rawUa}uA [${current.source}]")
                val mem = com.omarea.util.measure.MemSnapshot.read()
                appendLine(
                    "ram          : " + (mem?.let {
                        "${it.usedPercent}% used, ${it.memAvailableKb / 1024}MB available of ${it.memTotalKb / 1024}MB"
                    } ?: "unavailable")
                )
                appendLine(
                    "zram         : " + (mem?.let {
                        val ratio = it.zramCompression?.let { r -> String.format(locale, " (%.2fx)", r) } ?: ""
                        "${it.zramUsedMb}MB uncompressed -> ${it.zramMemUsedMb}MB RAM$ratio, total ${it.zramTotalMb}MB"
                    } ?: "unavailable")
                )
                val cpuLoad = com.omarea.util.CpuLoadUtils()
                appendLine("cpu_load     : ${cpuLoad.cpuLoadSum.toInt()}% (window ${com.omarea.util.CpuLoadUtils.getLastWindowMs()}ms)")
                appendLine("gpu_load     : ${com.omarea.util.GpuUtils.getGpuLoad()}%  freq=${com.omarea.util.GpuUtils.getGpuFreq()}MHz")
                appendLine("temperature  : battery ${com.omarea.data.GlobalStatus.updateBatteryTemperature()}C, cpu ${cpuLoad.cpuTemperatureText}")
                val (designMah, designSource) = com.omarea.util.measure.DesignCapacity.resolve(context)
                appendLine("design_cap   : ${if (designMah > 0) "${designMah}mAh [$designSource]" else "unavailable"}")
                appendLine("measure_log  : ${com.omarea.util.measure.MeasureLog.status()}")
            },
            isCode = false
        )

        sections += Section(
            "Charging (read-only)",
            buildString {
                val locale = java.util.Locale.US
                fun f1(value: Double?): String =
                    if (value == null) "-" else String.format(locale, "%.1f", value)

                appendLine("policy       : READ-ONLY — Scene never modifies charge parameters (ROM/kernel owns charging)")

                val v = com.omarea.util.measure.SysReader.read(CHARGE_PATHS)
                fun p(path: String): String? = v[path]?.takeIf { it.isNotBlank() && it != "error" }
                fun i(path: String): Long? = p(path)?.toLongOrNull()

                val usbMv = i("/sys/class/power_supply/usb/voltage_now")?.div(1000)
                val usbMa = i("/sys/class/power_supply/usb/input_current_now")?.div(1000)
                val battMv = i("/sys/class/power_supply/bms/voltage_avg")?.div(1000)
                    ?: i("/sys/class/power_supply/battery/voltage_now")?.div(1000)

                appendLine(
                    "usb          : type=${p("/sys/class/power_supply/usb/real_type") ?: "-"} " +
                        "present=${p("/sys/class/power_supply/usb/present") ?: "-"} " +
                        "pd_allowed=${p("/sys/class/power_supply/usb/pd_allowed") ?: "-"} " +
                        "pd_active=${p("/sys/class/power_supply/usb/pd_active") ?: "-"} " +
                        "qc_type=${p("/sys/class/power_supply/usb/quick_charge_type") ?: "-"}"
                )
                appendLine(
                    "usb power    : ${usbMv ?: "-"}mV x ${usbMa ?: "-"}mA = " +
                        "${f1(if (usbMv != null && usbMa != null) usbMv.toDouble() * usbMa.toDouble() / 1000.0 else null)}mW" +
                        "  [raw uV/µA -> decoded]"
                )
                appendLine(
                    "charge state : status=${p("/sys/class/power_supply/battery/status") ?: "-"} " +
                        "health=${p("/sys/class/power_supply/battery/health") ?: "-"} " +
                        "charging_enabled=${p("/sys/class/power_supply/battery/battery_charging_enabled") ?: "-"} " +
                        "input_suspend=${p("/sys/class/power_supply/battery/input_suspend") ?: "-"} " +
                        "step=${p("/sys/class/power_supply/battery/step_charging_enabled") ?: "-"}"
                )
                appendLine("cc_max       : ${p("/sys/class/power_supply/battery/constant_charge_current_max") ?: "-"} (raw µA, ROM-owned)")
                val (designMah, designSource) = com.omarea.util.measure.DesignCapacity.resolve(context)
                appendLine(
                    "capacity     : ${com.omarea.data.GlobalStatus.batteryCapacity}% " +
                        "charge_full=${i("/sys/class/power_supply/bms/charge_full")?.div(1000) ?: "-"}mAh " +
                        "design=${if (designMah > 0) "${designMah}mAh [$designSource]" else "-"}"
                )
                appendLine(
                    "battery_temp : ${f1(com.omarea.data.GlobalStatus.updateBatteryTemperature())}C decoded  " +
                        "(raw=${p("/sys/class/power_supply/battery/temp") ?: "-"} decidegrees)"
                )

                val current = com.omarea.util.battery.BatterySampler.sample(context)
                appendLine(
                    "battery_i    : median=${current.currentMa}mA avg=${current.averageMa ?: "-"}mA " +
                        "raw=${current.rawUa}uA [${current.source}] valid=${current.valid}"
                )
                appendLine(
                    "batt power   : " +
                        "${f1(if (battMv != null) battMv.toDouble() * current.currentMa / 1000.0 else null)}mW" +
                        "  [bms/voltage_avg x median(current_now)]"
                )
                append(
                    sh(
                        """
                        echo "legacy artif.: bp=${'$'}(getprop vtools.bp) fastcharge=${'$'}(getprop vtools.fastcharge) ccmax_backup=${'$'}(getprop vtools.charge.current.max) scene_ccmax=$([ -f /data/adb/.scene_ccmax ] && echo present || echo absent)"
                        echo "               (cleared once at boot by ChargeStockRestorer; empty = clean)"
                        """.trimIndent()
                    )
                )
            },
            isCode = false
        )

        sections += Section(
            "Profile engine & daemons",
            buildString {
                val platform = com.omarea.util.PlatformUtils().getCPUName()
                appendLine("engine_off    : " + com.omarea.engine.ProfileController.isEngineOff(context))
                appendLine(
                    "tuning_source : " +
                        (if (com.omarea.engine.TuningRepository.hasUserCopy(platform)) "user copy" else "bundled") +
                        " (" + com.omarea.engine.TuningRepository.userFile(platform).absolutePath + ")"
                )
                appendLine(
                    "thermal(Kt)   : " +
                        (if (ThermalService.isRunning) "ThermalService running" else "stopped")
                )
                append(
                    sh(
                        """
                        echo "last_mode     : ${'$'}(getprop vtools.powercfg) [app ${'$'}(getprop vtools.powercfg_app)]"
                        echo "mi_thermald   : ${'$'}(getprop init.svc.mi_thermald)"
                        echo "miuibooster   : ${'$'}(getprop init.svc.miuibooster)"
                        echo "thermal(sh)   : ${'$'}(pgrep -f 'scene_thermald[.]sh' | head -1)"
                        echo "profile_max   : ${'$'}(cat /data/local/tmp/scene_thermald.profile_max 2>/dev/null)"
                        echo "thermal_state : ${'$'}(cat /data/local/tmp/scene_thermald.state 2>/dev/null)"
                        """.trimIndent()
                    )
                )
            },
            isCode = false
        )

        sections += Section(
            "SELinux",
            buildString {
                appendLine("enforce     : ${sh("getenforce").trim()}")
                appendLine(
                    "direct_write: " +
                        com.omarea.engine.SepolicyOptimizer.directWritesEnabled(context) +
                        " (opt-in toggle, Tweaks ▸ Root)"
                )
                // avc-denial meter: the measurable proof the scoped rules work
                // (baseline on this device was ~8 denials/10s before tuning).
                val avc = sh(
                    "dmesg 2>/dev/null | grep -c 'avc:  denied' ; " +
                        "logcat -d -b all -t 2000 2>/dev/null | grep -c 'avc: *denied'"
                ).trim().lines().mapNotNull { it.trim().toIntOrNull() }
                appendLine("avc(dmesg) : ${avc.getOrElse(0) { -1 }}")
                appendLine("avc(logcat): ${avc.getOrElse(1) { -1 }} (last 2000 lines)")
                append(
                    sh(
                        """
                        echo "scene_policy: ${'$'}([ -f /data/local/tmp/scene_policy.rules ] && wc -l < /data/local/tmp/scene_policy.rules || echo missing)"
                        echo "node modes  : $(stat -c '%a %n' /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null)"
                        """.trimIndent()
                    )
                )
            },
            isCode = false
        )

        sections += Section(
            "SELinux capabilities (direct-write)",
            buildString {
                appendLine(
                    "write rules : " +
                        if (SepolicyOptimizer.directWritesEnabled(context)) "enabled (opt-in direct writes)"
                        else "disabled (root shell only; toggle in Tweaks)"
                )
                appendLine("probe       : rewrite each node's CURRENT value (no state change)")
                // Fresh probe: also seeds the in-memory cache for this boot.
                for (result in SepolicyCapability.probeAll(context)) {
                    appendLine(
                        (result.id + "            ").substring(0, 12) + ": " + result.status +
                            if (result.ok) "" else "   (" + result.label + ")"
                    )
                }
                val report = java.io.File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "debug/sepolicy-caps.txt"
                )
                appendLine("report      : ${if (report.exists()) report.absolutePath else "not written yet"}")
            },
            isCode = false
        )

        sections += Section(
            "App",
            buildString {
                val (vName, vCode) = appVersion(context)
                appendLine("versionName : $vName")
                appendLine("versionCode : $vCode")
                appendLine("uptime      : ${android.os.SystemClock.elapsedRealtime() / 1000}s")
                appendLine("powercfg    : ${sh("getprop vtools.powercfg").trim()}")
                appendLine("powercfg_app: ${sh("getprop vtools.powercfg_app").trim()}")
                appendLine("battery_pause: ${sh("getprop vtools.bp").trim()}")
                appendLine(
                    "true_off    : " +
                        (if (TrueOff.isOff(context)) "ON (all actuators stopped, reads only)" else "off") +
                        " · a11y=" + (if (TrueOff.isOff(context)) "disabled-by-us" else "user-managed")
                )
                appendLine("soc         : ${sh("getprop ro.soc.model").trim()} (${sh("getprop ro.board.platform").trim()})")
                appendLine("animator_big: ${sh("getprop persist.sys.miui_animator_sched.bigcores").trim()}")
            },
            isCode = false
        )

        sections += Section(
            "CPU (cpufreq / core_ctl)",
            sh(
                """
                echo "online       : $(cat /sys/devices/system/cpu/online)"
                for p in /sys/devices/system/cpu/cpufreq/policy*; do
                  echo "--- $(basename ${D}p)"
                  echo "  governor : $(cat ${D}p/scaling_governor 2>/dev/null)"
                  echo "  cur      : $(cat ${D}p/scaling_cur_freq 2>/dev/null)"
                  echo "  min/max  : $(cat ${D}p/scaling_min_freq 2>/dev/null) / $(cat ${D}p/scaling_max_freq 2>/dev/null)"
                  echo "  avail    : $(cat ${D}p/scaling_available_frequencies 2>/dev/null)"
                  echo "  hispeed  : $(cat ${D}p/schedutil/hispeed_freq 2>/dev/null)"
                done
                echo "--- core_ctl"
                echo "  cpu0 enable=$(cat /sys/devices/system/cpu/cpu0/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu0/core_ctl/min_cpus 2>/dev/null)"
                echo "  cpu6 enable=$(cat /sys/devices/system/cpu/cpu6/core_ctl/enable 2>/dev/null) min=$(cat /sys/devices/system/cpu/cpu6/core_ctl/min_cpus 2>/dev/null)"
                echo "--- msm_performance"
                echo "  max: $(cat /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null)"
                echo "--- boost"
                echo "  input_boost_freq: $(cat /sys/module/cpu_boost/parameters/input_boost_freq 2>/dev/null)"
                echo "  input_boost_ms  : $(cat /sys/module/cpu_boost/parameters/input_boost_ms 2>/dev/null)"
                echo "  powerkey_freq   : $(cat /sys/module/cpu_boost/parameters/powerkey_input_boost_freq 2>/dev/null)"
                echo "  powerkey_ms     : $(cat /sys/module/cpu_boost/parameters/powerkey_input_boost_ms 2>/dev/null)"
                echo "  sched_on_input  : $(cat /sys/module/cpu_boost/parameters/sched_boost_on_input 2>/dev/null)"
                echo "  sched_on_powerkey: $(cat /sys/module/cpu_boost/parameters/sched_boost_on_powerkey_input 2>/dev/null)"
                echo "  sched_prefer_idle: $(cat /sys/module/cpu_boost/parameters/sched_prefer_idle 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "GPU (kgsl)",
            sh(
                """
                echo "governor : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null)"
                echo "cur      : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null)"
                echo "min/max  : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null) / $(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null)"
                echo "avail    : $(cat /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null)"
                echo "pwrlevel : min=$(cat /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null) max=$(cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null) num=$(cat /sys/class/kgsl/kgsl-3d0/num_pwrlevels 2>/dev/null)"
                echo "idle/clamp: default=$(cat /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null) thermal=$(cat /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null) throttling=$(cat /sys/class/kgsl/kgsl-3d0/throttling 2>/dev/null)"
                echo "gpu busy : $(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null)"
                """.trimIndent()
            )
        )

        val sconfigRaw = sh("cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null").trim()
        sections += Section(
            "Thermal",
            buildString {
                append(
                    sh(
                        """
                        for z in /sys/class/thermal/thermal_zone*; do
                          echo "$(cat ${D}z/type 2>/dev/null): $(cat ${D}z/temp 2>/dev/null)"
                        done
                        """.trimIndent()
                    )
                )
                appendLine("--- miui thermal_message")
                appendLine("sconfig           : ${sconfigRaw.ifEmpty { "-" }} [${ThermalProfiles.label(sconfigRaw)}]")
                append(
                    sh(
                        """
                        echo "temp_state        : ${'$'}(cat /sys/class/thermal/thermal_message/temp_state 2>/dev/null)"
                        echo "cpu_limits        : ${'$'}(cat /sys/class/thermal/thermal_message/cpu_limits 2>/dev/null)"
                        echo "boost             : ${'$'}(cat /sys/class/thermal/thermal_message/boost 2>/dev/null)"
                        echo "global_mode       : ${'$'}(cat /data/vendor/thermal/thermal-global-mode 2>/dev/null)"
                        echo "decrypt.txt       : ${'$'}(stat -c '%s bytes, %y' /data/vendor/thermal/decrypt.txt 2>/dev/null)"
                        echo "--- thermal.dump (tail)"
                        tail -6 /data/vendor/thermal/thermal.dump 2>/dev/null
                        """.trimIndent()
                    )
                )
            }
        )

        sections += Section(
            "Memory & swap",
            sh(
                """
                grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree|Cached" /proc/meminfo
                echo "zram mm_stat: $(cat /sys/block/zram0/mm_stat 2>/dev/null)"
                echo "swappiness  : $(cat /proc/sys/vm/swappiness 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Battery",
            sh(
                """
                b=/sys/class/power_supply/battery
                echo "capacity : $(cat ${D}b/capacity 2>/dev/null)"
                echo "status   : $(cat ${D}b/status 2>/dev/null)"
                echo "current  : $(cat ${D}b/current_now 2>/dev/null)"
                echo "voltage  : $(cat ${D}b/voltage_now 2>/dev/null)"
                echo "temp     : $(cat ${D}b/temp 2>/dev/null)"
                echo "charge_en: $(cat ${D}b/battery_charging_enabled 2>/dev/null)"
                echo "cc_max   : $(cat ${D}b/constant_charge_current_max 2>/dev/null)"
                echo "step_chg : $(cat ${D}b/step_charging_enabled 2>/dev/null)"
                echo "temp_lvl : $(cat ${D}b/system_temp_level 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "Cpuset & stune",
            sh(
                """
                echo "background        : $(cat /dev/cpuset/background/cpus 2>/dev/null)"
                echo "system-background : $(cat /dev/cpuset/system-background/cpus 2>/dev/null)"
                echo "foreground        : $(cat /dev/cpuset/foreground/cpus 2>/dev/null)"
                echo "top-app           : $(cat /dev/cpuset/top-app/cpus 2>/dev/null)"
                echo "game (MIUI)       : $(cat /dev/cpuset/game/cpus 2>/dev/null)"
                echo "gamelite (MIUI)   : $(cat /dev/cpuset/gamelite/cpus 2>/dev/null)"
                echo "vr (MIUI)         : $(cat /dev/cpuset/vr/cpus 2>/dev/null)"
                echo "sched_boost       : $(cat /proc/sys/kernel/sched_boost 2>/dev/null)"
                echo "top-app prefer_idle: $(cat /dev/stune/top-app/schedtune.prefer_idle 2>/dev/null) boost: $(cat /dev/stune/top-app/schedtune.boost 2>/dev/null)"
                """.trimIndent()
            )
        )

        sections += Section(
            "ROM harmony (app ↔ ROM ↔ kernel)",
            buildString {
                appendLine(
                    "post_boot  : ${sh("getprop init.svc.qcom-post-boot").trim().ifEmpty { "absent" }}" +
                        " · waited ${PostApplyDriftGuard.postBootWait(context)}ms at boot"
                )
                fun daemon(pattern: String): String =
                    sh("pgrep -f '$pattern' 2>/dev/null | head -1").trim().ifEmpty { "off" }
                appendLine(
                    "daemons    : mi_thermald=${sh("getprop init.svc.mi_thermald").trim().ifEmpty { "off" }}" +
                        " miuibooster=${sh("getprop init.svc.miuibooster").trim().ifEmpty { "off" }}" +
                        " perf-hal=${daemon("vendor.qti.hardware.perf")}" +
                        " perfservice=${daemon("perfservice")}" +
                        " lmkd=${daemon("lmkd")} millet=${daemon("millet_monitor")}"
                )
                appendLine(
                    "engine     : ${if (ProfileController.isEngineOff(context)) "OFF" else "ON"}" +
                        " · guard=${if (ThermalService.isRunning) "running" else "off"}" +
                        " · thermal owner=${if (ProfileController.isEngineOff(context)) "mi_thermald" else "scene guard"}"
                )
                appendLine("stock snap : ${StockSnapshot.status(context)}")
                appendLine("drift check: ${PostApplyDriftGuard.lastResult(context) ?: "not run"}")
                appendLine(
                    "boot apply : boot ${PostApplyDriftGuard.bootAppliedCount(context)} applied" +
                        " · current boot ${StockSnapshot.bootCount(context)}" +
                        (if (ProfileController.isEngineOff(context)) "" else
                            if (PostApplyDriftGuard.bootAppliedCount(context) != StockSnapshot.bootCount(context))
                                "  <- MISSING (MIUI autostart?)" else " ok")
                )
                val locks = sh(
                    "cat /sys/module/msm_performance/parameters/cpu_min_freq 2>/dev/null; echo; " +
                        "cat /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null"
                ).trim().lines()
                appendLine("perf locks : min=${locks.getOrNull(0)?.trim().orEmpty()} max=${locks.getOrNull(1)?.trim().orEmpty()}")
                val doc = ProfileStore.doc(context)
                val platform = com.omarea.util.PlatformUtils().getCPUName()
                appendLine(
                    "tuning     : user copy=${if (TuningRepository.hasUserCopy(platform)) "yes" else "no"}" +
                        " · modified profiles=${ProfileKey.ALL.count { doc?.isModified(it) == true }}"
                )
                val tuning = TuningRepository.read(context, platform)
                appendLine(
                    "mitigations: " + (tuning?.let { ProfilePlanner.mitigations(it) } ?: emptyList())
                        .ifEmpty { listOf("none") }.joinToString()
                )
            },
            isCode = false
        )

        sections += Section("Recent shell executions (ShellLog)", ShellLog.dump(), isCode = true)
        val logcat = sh("logcat -d -t 400 *:E")
        val logcatTail = logcat.lines().takeLast(200).joinToString("\n")
        sections += Section("Logcat (errors, last 200 lines)", logcatTail, isCode = true)

        return sections
    }

    fun buildMarkdown(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("# Scene diagnostics report")
        sb.appendLine()
        sb.appendLine("- Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        val (vName, vCode) = appVersion(context)
        sb.appendLine("- Package: ${context.packageName} v$vName ($vCode)")
        sb.appendLine()
        for (s in collectSections(context)) {
            sb.appendLine("## ${s.title}")
            sb.appendLine()
            if (s.body.isBlank()) {
                sb.appendLine("_empty_")
            } else if (s.isCode) {
                sb.appendLine("```")
                sb.appendLine(s.body.trim())
                sb.appendLine("```")
            } else {
                sb.appendLine(s.body.trim())
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun buildJson(context: Context): String {
        val root = JSONObject()
        val (vName, vCode) = appVersion(context)
        root.put("app", JSONObject().put("package", context.packageName).put("versionName", vName).put("versionCode", vCode))
        root.put("generatedAt", System.currentTimeMillis())
        val arr = JSONArray()
        for (s in collectSections(context)) {
            val o = JSONObject()
            o.put("section", s.title)
            o.put("body", s.body.trim())
            arr.put(o)
        }
        root.put("sections", arr)
        return root.toString(2)
    }
}
