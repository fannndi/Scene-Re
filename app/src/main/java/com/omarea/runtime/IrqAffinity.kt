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
 * Opt-in Qualcomm IRQ affinity (IRQ-Balancer-Configuration, corrected).
 *
 * Pins the GPU (`kgsl-3d0`) and display (`msm_drm`) IRQs to chosen CPUs. The
 * ROM's `msm_irqbalance` otherwise rebalances them within ~90 s, so the
 * feature first bind-mounts a modified `msm_irqbalance.conf` whose
 * `IGNORED_IRQ` lists their **hwirq** numbers (the module's virq approach is
 * silently ignored by the daemon) and restarts it; only then are the
 * `smp_affinity_list` writes sticky. Verified on the target device —
 * see `docs/IRQ-AFFINITY.md`.
 *
 * Opt-in (default OFF), engine-ON only, TRUE OFF guarded; every exit path
 * (engine OFF / TRUE OFF / cleanup / uninstall guard) unmounts the conf and
 * restarts the balancer. Values come from the tuning JSON top-level
 * `irq_affinity` block (e.g. `{"kgsl":"6","msm_drm":"7"}`); per-profile
 * values can be layered on later.
 *
 * Responsibility: discover, mount, pin, restore.
 * Non-goals: planning the values ([IrqAffinityPolicy]).
 */
object IrqAffinity {

    private const val CONF = "/vendor/etc/msm_irqbalance.conf"
    private const val TMP_CONF = "/data/local/tmp/scene_irqbalance.conf"
    private const val DAEMON_PROC = "msm_irqbalance"
    private const val DAEMON_SVC = "vendor.msm_irqbalance"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_IRQ_AFFINITY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_IRQ_AFFINITY, enabled).apply()
        if (enabled) apply(context) else restore(context)
    }

    /** Configured CPU lists (`kgsl`/`msm_drm`) from the tuning JSON. */
    fun values(context: Context): Map<String, String> = try {
        val platform = PlatformUtils().getCPUName()
        val json = TuningRepository.read(context, platform) ?: return emptyMap()
        val obj = json.optJSONObject("irq_affinity") ?: return emptyMap()
        obj.keys().asSequence()
            .mapNotNull { key ->
                val value = obj.optString(key)
                if (value.isEmpty()) null else key to value
            }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    // ------------------------------------------------------------------ apply
    fun apply(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        val irqs = discover()
        if (irqs.isEmpty()) {
            ShellLog.log("IrqAffinity", "no managed IRQs (kgsl-3d0/msm_drm) on this kernel", error = true)
            return
        }
        val values = values(app)
            .filterKeys { IrqAffinityPolicy.TARGETS.containsValue(it) }
            .filterValues { IrqAffinityPolicy.isValidCpuList(it) }
        if (values.isEmpty()) {
            ShellLog.log("IrqAffinity", "no valid irq_affinity values, skipped", error = true)
            return
        }

        val daemon = daemonRunning()
        val mounted = isMounted()
        val script = StringBuilder()

        if (daemon && !mounted) {
            // Extend IGNORED_IRQ with the *hwirq* numbers, bind-mount, restart.
            val stock = RootShell.run("cat $CONF 2>/dev/null")
            val patched = IrqAffinityPolicy.extendIgnoredIrq(stock, irqs.values.map { it.hwirq })
            script.append("cat > $TMP_CONF <<'SCENE_IRQ_CONF'\n")
            script.append(patched.trimEnd())
            script.append("\nSCENE_IRQ_CONF\n")
            script.append("mount --bind $TMP_CONF $CONF && ")
            script.append("restorecon -F $CONF 2>/dev/null\n")
            script.append(restartDaemon())
        }

        for ((key, cpus) in values) {
            val name = IrqAffinityPolicy.TARGETS.entries.firstOrNull { it.value == key }?.key ?: continue
            val irq = irqs[name] ?: continue
            script.append("echo $cpus > /proc/irq/${irq.virq}/smp_affinity_list\n")
        }
        if (daemon) {
            script.append("renice -n -10 -p \$(pidof $DAEMON_PROC) >/dev/null 2>&1\n")
        }

        RootShell.run(script.toString().trimEnd())
        ShellLog.log(
            "IrqAffinity",
            "applied: ${values.entries.joinToString { "${it.key}=${it.value}" }}" +
                (if (daemon) " (daemon ${if (mounted) "already" else "conf"} handled)" else " (no daemon)")
        )
    }

    /**
     * Own change: unmount the modified conf and hand the IRQs back to
     * msm_irqbalance. Allowed on every exit path (engine OFF, TRUE OFF,
     * cleanup, uninstall guard).
     */
    fun restore(context: Context) {
        if (!CheckRootStatus.isAvailable()) return
        runCatching {
            RootShell.run(
                buildString {
                    appendLine("umount $CONF 2>/dev/null")
                    appendLine("rm -f $TMP_CONF")
                    appendLine("if pidof $DAEMON_PROC >/dev/null 2>&1; then")
                    appendLine("  ${restartDaemon().trimEnd()}")
                    appendLine("  renice -n 0 -p \$(pidof $DAEMON_PROC) >/dev/null 2>&1")
                    appendLine("fi")
                }.trimEnd()
            )
            ShellLog.log("IrqAffinity", "restored (conf unmounted, balancer back in charge)")
        }
    }

    /** Short status for Diagnostics. */
    fun describe(context: Context): String {
        if (!isEnabled(context)) return "off"
        val values = values(context)
        return if (values.isEmpty()) {
            "on (no values)"
        } else {
            "on · " + values.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun discover(): Map<String, IrqAffinityPolicy.Irq> {
        val out = runCatching { RootShell.run("cat /proc/interrupts 2>/dev/null") }.getOrDefault("")
        return IrqAffinityPolicy.parseInterrupts(out)
    }

    private fun daemonRunning(): Boolean =
        runCatching { RootShell.run("pidof $DAEMON_PROC 2>/dev/null") }.getOrDefault("").trim().isNotEmpty()

    private fun isMounted(): Boolean = runCatching {
        RootShell.run("mount 2>/dev/null | grep -q msm_irqbalance.conf && echo mounted")
    }.getOrDefault("").contains("mounted")

    /** Restart snippet shared by apply/restore (init restarts the service). */
    private fun restartDaemon(): String =
        "if [ -n \"\$(getprop init.svc.$DAEMON_SVC)\" ]; then " +
            "setprop ctl.restart $DAEMON_SVC; else kill \$(pidof $DAEMON_PROC) 2>/dev/null; fi\n" +
            "sleep 2"

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()
}
