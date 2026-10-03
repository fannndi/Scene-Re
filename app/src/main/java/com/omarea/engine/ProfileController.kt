package com.omarea.engine

import android.content.Context
import com.omarea.common.shell.ShellLog
import com.omarea.engine.DeviceCaps
import com.omarea.engine.ParameterCatalog
import com.omarea.engine.ProfileApplier
import com.omarea.engine.ProfileKey
import com.omarea.engine.ProfilePlanner
import com.omarea.engine.TuningRepository
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import com.omarea.util.PlatformUtils
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

    fun isEngineOff(context: Context): Boolean =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OFF, false)

    fun platform(): String = PlatformUtils().getCPUName()

    // --------------------------------------------------------------- applies
    /** Applies the init tuning (engine ON only, blocked during TRUE OFF). */
    fun applyInit(context: Context): Boolean {
        if (isEngineOff(context)) return false
        if (!TrueOff.allowsWrite(context)) return false
        // Monitor mode: tanpa root tidak ada satu pun jalur tulis yang bisa
        // mendarat — jangan jalan sebagai no-op senyap.
        if (!CheckRootStatus.isAvailable()) {
            ShellLog.log("ProfileController", "applyInit skipped: no root (monitor mode)")
            return false
        }
        StockSnapshot.ensureCaptured(context)
        val json = TuningRepository.read(context, platform()) ?: return false
        val plan = ProfilePlanner.planInit(json, DeviceCaps.read())
        if (plan.ops.isEmpty()) return false
        ProfileApplier.apply(plan)
        return true
    }

    /** What the last [applyMode] did — the Tuner shows this as feedback. */
    data class ApplyReport(
        val mode: String,
        /** Planned writes ran and every verified node matched. */
        val ok: Boolean,
        /** Number of planned writes. */
        val ops: Int,
        /** Nodes that did not verify (empty when fully applied). */
        val mismatches: List<String>
    )

    /** Report of the most recent [applyMode] call in this process. */
    @Volatile
    var lastReport: ApplyReport? = null
        private set

    /** Applies one profile (engine ON only): plan → apply → max handoff → daemons → hwui. */
    fun applyMode(context: Context, mode: String): Boolean {
        fun fail(reason: String): Boolean {
            ShellLog.log("ProfileController", "applyMode($mode) skipped: $reason", error = true)
            lastReport = ApplyReport(mode, ok = false, ops = 0, mismatches = emptyList())
            return false
        }

        if (isEngineOff(context)) return fail("engine off")
        if (!TrueOff.allowsWrite(context)) return fail("true off")
        if (!CheckRootStatus.isAvailable()) return fail("no root (monitor mode)")
        StockSnapshot.ensureCaptured(context)
        val json = TuningRepository.read(context, platform()) ?: return fail("no tuning for ${platform()}")
        val caps = DeviceCaps.read()
        var plan = ProfilePlanner.planProfile(json, mode, caps)
        if (plan.ops.isEmpty()) {
            // Per-profile preset fallback: an older user copy may not define
            // this profile yet; the shipped preset still applies instead of
            // leaving the mode dead. The user copy keeps winning for every
            // profile it does define.
            val preset = TuningRepository.readPreset(context, platform())
            if (preset != null) plan = ProfilePlanner.planProfile(preset, mode, caps)
        }
        if (plan.ops.isEmpty()) return fail("no ops for '$mode' (profile missing in tuning?)")
        // Silence the MIUI daemons FIRST: mi_thermald keeps re-locking
        // scaling_min/max in its loop and would overwrite the plan otherwise.
        DaemonController.ensureOn(context)
        ProfileApplier.directWrites = SepolicyOptimizer.directWritesEnabled(context)
        val mismatches = ProfileApplier.apply(plan)
        plan.profileMax?.let { ProfileApplier.writeThermalProfileMax(it.first, it.second, plan.profileGpu) }
            ?: plan.profileGpu?.let { ProfileApplier.writeThermalProfileMax(-1L, -1L, it) }
        // Pass the target mode explicitly: the runtime mode prop is only
        // updated after a successful apply, so resolving from the prop here
        // used to write the *previous* profile's HWUI values.
        HwuiController.applyActive(context, mode)
        lastReport = ApplyReport(mode, mismatches.isEmpty(), plan.ops.size, mismatches)
        return true
    }

    // --------------------------------------------------------------- engine
    /**
     * OFF transition: stop the thermal guard first (mi_thermald takes over),
     * then restore the pre-engine stock — the per-boot snapshot when available
     * (real ROM/kernel state), the static release profile otherwise — and
     * clear the HWUI overrides.
     */
    fun release(context: Context) {
        if (!CheckRootStatus.isAvailable()) {
            ShellLog.log("ProfileController", "release skipped: no root (monitor mode)")
            return
        }
        DaemonController.ensureOff(context)
        val snapshot = StockSnapshot.restorePlan(context)
        if (snapshot != null && snapshot.ops.isNotEmpty()) {
            ProfileApplier.directWrites = SepolicyOptimizer.directWritesEnabled(context)
            ProfileApplier.apply(snapshot)
            ShellLog.log("ProfileController.release", "restored stock snapshot (${snapshot.ops.size} ops)")
        } else {
            TuningRepository.read(context, platform())?.let { json ->
                ProfileApplier.apply(ProfilePlanner.planProfile(json, ProfileKey.RELEASE, DeviceCaps.read()))
            }
            ShellLog.log("ProfileController.release", "no snapshot — applied the static release profile")
        }
        HwuiController.clear(context)
    }

    /**
     * ON/OFF toggle from the Tuner card.
     * Blocked while TRUE OFF unless [force] (the enter/exit transitions are
     * the only forced callers).
     */
    fun setEngineEnabled(context: Context, enabled: Boolean, force: Boolean = false) {
        if (!TrueOff.allowsWrite(context, force)) return
        if (enabled) {
            // Base tuning now; the Tuner immediately follows with
            // ModeSwitcher.ensureReady() which re-applies the saved mode.
            applyInit(context)
            DaemonController.ensureOn(context)
            HwuiController.applyActive(context)
        } else {
            release(context)
        }
    }

    // -------------------------------------------------------------- catalog
    /** Regenerates Parameter.sh (valid in both engine states). */
    fun syncCatalog(context: Context) {
        try {
            val json = TuningRepository.read(context, platform()) ?: return
            val text = ParameterCatalog.generate(
                platform(), json, DeviceCaps.read(),
                KernelCompat.snapshot(context).locked.map { it.id }.toSet()
            )
            TuningRepository.dir().mkdirs()
            File(TuningRepository.dir(), "Parameter.sh").writeText(text)
        } catch (ex: Exception) {
            ShellLog.log("ProfileController.catalog", ex.message ?: "error", error = true)
        }
    }
}
