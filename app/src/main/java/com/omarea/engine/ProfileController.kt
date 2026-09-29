package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.DeviceCaps
import com.omarea.engine.ParameterCatalog
import com.omarea.engine.ProfileApplier
import com.omarea.engine.ProfileKey
import com.omarea.engine.ProfilePlanner
import com.omarea.engine.TuningRepository
import com.omarea.util.PlatformUtils
import com.omarea.util.PropsUtils
import com.omarea.data.SpfConfig
import java.io.File

/**
 * Orchestrates the device-exact profile engine.
 *
 *   tuning JSON → [ProfilePlanner] (pure) → [ProfileApplier] (shell)
 *              → [DaemonController] (daemons) → [HwuiController] (props)
 *
 * Responsibility: the ON/OFF lifecycle of the engine and one-shot applies.
 * Non-goals: UI, event handling, external /data/powercfg.sh scripts
 * (ModeSwitcher owns the external escape hatch).
 */
object ProfileController {

    /** Mode prop kept in sync by ModeSwitcher. */
    private const val MODE_PROP = "vtools.powercfg"

    fun isEngineOff(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)

    fun platform(): String = PlatformUtils().getCPUName()

    // --------------------------------------------------------------- applies
    /** Applies the init tuning (engine ON only). */
    fun applyInit(context: Context): Boolean {
        if (isEngineOff(context)) return false
        val json = TuningRepository.read(context, platform()) ?: return false
        val plan = ProfilePlanner.planInit(json, DeviceCaps.read())
        if (plan.ops.isEmpty()) return false
        ProfileApplier.apply(plan)
        return true
    }

    /** Applies one profile (engine ON only): plan → apply → max handoff → daemons → hwui. */
    fun applyMode(context: Context, mode: String): Boolean {
        if (isEngineOff(context)) return false
        val json = TuningRepository.read(context, platform()) ?: return false
        val plan = ProfilePlanner.planProfile(json, mode, DeviceCaps.read())
        if (plan.ops.isEmpty()) {
            ShellLog.log("ProfileController", "no ops for '$mode' (profile missing in tuning?)", error = true)
            return false
        }
        // Silence the MIUI daemons FIRST: mi_thermald keeps re-locking
        // scaling_min/max in its loop and would overwrite the plan otherwise.
        DaemonController.ensureOn(context)
        ProfileApplier.apply(plan)
        plan.profileMax?.let { ProfileApplier.writeThermalProfileMax(it.first, it.second) }
        HwuiController.applyActive(context)
        return true
    }

    // --------------------------------------------------------------- engine
    /** OFF transition: stock release profile + default props + MIUI daemons. */
    fun release(context: Context) {
        TuningRepository.read(context, platform())?.let { json ->
            ProfileApplier.apply(ProfilePlanner.planProfile(json, ProfileKey.RELEASE, DeviceCaps.read()))
        }
        HwuiController.clear(context)
        DaemonController.ensureOff(context)
    }

    /** ON/OFF toggle from the Tuner card. */
    fun setEngineEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            // Bring back the base tuning immediately; the mode itself applies
            // on the next switch (or boot) by design.
            applyInit(context)
            DaemonController.ensureOn(context)
            HwuiController.applyActive(context)
        } else {
            release(context)
        }
    }

    /** Boot: init tuning + re-apply the last active mode + daemons. */
    fun applyBootState(context: Context) {
        if (isEngineOff(context)) return
        applyInit(context)

        // The mode prop is volatile; the persisted last mode is the reliable
        // source after a reboot (fallback to the prop for older installs).
        val persisted = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getString(SpfConfig.GLOBAL_SPF_LAST_MODE, "")
            ?: ""
        val mode = persisted.ifEmpty { PropsUtils.getProp(MODE_PROP) }

        if (mode.isEmpty() || !applyMode(context, mode)) {
            DaemonController.ensureOn(context)
        }
    }

    // -------------------------------------------------------------- catalog
    /** Regenerates Parameter.sh (valid in both engine states). */
    fun syncCatalog(context: Context) {
        try {
            val json = TuningRepository.read(context, platform()) ?: return
            val text = ParameterCatalog.generate(platform(), json, DeviceCaps.read())
            TuningRepository.dir().mkdirs()
            File(TuningRepository.dir(), "Parameter.sh").writeText(text)
        } catch (ex: Exception) {
            ShellLog.log("ProfileController.catalog", ex.message ?: "error", error = true)
        }
    }
}
