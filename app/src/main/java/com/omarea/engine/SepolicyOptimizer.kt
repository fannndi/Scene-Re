package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig

/**
 * Scoped SELinux tuning for APatch.
 *
 * The rules are delivered through the **module path** ([SepolicyModule]):
 * APatch applies `/data/adb/modules/scene_sepolicy/sepolicy.rule` at
 * post-fs-data through its own (working) policy-injection path and announces
 * the load to the kernel AVC. They are therefore persistent — effective until
 * disabled — and need no per-boot runtime loader.
 *
 * The old runtime `magiskpolicy --apply --live` was removed: on this APatch
 * build it is a no-op for enforcement (`runtime policy authentication
 * unavailable`) and it re-loaded the policy WITHOUT APatch's boot-time
 * patches, which broke every direct write.
 *
 * Reads (default): let untrusted_app read the sysfs nodes the UI polls
 * (GPU devfreq, cpufreq), which removes the avc-denial spam behind the Home
 * screen timer.
 *
 * Writes (opt-in, [SpfConfig.GLOBAL_SPF_DIRECT_WRITES]): additionally allows
 * untrusted_app writes to a whitelist of tuning nodes (chmod 0666) so profile
 * applies can skip the root shell entirely.
 *
 * NOTE: magiskpolicy/sepolicy.rule use the classic statement format
 * `allow <source> <target> <class> <perms...>` (space separated, no colon).
 *
 * Responsibility: build the policy statements + node permissions, sync the
 * module, verify the result.
 * Non-goals: applying profiles (ProfileApplier falls back per op).
 */
object SepolicyOptimizer {

    private const val MAGISKPOLICY = "/data/adb/ap/bin/magiskpolicy"
    private const val RULES_FILE = "/data/local/tmp/scene_policy.rules"

    /** Read rules keep the app's direct sysfs polling silent. */
    private val READ_RULES = listOf(
        "allow untrusted_app vendor_sysfs_kgsl dir { search open read getattr }",
        "allow untrusted_app vendor_sysfs_kgsl file { read open getattr }",
        "allow untrusted_app vendor_sysfs_kgsl lnk_file { read getattr }",
        "allow untrusted_app sysfs_devices_system_cpu dir { search open read getattr }",
        "allow untrusted_app sysfs_devices_system_cpu file { read open getattr }",
        "allow untrusted_app sysfs_devices_system_cpu lnk_file { read getattr }",
        // Types hit by the plan and the Home/Tweaks polls (device-verified).
        "allow untrusted_app vendor_sysfs_msm_perf dir { search open read getattr }",
        "allow untrusted_app vendor_sysfs_msm_perf file { read open getattr }",
        "allow untrusted_app vendor_sysfs_cpu_boost dir { search open read getattr }",
        "allow untrusted_app vendor_sysfs_cpu_boost file { read open getattr }",
        "allow untrusted_app vendor_sysfs_devfreq dir { search open read getattr }",
        "allow untrusted_app vendor_sysfs_devfreq file { read open getattr }"
    )

    /** Extra rules for the opt-in direct-write mode. */
    private val WRITE_RULES = listOf(
        "allow untrusted_app sysfs_devices_system_cpu file write",
        "allow untrusted_app sysfs_devices_system_cpu dir write",
        // GPU pwrlevel nodes live under vendor_sysfs_kgsl — without this the
        // direct-write path silently fell back to a root shell for every GPU op.
        "allow untrusted_app vendor_sysfs_kgsl file write",
        "allow untrusted_app vendor_sysfs_kgsl dir write",
        // msm_performance freq-lock release + cpu_boost knobs (plan ops).
        "allow untrusted_app vendor_sysfs_msm_perf file write",
        "allow untrusted_app vendor_sysfs_cpu_boost file write"
    )

    /** Exposed for unit tests (statement format must stay classic, no colon). */
    internal fun statements(writes: Boolean): List<String> =
        READ_RULES + if (writes) WRITE_RULES else emptyList()

    /** Exposed for unit tests: the chmod whitelist. */
    internal fun writeNodes(): List<String> = WRITE_NODES

    /** Nodes chmod-ed to 0666 when direct writes are enabled. */
    private val WRITE_NODES = listOf(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
        "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
        "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
        // Frequency-lock release + boost knobs (written on every apply).
        "/sys/module/msm_performance/parameters/cpu_max_freq",
        "/sys/module/msm_performance/parameters/cpu_min_freq",
        "/sys/module/cpu_boost/parameters/input_boost_freq",
        "/sys/module/cpu_boost/parameters/input_boost_ms",
        "/sys/module/cpu_boost/parameters/sched_boost_on_input",
        "/sys/module/cpu_boost/parameters/powerkey_input_boost_freq",
        "/sys/module/cpu_boost/parameters/powerkey_input_boost_ms"
    )

    fun directWritesEnabled(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_DIRECT_WRITES, false)

    /**
     * Applies read rules always; write rules + node chmod when [writes].
     * Returns a human-readable status for the caller's toast/log.
     */
    fun apply(context: Context, writes: Boolean = directWritesEnabled(context)): String {
        if (!RootShell.run("[ -x $MAGISKPOLICY ] && echo yes").contains("yes")) {
            ShellLog.log("SepolicyOptimizer", "magiskpolicy not available", error = true)
            return "magiskpolicy not available"
        }

        val rules = READ_RULES + if (writes) WRITE_RULES else emptyList()
        RootShell.run(
            "cat > $RULES_FILE <<'SCENE_RULES'\n${rules.joinToString("\n")}\nSCENE_RULES"
        )

        // Durable + EFFECTIVE path: APatch applies a module's sepolicy.rule at
        // post-fs-data through its own (working) path and announces the policy
        // load to the kernel AVC. The old runtime `magiskpolicy --apply --live`
        // is deliberately NOT used anymore: on this APatch build it is a no-op
        // for enforcement ("runtime policy authentication unavailable") AND it
        // re-loads the policy without APatch's boot-time patches, which made
        // every direct write fail even after the module had applied.
        val moduleOk = SepolicyModule.sync(rules.joinToString("\n"))

        RootShell.run(
            WRITE_NODES.joinToString("; ") {
                "chmod ${if (writes) "0666" else "0664"} $it 2>/dev/null"
            }
        )

        ShellLog.log(
            "SepolicyOptimizer",
            "${rules.size} rules written (writes=$writes), module ${if (moduleOk) "synced" else "FAILED"}"
        )

        val status = verify(writes)
        return status + " · module " + (if (moduleOk) "synced (reboot to enforce)" else "sync failed")
    }

    /**
     * Read-back check: every whitelisted node must be readable (and writable
     * when direct writes are on) *from the app process*. The write check
     * rewrites the node's current value (access(2) lies on this kernel).
     */
    private fun verify(writes: Boolean): String {
        var readable = 0
        var writable = 0
        val unreadable = ArrayList<String>()
        for (node in WRITE_NODES) {
            val file = java.io.File(node)
            if (file.exists() && file.canRead()) readable++ else unreadable += node
            if (writes && file.exists() && DirectWrite.probe(node).isEmpty()) writable++
        }
        val status = "read $readable/${WRITE_NODES.size}" +
            if (writes) ", write $writable/${WRITE_NODES.size}" else ""
        if (unreadable.isNotEmpty()) {
            ShellLog.log("SepolicyOptimizer.verify", "unreadable: $unreadable", error = true)
        }
        ShellLog.log("SepolicyOptimizer.verify", status)
        return status
    }
}
