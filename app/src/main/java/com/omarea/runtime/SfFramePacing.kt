package com.omarea.runtime

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.omarea.common.shell.ShellLog
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.PropShell
import com.omarea.engine.RootShell
import com.omarea.util.CheckRootStatus

/**
 * Opt-in SurfaceFlinger frame pacing (AZenith-derived, docs/ATTRIBUTION.md).
 *
 * Writes the `debug.sf.*` phase-offset/duration props for the current refresh
 * rate so app and SurfaceFlinger work share the frame instead of colliding.
 * Experimental: AZenith applies these at module init; on this ROM they are
 * runtime props (resetprop) and may only take full effect after a reboot.
 * Values are computed by the pure [pacingFor] (JVM-tested) and deleted again
 * on disable / engine OFF / TRUE OFF / cleanup.
 *
 * Responsibility: compute + write/delete the props.
 * Non-goals: measuring the result (benchmark owns that).
 */
object SfFramePacing {

    /** All props this feature owns (deleted on restore). */
    val PROPS = listOf(
        "debug.sf.early.app.duration",
        "debug.sf.earlyGl.app.duration",
        "debug.sf.late.app.duration",
        "debug.sf.early.sf.duration",
        "debug.sf.earlyGl.sf.duration",
        "debug.sf.late.sf.duration",
        "debug.sf.early_app_phase_offset_ns",
        "debug.sf.high_fps_early_app_phase_offset_ns",
        "debug.sf.high_fps_late_app_phase_offset_ns",
        "debug.sf.early_phase_offset_ns",
        "debug.sf.high_fps_early_phase_offset_ns",
        "debug.sf.high_fps_late_phase_offset_ns",
        "debug.sf.phase_offset_threshold_for_next_vsync_ns",
        "debug.sf.enable_advanced_sf_phase_offset",
        "debug.sf.use_phase_offsets_as_durations"
    )

    data class Pacing(
        val appDuration: Long,
        val sfDuration: Long,
        val appOffset: Long,
        val sfOffset: Long,
        val threshold: Long
    )

    /**
     * Pure frame math: frame duration = 1e9/hz; the ratios follow AZenith's
     * per-refresh-rate table (higher rates give the app a larger share).
     */
    fun pacingFor(refreshHz: Int): Pacing {
        val hz = refreshHz.coerceIn(30, 240)
        val frame = 1_000_000_000.0 / hz
        val ratios: List<Double> = when {
            hz >= 120 -> listOf(0.68, 0.85, 0.58, 0.32, 0.28)
            hz >= 90 -> listOf(0.66, 0.82, 0.60, 0.30, 0.32)
            hz >= 75 -> listOf(0.64, 0.80, 0.62, 0.28, 0.35)
            else -> listOf(0.62, 0.75, 0.65, 0.25, 0.38)
        }
        val threshold = (frame * ratios[4]).coerceIn(frame * 0.22, frame * 0.45)
        return Pacing(
            appDuration = (frame * ratios[2]).toLong(),
            sfDuration = (frame * ratios[3]).toLong(),
            appOffset = -(frame * ratios[0]).toLong(),
            sfOffset = -(frame * ratios[1]).toLong(),
            threshold = threshold.toLong()
        )
    }

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        .getBoolean(SpfConfig.GLOBAL_SPF_SF_PACING, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            .edit().putBoolean(SpfConfig.GLOBAL_SPF_SF_PACING, enabled).apply()
        if (enabled) apply(context) else restore(context)
    }

    fun apply(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        val hz = currentRefreshHz(app)
        val p = pacingFor(hz)
        val commands = buildList {
            for (key in listOf(
                "debug.sf.early.app.duration", "debug.sf.earlyGl.app.duration", "debug.sf.late.app.duration"
            )) add(PropShell.set(key, p.appDuration.toString()))
            for (key in listOf(
                "debug.sf.early.sf.duration", "debug.sf.earlyGl.sf.duration", "debug.sf.late.sf.duration"
            )) add(PropShell.set(key, p.sfDuration.toString()))
            for (key in listOf(
                "debug.sf.early_app_phase_offset_ns",
                "debug.sf.high_fps_early_app_phase_offset_ns",
                "debug.sf.high_fps_late_app_phase_offset_ns"
            )) add(PropShell.set(key, p.appOffset.toString()))
            for (key in listOf(
                "debug.sf.early_phase_offset_ns",
                "debug.sf.high_fps_early_phase_offset_ns",
                "debug.sf.high_fps_late_phase_offset_ns"
            )) add(PropShell.set(key, p.sfOffset.toString()))
            add(PropShell.set("debug.sf.phase_offset_threshold_for_next_vsync_ns", p.threshold.toString()))
            add(PropShell.set("debug.sf.enable_advanced_sf_phase_offset", "1"))
            add(PropShell.set("debug.sf.use_phase_offsets_as_durations", "1"))
        }
        RootShell.run(commands.joinToString("\n"))
        ShellLog.log("SfFramePacing", "${hz}Hz: app=${p.appDuration}ns sf=${p.sfDuration}ns")
    }

    /** Own change: delete the props on every exit path. */
    fun restore(context: Context) {
        runCatching {
            RootShell.run(PROPS.joinToString("\n") { PropShell.delete(it) })
            ShellLog.log("SfFramePacing", "props removed")
        }
    }

    private fun currentRefreshHz(context: Context): Int = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        Math.round(dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f)
    } catch (_: Exception) {
        60
    }

    private fun allowed(context: Context): Boolean =
        !TrueOff.isOff(context) &&
            !ProfileController.isEngineOff(context) &&
            CheckRootStatus.isAvailable()
}
