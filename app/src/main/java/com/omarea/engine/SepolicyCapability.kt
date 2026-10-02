package com.omarea.engine

import android.content.Context
import android.os.SystemClock
import com.omarea.common.shell.ShellLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SELinux direct-write capability probe + cache (honest reporting).
 *
 * For each node family the app may write directly, the probe rewrites the
 * node's CURRENT value through [DirectWrite] and reports OK / DENIED — a
 * no-op for the device (same value) that exercises the full MAC+DAC path.
 * Results are cached per boot:
 *  - in memory for [ProfileApplier] (it skips direct attempts for families
 *    known to be denied → no avc noise, no wasted syscalls),
 *  - in a pullable report file for agents and the Diagnostics screen.
 *
 * Responsibility: probing, caching and reporting capability.
 * Non-goals: policy application ([SepolicyOptimizer]) and profile planning.
 */
object SepolicyCapability {

    data class Family(val id: String, val label: String, val probeNode: String)

    data class Result(val id: String, val label: String, val status: String) {
        val ok: Boolean get() = status == "OK" || status.startsWith("OK(")
    }

    val families = listOf(
        Family("cpu", "CPU freq/governor", "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"),
        Family("kgsl", "GPU pwrlevel", "/sys/class/kgsl/kgsl-3d0/min_pwrlevel"),
        Family("msm_perf", "perf freq locks", "/sys/module/msm_performance/parameters/cpu_max_freq"),
        Family("cpu_boost", "input boost", "/sys/module/cpu_boost/parameters/input_boost_ms"),
        Family("thermal", "thermal mailbox", "/sys/class/thermal/thermal_message/temp_state"),
        Family("scsi_host", "UFS clockscale", "/sys/devices/platform/soc/1d84000.ufshc/clkscale_enable"),
        Family("sysfs_generic", "sysfs generic (UFS devfreq)", "/sys/class/devfreq/1d84000.ufshc/min_freq"),
        Family("lmk", "LMK minfree", "/sys/module/lowmemorykiller/parameters/minfree"),
        Family("sched_vm", "sched/vm sysctls (root-only DAC)", "/proc/sys/kernel/sched_upmigrate")
    )

    /**
     * Path → family mapping, order = most specific first.
     * Pure: unit-tested.
     *
     * Some UFS nodes share one directory but carry different SELinux types
     * (`clkscale_enable` = vendor_sysfs_scsi_host; `hibern8_on_idle_enable`
     * and the devfreq min/max are generic `sysfs`), so exact-path overrides
     * come before prefix matching.
     */
    private val EXACT = mapOf(
        "/sys/devices/platform/soc/1d84000.ufshc/clkscale_enable" to "scsi_host",
        "/sys/devices/platform/soc/1d84000.ufshc/clkgate_enable" to "sysfs_generic",
        "/sys/devices/platform/soc/1d84000.ufshc/hibern8_on_idle_enable" to "sysfs_generic",
        "/sys/class/devfreq/1d84000.ufshc/min_freq" to "sysfs_generic",
        "/sys/class/devfreq/1d84000.ufshc/max_freq" to "sysfs_generic"
    )

    private val PREFIXES = listOf(
        "cpu" to "/sys/devices/system/cpu",
        "kgsl" to "/sys/class/kgsl",
        "msm_perf" to "/sys/module/msm_performance",
        "cpu_boost" to "/sys/module/cpu_boost",
        "thermal" to "/sys/class/thermal/thermal_message",
        "lmk" to "/sys/module/lowmemorykiller/parameters",
        "sched_vm" to "/proc/sys",
        "sysfs_generic" to "/sys/"
    )

    /** Family id for a node path; null when unknown. Pure. */
    internal fun familyFor(node: String): String? =
        EXACT[node] ?: PREFIXES.firstOrNull { node.startsWith(it.second) }?.first

    /** In-memory cache consulted by [ProfileApplier] (family id → allowed). */
    @Volatile
    private var cache: Map<String, Boolean> = emptyMap()

    /** `null` = not probed yet (caller may attempt the write). */
    fun canWrite(node: String): Boolean? = familyFor(node)?.let { cache[it] }

    /** Learn from a real apply: remember the family's outcome. */
    fun mark(node: String, ok: Boolean) {
        val id = familyFor(node) ?: return
        if (cache[id] == ok) return
        cache = cache + (id to ok)
    }

    /** Replaces the whole cache (used by the boot probe). */
    fun seed(results: Map<String, Boolean>) {
        cache = results
    }

    // ------------------------------------------------------------------ probe
    fun probeAll(context: Context, persist: Boolean = true): List<Result> {
        // Monitor mode: the probe performs direct writes — parameter writes.
        // Skip entirely so Diagnostics never reports misleading "locked"
        // families that are merely invisible without root.
        if (!com.omarea.util.CheckRootStatus.isAvailable()) return emptyList()
        val results = ArrayList<Result>()
        val seedMap = HashMap<String, Boolean>()
        for (family in families) {
            val file = File(family.probeNode)
            val status = when {
                !file.exists() -> "missing"
                !file.canRead() -> "unreadable"
                else -> {
                    val failure = DirectWrite.probe(family.probeNode)
                    if (failure.isEmpty()) {
                        "OK(${DirectWrite.lastMode})"
                    } else {
                        failure
                    }
                }
            }
            val ok = status == "OK" || status.startsWith("OK(")
            seedMap[family.id] = ok
            results += Result(family.id, family.label, status)
        }
        seed(seedMap)
        if (persist) {
            writeReport(context, results)
        }
        ShellLog.log("SepolicyCapability", results.joinToString(", ") { "${it.id}=${it.status}" })
        return results
    }

    /** Latest persisted report text (Diagnostics/agents), or an empty list. */
    fun lastResults(context: Context): List<Result> {
        val file = reportFile(context)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size == 4 && parts[0] == "cap") {
                    Result(parts[1], parts[2], parts[3])
                } else null
            }
    }

    private fun reportFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "debug/sepolicy-caps.txt")

    private fun writeReport(context: Context, results: List<Result>) {
        try {
            val file = reportFile(context)
            file.parentFile?.mkdirs()
            val writes = SepolicyOptimizer.directWritesEnabled(context)
            val report = buildString {
                appendLine("# Scene SELinux direct-write capability (probe = rewrite current value)")
                appendLine("# boot=" + System.currentTimeMillis() + " at " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("# write rules enabled: $writes")
                appendLine("# note: new module rules only take effect after a reboot")
                for (r in results) {
                    appendLine("cap|${r.id}|${r.label}|${r.status}")
                }
            }
            file.writeText(report)
        } catch (ex: Exception) {
            ShellLog.log("SepolicyCapability.report", ex.message ?: "write failed", error = true)
        }
    }

    /** Boot marker helper: rules applied this boot differ from the module file. */
    fun bootMarker(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
}
