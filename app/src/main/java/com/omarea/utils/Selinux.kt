package com.omarea.utils

import com.omarea.common.shared.RootBackend
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape

/**
 * SELinux state and the denials that can silently break root writes.
 *
 * Scene never ships a Zygisk module (hard scope) and never hides SELinux
 * activity: instead it reports what the policy is doing and, when the user
 * explicitly opts in, applies the missing `allow` rules through the
 * policy-patching tool the root backend already provides (`magiskpolicy` on
 * Magisk, `ksud` on KernelSU, `supolicy` on older chains).
 *
 * The denials are read from the audit log first, then the kernel ring buffer,
 * then the auditd events buffer - all root-readable on the surya MIUI 14 stack.
 * Rules are derived from the denial itself (`scontext`/`tcontext`/`tclass`/
 * `perms`), so no type name is ever guessed.
 */
object Selinux {
    private const val AUDIT_LOG = "/data/misc/audit/audit.log"
    private const val LAST_RUN_PROP = "vtools.scene.selinux.last"
    private const val MIN_INTERVAL_SEC = 900L

    @Volatile
    private var lastRunAt = 0L

    /** Root domains whose denials Scene may repair (never app domains). */
    private val rootDomains = setOf("magisk", "su", "ksu", "kernel")

    data class Denial(
        val source: String,
        val target: String,
        val tclass: String,
        val perms: String,
        val path: String,
        val raw: String
    ) {
        /** `allow magisk sysfs_thermal file { write }` */
        fun rule(): String =
            "allow $source $target $tclass { " + perms.split(",").joinToString(" ") { it.trim() } + " }"
    }

    fun enforcing(): Boolean = try {
        shell("getenforce 2> /dev/null").trim().equals("Enforcing", ignoreCase = true) ||
            valueOf("/sys/fs/selinux/enforce") == "1"
    } catch (ex: Exception) {
        false
    }

    /** Current SELinux context of the root shell, e.g. `u:r:magisk:s0`. */
    fun domain(): String = try {
        shell("cat /proc/self/attr/current 2> /dev/null").trim()
    } catch (ex: Exception) {
        ""
    }

    /** Policy-patching tools the running root backend provides. */
    fun tools(): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        // magiskpolicy: Magisk. apd: APatch and FolkPatch-Re (it ships a full
        // magiskpolicy clone under `apd sepolicy`). ksud/supolicy: the rest.
        for (tool in listOf("magiskpolicy", "apd", "ksud", "supolicy")) {
            val path = try {
                shell("command -v $tool 2> /dev/null").trim()
            } catch (ex: Exception) {
                ""
            }
            if (path.isNotEmpty()) {
                found[tool] = path
            }
        }
        return found
    }

    /** Recent `avc: denied` lines, newest last, de-duplicated. */
    fun denials(limit: Int = 60): List<Denial> {
        val text = readDenialText()
        if (text.isBlank()) {
            return emptyList()
        }
        val rows = ArrayList<Denial>()
        val seen = HashSet<String>()
        for (line in text.lineSequence()) {
            if (!line.contains("avc: denied") && !line.contains("avc:  denied")) {
                continue
            }
            val match = PATTERN.find(line) ?: continue
            val denial = Denial(
                source = match.groupValues[3],
                target = match.groupValues[4],
                tclass = match.groupValues[5],
                perms = match.groupValues[1],
                path = match.groupValues[2],
                raw = line.trim()
            )
            if (denial.source !in rootDomains) {
                continue
            }
            if (seen.add(denial.rule())) {
                rows.add(denial)
            }
        }
        return rows.takeLast(limit)
    }

    private fun readDenialText(): String {
        for (command in listOf(
            "tail -n 400 $AUDIT_LOG 2> /dev/null",
            "dmesg 2> /dev/null | grep -a 'avc: denied' | tail -n 200",
            "logcat -b events -d -s auditd 2> /dev/null | tail -n 200"
        )) {
            val out = try {
                shell(command)
            } catch (ex: Exception) {
                ""
            }
            if (out.contains("avc: denied") || out.contains("avc:  denied")) {
                return out
            }
        }
        return ""
    }

    /** The `allow` rules that would clear the current root-domain denials. */
    fun rules(denials: List<Denial> = denials()): List<String> =
        denials.map { it.rule() }.distinct()

    /**
     * Apply [rules] through the available tool. Returns a human status line;
     * never throws. Magisk's `magiskpolicy --live` and FolkPatch's
     * `apd sepolicy --live` take one rule per argument, KernelSU's
     * `ksud sepolicy patch` accepts them the same way.
     */
    fun applyRules(rules: List<String>): String {
        if (rules.isEmpty()) {
            return "no rules needed"
        }
        val tool = tools().entries.firstOrNull() ?: return "no policy tool available"
        val quoted = rules.joinToString(" ") { ShellEscape.quote(it) }
        val command = when (tool.key) {
            "magiskpolicy" -> "${tool.value} --live $quoted"
            "apd" -> "${tool.value} sepolicy --live $quoted"
            "ksud" -> "${tool.value} sepolicy patch $quoted"
            else -> "${tool.value} $quoted"
        }
        return try {
            val output = shell(command).trim()
            val failed = output.lines().count { it.contains("denied", true) || it.contains("failed", true) }
            "${tool.key}: ${rules.size} rule(s), ${if (failed > 0) "$failed reported an error" else "applied"}"
        } catch (ex: Exception) {
            "${tool.key}: " + ex.javaClass.simpleName
        }
    }

    /**
     * Run the opt-in repair at most every [MIN_INTERVAL_SEC] seconds. Called
     * from the options layer when the user enabled the experimental switch;
     * a plain report is always available in Diagnostics.
     */
    fun repairIfEnabled(enabled: Boolean) {
        if (!enabled) {
            return
        }
        try {
            val now = System.currentTimeMillis() / 1000
            if (now - lastRunAt < MIN_INTERVAL_SEC) {
                return
            }
            val last = shell("getprop $LAST_RUN_PROP 2> /dev/null").trim().toLongOrNull() ?: 0L
            if (now - last < MIN_INTERVAL_SEC) {
                lastRunAt = now
                return
            }
            lastRunAt = now
            shell("setprop $LAST_RUN_PROP $now")
            val rules = rules()
            if (rules.isEmpty()) {
                return
            }
            SceneLog.i("Selinux", applyRules(rules))
        } catch (ex: Exception) {
            SceneLog.e("Selinux", "repair failed", ex)
        }
    }

    fun report(): String {
        val sb = StringBuilder("SELinux\n")
        sb.append("  mode = ").append(if (enforcing()) "enforcing" else "permissive/unknown")
            .append(" (enforce=").append(valueOf("/sys/fs/selinux/enforce")).append(")").append('\n')
        sb.append("  shell domain = ").append(domain().ifEmpty { "(unreadable)" }).append('\n')
        sb.append("  root manager = ").append(RootBackend.manager()).append('\n')
        sb.append("  root backend = ").append(RootBackend.backend().name.lowercase())
            .append(" - ").append(RootBackend.backendDescription()).append('\n')
        for (line in managerHealth()) {
            sb.append(line).append('\n')
        }
        val tools = tools()
        sb.append("  policy tools = ")
            .append(tools.entries.joinToString(", ") { "${it.key} (${it.value})" }.ifEmpty { "(none)" })
            .append('\n')
        val denials = denials()
        sb.append("  root-domain denials = ").append(denials.size).append('\n')
        for (denial in denials.takeLast(20)) {
            sb.append("    ").append(denial.rule())
                .append(if (denial.path.isNotEmpty()) "  # ${denial.path}" else "").append('\n')
        }
        val rules = rules(denials)
        sb.append("  repair = ")
            .append(
                when {
                    rules.isEmpty() -> "nothing to repair"
                    tools.isEmpty() -> "no policy tool available"
                    else -> "${rules.size} rule(s) ready, toggle the experimental repair to apply"
                }
            )
            .append('\n')
        sb.append('\n').append(selfTestReport())
        return sb.toString()
    }

    // +---------------------------------------------------------------+
    // | Capability self-test: is root enough for Scene's writes?       |
    // +---------------------------------------------------------------+

    /** One Scene-critical operation and what the policy says about it. */
    data class Capability(
        val name: String,
        val path: String,
        /** ok | denied | missing | skipped */
        val state: String,
        val detail: String
    ) {
        val ok: Boolean get() = state == "ok"
    }

    private enum class Kind { TEXT, SCHEDULER, DIR, BLOCK, PROP }

    private data class Target(val name: String, val path: String, val kind: Kind)

    private val targets = listOf(
        Target("thermal temp_state", "/sys/class/thermal/thermal_message/temp_state", Kind.TEXT),
        Target("thermal sconfig", "/sys/class/thermal/thermal_message/sconfig", Kind.TEXT),
        Target("thermal board_sensor", "/sys/class/thermal/thermal_message/board_sensor_temp", Kind.TEXT),
        Target("thermal global mode", "/data/vendor/thermal/thermal-global-mode", Kind.TEXT),
        Target("cpu_boost input", "/sys/module/cpu_boost/parameters/input_boost_freq", Kind.TEXT),
        Target("cpu_boost sched_on_input", "/sys/module/cpu_boost/parameters/sched_boost_on_input", Kind.TEXT),
        Target("sched_group up", "/proc/sys/kernel/sched_group_upmigrate", Kind.TEXT),
        Target("sched_group down", "/proc/sys/kernel/sched_group_downmigrate", Kind.TEXT),
        Target("kgsl max_pwrlevel", "/sys/class/kgsl/kgsl-3d0/max_pwrlevel", Kind.TEXT),
        Target("kgsl devfreq governor", "/sys/class/kgsl/kgsl-3d0/devfreq/governor", Kind.TEXT),
        Target("cpuset top-app", "/dev/cpuset/top-app/cpus", Kind.TEXT),
        Target("stune top-app boost", "/dev/stune/top-app/schedtune.boost", Kind.TEXT),
        Target("block scheduler", "/sys/block/sda/queue/scheduler", Kind.SCHEDULER),
        Target("scene data dir", "/data/adb/scene", Kind.DIR),
        Target("system block device", "/dev/block/mapper/system", Kind.BLOCK),
        Target("property service", "vtools.scene.selinux.probe", Kind.PROP)
    )

    /**
     * Prove every capability instead of assuming "root means allowed":
     * each text node is read and written back (idempotent), the scheduler is
     * re-selected by its active name, directories get a temp file, the block
     * device is opened for write with zero bytes and the property probe is a
     * benign setprop. A `denied` line is the SELinux/DAC answer for the
     * running domain.
     */
    fun selfTest(): List<Capability> = targets.map { probe(it) }

    private fun probe(target: Target): Capability {
        return try {
            val output = shell(probeCommand(target)).lines().map { it.trim() }
            val state = output.firstOrNull { it.startsWith("STATE=") }?.removePrefix("STATE=") ?: "skipped"
            val detail = output.firstOrNull { it.startsWith("DETAIL=") }?.removePrefix("DETAIL=") ?: ""
            Capability(target.name, target.path, state, detail.take(160))
        } catch (ex: Exception) {
            Capability(target.name, target.path, "skipped", ex.javaClass.simpleName)
        }
    }

    private fun probeCommand(target: Target): String {
        val path = ShellEscape.quote(target.path)
        return when (target.kind) {
            Kind.TEXT -> """
p=$path
[ -e "${'$'}p" ] || { echo STATE=missing; exit 0; }
v="${'$'}(cat "${'$'}p" 2> /dev/null | head -c 200)"
[ -n "${'$'}v" ] || { echo STATE=skipped; echo DETAIL=unreadable; exit 0; }
if printf '%s\n' "${'$'}v" > "${'$'}p" 2> /tmp/.scene_sel_err; then
  echo STATE=ok; echo DETAIL=write-back ok
else
  echo STATE=denied; echo "DETAIL=${'$'}(head -c 120 /tmp/.scene_sel_err)"
fi
""".trimIndent()
            Kind.SCHEDULER -> """
p=$path
[ -e "${'$'}p" ] || { echo STATE=missing; exit 0; }
v="${'$'}(cat "${'$'}p" 2> /dev/null | sed 's/.*\[\(.*\)\].*/\1/')"
[ -n "${'$'}v" ] || { echo STATE=skipped; echo DETAIL=unreadable; exit 0; }
if printf '%s\n' "${'$'}v" > "${'$'}p" 2> /tmp/.scene_sel_err; then
  echo STATE=ok; echo DETAIL=active scheduler ${'$'}v
else
  echo STATE=denied; echo "DETAIL=${'$'}(head -c 120 /tmp/.scene_sel_err)"
fi
""".trimIndent()
            Kind.DIR -> """
p=$path
[ -d "${'$'}p" ] || { echo STATE=missing; exit 0; }
if (echo probe > "${'$'}p/.scene_selinux_probe" 2> /tmp/.scene_sel_err) && rm -f "${'$'}p/.scene_selinux_probe"; then
  echo STATE=ok; echo DETAIL=create+delete ok
else
  echo STATE=denied; echo "DETAIL=${'$'}(head -c 120 /tmp/.scene_sel_err)"
fi
""".trimIndent()
            Kind.BLOCK -> """
p=$path
[ -e "${'$'}p" ] || { echo STATE=missing; exit 0; }
if : > "${'$'}p" 2> /tmp/.scene_sel_err; then
  echo STATE=ok; echo DETAIL=write-open ok (0 bytes)
else
  echo STATE=denied; echo "DETAIL=${'$'}(head -c 120 /tmp/.scene_sel_err)"
fi
""".trimIndent()
            Kind.PROP -> """
v="${'$'}(date +%s)"
if setprop ${target.path} "${'$'}v" 2> /tmp/.scene_sel_err; then
  echo STATE=ok; echo DETAIL=${'$'}(getprop ${target.path})
else
  echo STATE=denied; echo "DETAIL=${'$'}(head -c 120 /tmp/.scene_sel_err)"
fi
""".trimIndent()
        }
    }

    /** Human summary + the per-capability table for the diagnostics bundle. */
    fun selfTestReport(): String {
        val results = selfTest()
        val denied = results.filter { it.state == "denied" }
        val missing = results.filter { it.state == "missing" }
        val sb = StringBuilder("Capability self-test (root sufficient?)\n")
        sb.append("  domain = ").append(domain().ifEmpty { "(unreadable)" })
            .append(", enforcing = ").append(enforcing()).append('\n')
        sb.append("  result = ").append(results.count { it.ok }).append('/').append(results.size)
            .append(" writable, ").append(denied.size).append(" denied, ")
            .append(missing.size).append(" absent\n")
        sb.append(
            when {
                denied.isEmpty() && missing.isEmpty() ->
                    "  verdict = root is sufficient for every Scene operation on this ROM\n"
                denied.isEmpty() ->
                    "  verdict = root is sufficient; the absent rows are kernel/ROM gaps, not policy\n"
                else ->
                    "  verdict = SELinux/DAC blocks ${denied.size} operation(s); " +
                        "use the repair rules or a permissive root domain\n"
            }
        )
        for (capability in results) {
            sb.append("    [").append(capability.state).append("] ")
                .append(capability.name).append(" -> ").append(capability.path)
                .append(if (capability.detail.isNotEmpty()) "  (${capability.detail})" else "")
                .append('\n')
        }
        return sb.toString()
    }

    private fun valueOf(path: String): String = try {
        shell("cat $path 2> /dev/null").trim().ifEmpty { "?" }
    } catch (ex: Exception) {
        "?"
    }

    /**
     * Root-manager health for the checks that explain "uid 0 but every command
     * fails". On APatch and FolkPatch-Re (`apd`), `su` is granted by the kernel
     * patch against `/data/adb/ap/package_config`: a row whose uid is stale after
     * a reinstall, or whose `sctx` names a domain the running policy does not
     * define, makes `su` exit before the shell ever starts. The same conditions
     * that bit us on the MIUI 14 surya build, so the report tells them apart.
     *
     * All probes are read-only; no `setcon` is attempted because changing the
     * context of the persistent shell kills the session on this ROM.
     */
    private fun managerHealth(): List<String> {
        val lines = ArrayList<String>()
        val uid = android.os.Process.myUid()
        val id = shell("id -u 2> /dev/null").trim()
        lines.add("  root shell = " + if (id == "0") "uid 0" else "unavailable ('$id')")
        if (RootBackend.manager() != "apatch") {
            return lines
        }

        val entry = shell("grep -F \",$uid,\" /data/adb/ap/package_config 2> /dev/null")
            .trim().lines().firstOrNull { it.isNotEmpty() }.orEmpty()
        if (entry.isEmpty()) {
            lines.add("  package entry = no row with uid $uid; su is denied until the manager re-syncs")
            return lines
        }

        val cols = entry.split(",")
        val allow = cols.getOrNull(2)?.trim().orEmpty()
        val sctx = cols.getOrNull(5)?.trim().orEmpty()
        lines.add("  package entry = allow=$allow uid=$uid sctx=$sctx")
        if (allow != "1") {
            lines.add("  package entry = allow is '$allow', not 1: su will be denied")
        }
        if (sctx.isNotEmpty()) {
            // Substring match on the policy string table: exact enough to flag a
            // domain that was never defined (0 occurrences), tolerant otherwise.
            val type = sctx.substringAfter("u:r:").substringBefore(":").ifEmpty { sctx }
            val inPolicy = (shell("grep -c $type /sys/fs/selinux/policy 2> /dev/null")
                .trim().toIntOrNull() ?: 0) > 0
            val live = shell("cat /proc/self/attr/current 2> /dev/null").trim()
            lines.add("  context entry = declared type in policy=${if (inPolicy) "yes" else "no"}, shell running as '$live'")
            if (live.isNotEmpty() && live != sctx) {
                lines.add("  context hint = declared '$sctx' but the shell runs as '$live' (stale entry or unapplied rules)")
            }
        }
        // `-x apd` (not `-f uid-listener`): a -f pattern would also match the
        // shell running the probe, whose command line contains the string.
        val listener = shell("pgrep -x apd 2> /dev/null | head -n 1").trim()
        lines.add(
            "  uid listener = " +
                if (listener.isEmpty()) "not running; uid changes after an update are not picked up"
                else "pid $listener"
        )
        return lines
    }

    /**
     * `avc: denied { perms } for (name|path)="..." ... scontext=u:r:<src>:s0
     * tcontext=u:object_r:<type>:s0 tclass=<cls>` - the denied block and the
     * name/path come first in the audit line, so the regex follows that order.
     */
    private val PATTERN = Regex(
        "avc: denied \\{ ([^}]*) \\} for" +
            "(?:.*?(?:path|name)=\"([^\"]*)\")?.*?" +
            "scontext=u:r:([^:]+):s0" +
            ".*?tcontext=u:object_r:([^:]+):s0" +
            ".*?tclass=([^\\s]+)"
    )

    private fun shell(command: String): String = KeepShellPublic.doCmdSync(command)
}
