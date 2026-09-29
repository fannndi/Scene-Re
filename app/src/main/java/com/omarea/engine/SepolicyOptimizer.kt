package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig

/**
 * Scoped SELinux tuning via APatch's magiskpolicy (live patch, lost on reboot
 * and re-applied by BootWorker — no bootloop risk).
 *
 * Reads (default): let untrusted_app read the sysfs nodes the UI polls
 * (GPU devfreq, cpufreq), which removes the avc-denial spam behind the Home
 * screen timer.
 *
 * Writes (opt-in, [SpfConfig.GLOBAL_SPF_DIRECT_WRITES]): additionally makes a
 * whitelist of tuning nodes world-writable and allows untrusted_app writes so
 * profile applies can skip the root shell entirely.
 *
 * NOTE: this magiskpolicy build uses the classic statement format
 * `allow <source> <target> <class> <perms...>` (space separated, no colon).
 *
 * Responsibility: build + apply policy statements and node permissions.
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
        "allow untrusted_app sysfs_devices_system_cpu lnk_file { read getattr }"
    )

    /** Extra rules for the opt-in direct-write mode. */
    private val WRITE_RULES = listOf(
        "allow untrusted_app sysfs_devices_system_cpu file write",
        "allow untrusted_app sysfs_devices_system_cpu dir write"
    )

    /** Nodes chmod-ed to 0666 when direct writes are enabled. */
    private val WRITE_NODES = listOf(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
        "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
        "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
    )

    fun directWritesEnabled(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_DIRECT_WRITES, false)

    /** Applies read rules always; write rules + node chmod when [writes]. */
    fun apply(context: Context, writes: Boolean = directWritesEnabled(context)) {
        if (!RootShell.run("[ -x $MAGISKPOLICY ] && echo yes").contains("yes")) {
            ShellLog.log("SepolicyOptimizer", "magiskpolicy not available", error = true)
            return
        }

        val rules = READ_RULES + if (writes) WRITE_RULES else emptyList()
        RootShell.run(
            "cat > $RULES_FILE <<'SCENE_RULES'\n${rules.joinToString("\n")}\nSCENE_RULES"
        )
        val out = RootShell.run("$MAGISKPOLICY --apply $RULES_FILE --live 2>&1").trim()
        if (out.isNotEmpty()) {
            ShellLog.log("SepolicyOptimizer", out.take(300), error = true)
        } else {
            ShellLog.log("SepolicyOptimizer", "applied ${rules.size} rules (writes=$writes)")
        }

        RootShell.run(
            WRITE_NODES.joinToString("; ") {
                "chmod ${if (writes) "0666" else "0664"} $it 2>/dev/null"
            }
        )
    }
}
