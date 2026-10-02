package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.RootShell
import com.omarea.engine.TuningRepository
import com.omarea.util.CheckRootStatus
import com.omarea.util.PlatformUtils

/**
 * Opt-in kernel crash guard (AZenith-derived, docs/ATTRIBUTION.md).
 *
 * Writes `panic=0` / `panic_on_oops=0` / `panic_on_warn=0` /
 * `softlockup_panic=0` so an oops does not reboot the device. This can mask
 * kernel bugs, so it is **off by default** and clearly labelled experimental.
 * Values are data-driven: `tuning.json` `kernel_guard.enable` /
 * `kernel_guard.stock` (the shipped stock values were probe-verified:
 * panic=5, panic_on_warn=0, panic_on_oops=1). The per-boot StockSnapshot also
 * captures these nodes, so engine OFF restores them through the normal path.
 *
 * Responsibility: read the JSON values + write/restore the sysctls.
 * Non-goals: deciding when the engine runs.
 */
object KernelCrashGuard {

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_KERNEL_CRASH_GUARD, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_KERNEL_CRASH_GUARD, enabled).apply()
        if (enabled) apply(context) else restore(context)
    }

    fun apply(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        val values = values(app, "enable")
        if (values.isEmpty()) return
        write(values)
        ShellLog.log("KernelCrashGuard", "applied: ${values.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    /** Own change: restoring is allowed on every exit path. */
    fun restore(context: Context) {
        val values = values(context, "stock")
        if (values.isEmpty()) return
        write(values)
        ShellLog.log("KernelCrashGuard", "restored stock panic values")
    }

    private fun values(context: Context, block: String): Map<String, String> = try {
        val platform = PlatformUtils().getCPUName()
        val json = TuningRepository.read(context, platform) ?: return emptyMap()
        val obj = json.optJSONObject("kernel_guard")?.optJSONObject(block) ?: return emptyMap()
        obj.keys().asSequence()
            .mapNotNull { key -> obj.optString(key).takeIf { it.isNotEmpty() }?.let { key to it } }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun write(values: Map<String, String>) {
        runCatching {
            RootShell.run(
                values.entries.joinToString("\n") {
                    "echo '${it.value}' > /proc/sys/kernel/${it.key} 2>/dev/null"
                }
            )
        }
    }

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()
}
