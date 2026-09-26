package com.omarea.scene_mode.power

import com.omarea.common.shell.KeepShellPublic
import com.omarea.utils.GovernorCapabilities
import java.util.Locale

/**
 * Read-only power / idle snapshot, complementary to [BatteryHealth]: instead of
 * the battery itself it reports why the device does or does not reach the
 * deepest idle states.
 *
 * Sources (all audited from the stock MIUI 14 surya stack):
 *  - `/sys/power/suspend_stats`: how often suspend succeeds and which device
 *    failed last - the first thing to look at for idle drain;
 *  - `/sys/kernel/debug/wakeup_sources`: the biggest wake lock holders;
 *  - `cpu_boost` (`input_boost_freq`, `input_boost_ms`, `sched_boost_on_input`)
 *    and `msm_performance` locks, i.e. what is keeping the CPUs busy;
 *  - the kernel knobs the options layer tunes (`coloc_fmin`, the workqueue
 *    mode, the writeback centisecs the surya config leaves at 5 s);
 *  - zram/swap usage and the `msm_irqbalance` service state.
 *
 * Every read is guarded: a missing node prints `(absent)`.
 */
object PowerReport {
    private const val WAKEUP_SOURCES = "/sys/kernel/debug/wakeup_sources"

    private val nodes = listOf(
        "input_boost_freq" to "/sys/module/cpu_boost/parameters/input_boost_freq",
        "input_boost_ms" to "/sys/module/cpu_boost/parameters/input_boost_ms",
        "sched_boost_on_input" to "/sys/module/cpu_boost/parameters/sched_boost_on_input",
        "msm_cpu_min_freq" to "/sys/module/msm_performance/parameters/cpu_min_freq",
        "msm_cpu_max_freq" to "/sys/module/msm_performance/parameters/cpu_max_freq",
        "coloc_fmin_khz" to "/proc/sys/kernel/sched_little_cluster_coloc_fmin_khz",
        "cpu_governor.policy0" to "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
        "cpu_governor.policy6" to "/sys/devices/system/cpu/cpufreq/policy6/scaling_governor",
        "gpu_governor" to "/sys/class/kgsl/kgsl-3d0/devfreq/governor",
        "wq_power_efficient" to "/sys/module/workqueue/parameters/power_efficient",
        "dirty_writeback_centisecs" to "/proc/sys/vm/dirty_writeback_centisecs",
        "dirty_expire_centisecs" to "/proc/sys/vm/dirty_expire_centisecs",
        "swappiness" to "/proc/sys/vm/swappiness"
    )

    fun build(): String {
        val sb = StringBuilder("Power & idle\n")
        try {
            for ((key, value) in readValues()) {
                sb.append("  ").append(key).append(" = ").append(value).append('\n')
            }
            // Availability checker results: 1 = the selected governor/scheduler
            // is not advertised by the kernel ("cannot apply ... unavailable").
            sb.append("  blocked.cpu_governor = ")
                .append(shell("getprop vtools.scene.gov.blocked 2> /dev/null").trim().ifEmpty { "0" })
                .append('\n')
            sb.append("  blocked.gpu_governor = ")
                .append(shell("getprop vtools.scene.gpu.gov.blocked 2> /dev/null").trim().ifEmpty { "0" })
                .append('\n')
            sb.append("  blocked.io_scheduler = ")
                .append(shell("getprop vtools.scene.io.blocked 2> /dev/null").trim().ifEmpty { "0" })
                .append('\n')

            // What this kernel actually offers, and the governor each scenario
            // resolves to through the preference chains.
            val caps = GovernorCapabilities.read()
            val resolved = GovernorCapabilities.resolved(caps)
            sb.append("  available.cpu0 = ").append(caps.cpu0.joinToString(" ").ifEmpty { "(unreadable)" }).append('\n')
            sb.append("  available.cpu6 = ").append(caps.cpu6.joinToString(" ").ifEmpty { "(unreadable)" }).append('\n')
            sb.append("  available.gpu = ").append(caps.gpu.joinToString(" ").ifEmpty { "(unreadable)" }).append('\n')
            sb.append("  available.io = ").append(caps.io.joinToString(" ").ifEmpty { "(unreadable)" }).append('\n')
            sb.append("  resolved = powersave:").append(resolved["powersave"].orEmpty().ifEmpty { "-" })
                .append(" balance:").append(resolved["balance"].orEmpty().ifEmpty { "-" })
                .append(" performance:").append(resolved["performance"].orEmpty().ifEmpty { "-" })
                .append(" gpu:").append(resolved["gpu"].orEmpty().ifEmpty { "-" })
                .append('\n')

            sb.append('\n').append("Suspend statistics\n")
            for (key in listOf(
                "success", "fail", "failed_freeze", "failed_prepare", "failed_suspend",
                "failed_resume", "last_failed_dev", "last_failed_errno", "last_failed_step"
            )) {
                sb.append("  ").append(key).append(" = ")
                    .append(valueOf("/sys/power/suspend_stats/$key")).append('\n')
            }

            sb.append('\n').append("Top wakeup sources (by total time)\n")
            val sources = wakeupSources()
            if (sources.isEmpty()) {
                sb.append("  (unreadable)\n")
            } else {
                for (source in sources) {
                    sb.append("  ").append(source).append('\n')
                }
            }

            sb.append('\n').append("Memory / swap\n")
            sb.append("  swappiness = ").append(valueOf("/proc/sys/vm/swappiness")).append('\n')
            sb.append("  ").append(swapLine()).append('\n')

            sb.append('\n').append("IRQ balancing\n")
            sb.append("  service = ")
                .append(shell("getprop init.svc.vendor.msm_irqbalance 2> /dev/null").trim().ifEmpty { "(unknown)" })
                .append('\n')
            sb.append("  conf = ")
                .append(
                    shell("grep -E '^PRIO=|^IGNORED_IRQ=' /vendor/etc/msm_irqbalance.conf 2> /dev/null")
                        .trim().replace('\n', ' ').ifEmpty { "(absent)" }
                )
                .append('\n')
        } catch (ex: Exception) {
            sb.append("  (report failed: ").append(ex.javaClass.simpleName).append(")\n")
        }
        return sb.toString()
    }

    /** `key=value` lines from the guarded nodes, one shell round trip. */
    private fun readValues(): List<Pair<String, String>> {
        val cmd = StringBuilder()
        for ((key, path) in nodes) {
            cmd.append("echo ").append(key).append("=\$(cat ").append(path).append(" 2> /dev/null)\n")
        }
        val result = shell(cmd.toString()).lines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index > 0) line.substring(0, index).trim() to line.substring(index + 1).trim() else null
        }
        return result.map { (key, value) -> key to value.ifEmpty { "(absent)" } }
    }

    private fun valueOf(path: String): String =
        shell("cat $path 2> /dev/null").trim().ifEmpty { "(absent)" }

    private fun swapLine(): String {
        val swaps = shell("grep -E 'zram|swap' /proc/swaps 2> /dev/null").trim()
        if (swaps.isEmpty()) {
            return "swap = (none)"
        }
        val mm = shell("cat /sys/block/zram0/mm_stat 2> /dev/null").trim()
            .split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val useMb = if (mm.size >= 3) {
            String.format(Locale.US, " orig=%.1f MB comp=%.1f MB mem=%.1f MB",
                mm[0] / 1048576.0, mm[1] / 1048576.0, mm[2] / 1048576.0)
        } else {
            ""
        }
        return "swaps = " + swaps.replace('\n', ' ') + useMb
    }

    /**
     * The busiest wake lock holders. Format:
     * `name active_count event_count wakeup_count expire_count active_since
     * total_time max_time last_change prevent_suspend_time`.
     */
    private fun wakeupSources(limit: Int = 6): List<String> {
        val output = shell("cat $WAKEUP_SOURCES 2> /dev/null")
        if (output.isBlank()) {
            return emptyList()
        }
        data class Source(val name: String, val activeSince: Long, val totalMs: Long, val wakeups: Long)

        val rows = ArrayList<Source>()
        for (line in output.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 7 || parts[0] == "name" || parts[0].startsWith("name")) {
                continue
            }
            val activeSince = parts[5].toLongOrNull() ?: continue
            val totalMs = parts[6].toLongOrNull() ?: continue
            val wakeups = parts.getOrNull(3)?.toLongOrNull() ?: 0
            rows.add(Source(parts[0], activeSince, totalMs, wakeups))
        }
        return rows.sortedByDescending { it.totalMs }.take(limit).map { source ->
            String.format(
                Locale.US,
                "%s: total=%.1fs active_since=%.1fs wakeups=%d",
                source.name,
                source.totalMs / 1000.0,
                source.activeSince / 1000.0,
                source.wakeups
            )
        }
    }

    private fun shell(command: String): String = try {
        KeepShellPublic.doCmdSync(command)
    } catch (ex: Exception) {
        ""
    }
}
