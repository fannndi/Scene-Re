package com.omarea.vtools.privilege

import android.util.Log
import com.omarea.common.shell.CapabilityResult
import com.omarea.common.shell.CapabilitySnapshot
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellCapability
import com.omarea.common.shell.ShellCapabilityProbe
import com.omarea.common.shell.ShellCapabilityRegistry
import com.omarea.common.shell.ShellModeProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Probes what the active privilege tier can actually do, instead of assuming that a feature works
 * because the app "should" have root.
 *
 * Every check is read-only: the probe never writes to sysfs or changes system state. Results are
 * cached in a [CapabilitySnapshot] and refreshed when the privilege tier changes or on demand.
 *
 * Why this exists: on a Shizuku (uid 2000) session, monitoring features such as CPU frequency,
 * thermal zones and memory info are perfectly readable, but `/sys/class/kgsl` (GPU), battery power
 * supply nodes and every sysfs write are denied. Hardcoding `hasRootAccess` gates therefore hides
 * features that would work. Feature code should ask this probe instead.
 */
object ShellCapabilityProbeImpl : ShellCapabilityProbe {
    private const val TAG = "SceneCapability"

    /** Sentinel emitted by the shell script when a read succeeded. */
    private const val OK = "CAP_OK"

    /** Sentinel emitted when the shell reported a permission problem. */
    private const val DENIED = "CAP_DENIED"

    /** How many times to retry a probe that came back without any usable output. */
    private const val PROBE_RETRIES = 6
    private const val PROBE_RETRY_DELAY_MILLIS = 500L

    @Volatile
    private var cached: CapabilitySnapshot = CapabilitySnapshot.EMPTY

    private var inFlight: CountDownLatch? = null

    /** Called once from [PrivilegeManager.init]; safe to call again after a tier change. */
    fun register() {
        ShellCapabilityRegistry.probe = this
    }

    override fun snapshot(): CapabilitySnapshot = cached

    /**
     * Single probe script. Each line prints `name=CAP_OK`, `name=CAP_DENIED` or `name=CAP_NONE`.
     *
     * The three answers mean genuinely different things, and the difference is user-facing:
     * `CAP_DENIED` means "root would unlock this", `CAP_NONE` means "this ROM has no such control".
     *
     * Getting that distinction right took measurement, because `[ -e ]` cannot be trusted on the
     * paths this app cares about. On surya, `ls /sys/class/kgsl/` is refused at shell uid, the
     * kernel refuses the `stat` that `[ -e ]` performs, and `[ -e ]` therefore reports false for a
     * directory that plainly exists. Two rules follow:
     *
     * 1. **Read/write first, existence second.** A successful `[ -r ]` / `[ -w ]` answers the
     *    question outright. Only when it fails do we ask the harder question.
     *
     * 2. **Existence is inherited from an ancestor.** [`emitExistence`] walks up the path until it
     *    finds a directory whose contents can be listed, then looks for the next segment there.
     *    `/sys/class/kgsl/kgsl-3d0` is therefore resolved through the listable `/sys/class/kgsl`,
     *    which is how a sysfs class directory is supposed to be inspected anyway.
     *
     * Write capabilities are probed with `[ -w ]` rather than by opening the file: a sysfs write
     * has side effects, and a capability probe must never change system state.
     */
    private fun buildProbeScript(): String {
        val probe = { name: String, path: String, mode: Char ->
            val test = if (mode == 'w') "[ -w '$path' ]" else "[ -r '$path' ]"
            "if $test 2>/dev/null; then echo '$name=$OK'; " +
                    "elif ${emitExistence(path)}; then echo '$name=$DENIED'; " +
                    "else echo '$name=CAP_NONE'; fi"
        }
        val probeRead = { name: String, path: String -> probe(name, path, 'r') }
        val probeWrite = { name: String, path: String -> probe(name, path, 'w') }
        return buildString {
            appendLine("echo '|CAP|'")
            // Record the uid the shell actually ran as. This is the ground truth for the tier: a
            // routing bug that silently falls back to the app's own shell would otherwise be
            // indistinguishable from a genuine permission denial. `id -u` is captured without
            // command substitution, which the persistent shell mangles.
            appendLine("id -u >/dev/null 2>&1 && id -u | sed 's/^/probe_uid=/' || echo 'probe_uid=unknown'")
            // Framework facilities: these fail loudly when the tier cannot talk to the framework.
            appendLine("if dumpsys -l >/dev/null 2>&1; then echo 'dumpsys=$OK'; else echo 'dumpsys=$DENIED'; fi")
            appendLine("if cmd package list packages >/dev/null 2>&1; then echo 'framework_control=$OK'; else echo 'framework_control=$DENIED'; fi")
            appendLine("if pm list packages >/dev/null 2>&1; then echo 'app_control=$OK'; else echo 'app_control=$DENIED'; fi")
            appendLine("if settings get global window_animation_scale >/dev/null 2>&1; then echo 'settings_write=$OK'; else echo 'settings_write=$DENIED'; fi")
            appendLine("if dumpsys battery >/dev/null 2>&1; then echo 'battery_framework_read=$OK'; else echo 'battery_framework_read=$DENIED'; fi")
            // CPU: cpu0 answers "can this tier touch cpufreq" for the little cluster.
            appendLine(probeRead("cpu_sysfs_read", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
            appendLine(probeWrite("cpu_sysfs_write", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"))
            appendLine(probeRead("gpu_sysfs_read", "/sys/class/kgsl/kgsl-3d0/gpuclk"))
            appendLine(probeWrite("gpu_sysfs_write", "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"))
            appendLine(probeRead("thermal_sysfs_read", "/sys/class/thermal/thermal_zone0/temp"))
            appendLine(probeWrite("thermal_sysfs_write", "/sys/class/thermal/thermal_zone0/mode"))
            appendLine(probeRead("proc_read", "/proc/stat"))
            appendLine(probeRead("battery_sysfs_read", "/sys/class/power_supply/battery/current_now"))
            appendLine(probeWrite("kernel_module_rw", "/sys/module/cpu_boost/parameters/input_boost_enabled"))
            appendLine(probeWrite("storage_tuning", "/sys/block/sda/queue/read_ahead_kb"))
            appendLine("echo '|END|'")
        }
    }

    /**
     * Emits a shell condition that is true when [path] exists, without relying on `[ -e ]`.
     *
     * Walks up from the path looking for the deepest ancestor whose directory contents can be
     * listed, then tests for the remaining segment inside it. Falls back to `[ -e ]` if even `/`
     * is unlistable, which should never happen but keeps the emitted script well-formed.
     */
    private fun emitExistence(path: String): String {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return "[ -e '$path' ]"
        }
        val conditions = ArrayList<String>()
        for (cut in segments.indices) {
            val parent = "/" + segments.subList(0, cut).joinToString("/")
            val child = segments[cut]
            conditions.add("ls '$parent' 2>/dev/null | grep -qxF '$child'")
        }
        conditions.add("[ -e '$path' ]")
        return conditions.joinToString(" || ") { "( $it )" }
    }

    override fun probe(): CapabilitySnapshot {
        // Coalesce concurrent probes so a tier change does not trigger several shell round trips.
        val existing = inFlight
        if (existing != null) {
            try {
                existing.await(15, TimeUnit.SECONDS)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return cached
        }
        val latch = CountDownLatch(1)
        inFlight = latch
        try {
            val tier = ShellModeProvider.mode
            val script = buildProbeScript()
            var output = KeepShellPublic.doCmdSync(script)
            // A blank answer means the shell backend was still coming up (usually the Shizuku user
            // service connecting), not that every capability is unavailable. Retry briefly so a
            // transient empty read does not poison the snapshot and hide working features.
            var attempt = 0
            while (!output.contains("|CAP|") && attempt < PROBE_RETRIES) {
                attempt++
                try {
                    Thread.sleep(PROBE_RETRY_DELAY_MILLIS)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                output = KeepShellPublic.doCmdSync(script)
            }
            // The shell backend decides the real answer, so log what actually ran. Without this a
            // tier/routing mismatch looks identical to a genuine permission denial.
            Log.i(TAG, "probe start tier=$tier attempt=$attempt output=${describeOutput(output)}")
            val parsed = parseProbeOutput(output)
            if (parsed.isEmpty()) {
                // Nothing parsed: keep the previous snapshot rather than publishing "everything
                // unavailable", which would disable features the session can actually use.
                Log.w(TAG, "probe produced no parsable results; keeping previous snapshot")
                return cached
            }
            val results = LinkedHashMap<ShellCapability, CapabilityResult>()

            for (capability in ShellCapability.values()) {
                val raw = parsed[capability.id]
                val available = interpret(capability, raw, tier)
                val detail = when (raw) {
                    OK -> "ok"
                    DENIED -> "denied"
                    "CAP_NONE" -> "node missing on this ROM"
                    null -> "not reported"
                    else -> raw
                }
                results[capability] = CapabilityResult(capability, available, detail)
            }

            val snapshot = CapabilitySnapshot(tier.name, results, System.currentTimeMillis())
            cached = snapshot
            Log.i(TAG, summarize(snapshot))
            return snapshot
        } catch (ex: Throwable) {
            Log.e(TAG, "Capability probe failed: " + ex.message)
            return cached
        } finally {
            inFlight = null
            latch.countDown()
        }
    }

    /**
     * Turns the raw probe answers into a decision.
     *
     * The `[ -w ]` test is deliberately combined with tier knowledge for write capabilities: sysfs
     * usually reports mode 0644 owned by root, so `[ -w ]` is accurate for a real uid, but a
     * root-backed shell would still succeed. Root mode therefore promotes write capabilities whose
     * node exists but reported as not writable.
     *
     * `CAP_NONE` is never promoted: when the ROM has no such node, root cannot help either. This
     * matters on surya, where `/sys/class/kgsl/kgsl-3d0` exists but cannot even be stat'ed by a
     * non-root uid, and where `/sys/module/cpu_boost` genuinely does not exist.
     */
    private fun interpret(
        capability: ShellCapability,
        raw: String?,
        tier: com.omarea.common.shell.ShellMode
    ): Boolean {
        if (raw == OK) {
            // A writable sysfs node still belongs to root on a Shizuku session, but the framework
            // facilities (dumpsys/cmd/pm/settings) are genuinely usable at shell uid.
            if (isWriteCapability(capability) && tier != com.omarea.common.shell.ShellMode.ROOT) {
                return false
            }
            return true
        }
        if (raw == DENIED) {
            // Root can write a node the shell was refused, but cannot conjure one that is absent.
            return tier == com.omarea.common.shell.ShellMode.ROOT && isWriteCapability(capability)
        }
        // CAP_NONE, an unparsed line, or no answer at all.
        return false
    }

    /**
     * True for capabilities that require writing to a kernel node.
     *
     * `SETTINGS_WRITE` and `BATTERY_FRAMEWORK_READ` are deliberately absent: they are framework
     * facilities reached through `settings`/`dumpsys`, which the shell uid can use, so they must
     * not be demoted to root-only.
     */
    private fun isWriteCapability(capability: ShellCapability): Boolean {
        return capability == ShellCapability.CPU_SYSFS_WRITE ||
                capability == ShellCapability.GPU_SYSFS_WRITE ||
                capability == ShellCapability.THERMAL_SYSFS_WRITE ||
                capability == ShellCapability.KERNEL_MODULE_RW ||
                capability == ShellCapability.STORAGE_TUNING
    }

    /**
     * Parses the `name=value` lines produced by the probe script.
     *
     * Only lines between the `|CAP|` and `|END|` markers are considered. The settings read prints a
     * bare value before the markers, and it is deliberately ignored: the availability of that
     * facility is decided by the explicit `settings get ... >/dev/null` test, which distinguishes
     * "command succeeded" from "command printed something".
     */
    private fun parseProbeOutput(output: String): Map<String, String> {
        val map = HashMap<String, String>()
        var inside = false
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed == "|CAP|") {
                inside = true
                continue
            }
            if (trimmed == "|END|") {
                break
            }
            if (!inside || trimmed.isEmpty()) {
                continue
            }
            val separator = trimmed.indexOf('=')
            if (separator <= 0 || separator == trimmed.length - 1) {
                continue
            }
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    private fun summarize(snapshot: CapabilitySnapshot): String {
        val available = snapshot.results.filterValues { it.available }.keys.map { it.id }
        val denied = snapshot.results.filterValues { !it.available }.keys.map { it.id }
        return "tier=${snapshot.tier} available=${available.joinToString(",")} unavailable=${denied.joinToString(",")}"
    }

    /** Short, log-safe description of probe output, for diagnosing a routing mismatch. */
    private fun describeOutput(output: String): String {
        if (output.isBlank()) {
            return "<empty>"
        }
        val lines = output.lines().count { it.isNotBlank() }
        val uid = Regex("probe_uid=(\\S+)").find(output)?.groupValues?.get(1) ?: "?"
        val hasMarkers = output.contains("|CAP|") && output.contains("|END|")
        return "lines=$lines uid=$uid markers=$hasMarkers"
    }
}
