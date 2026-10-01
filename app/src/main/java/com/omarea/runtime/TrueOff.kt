package com.omarea.runtime

import android.content.Context
import android.widget.Toast
import com.omarea.data.SpfConfig
import com.omarea.engine.ProfileController
import com.omarea.engine.ThermalService
import com.omarea.ui.popup.FloatFpsWatch
import com.omarea.util.AccessibleServiceHelper

/**
 * TRUE OFF — the master switch that stops every actuator.
 *
 * Responsibility: one flag (`SpfConfig.GLOBAL_SPF_TRUE_OFF`) that every write
 * funnel checks, plus the enter/exit transitions:
 *
 *  - **enter** (order matters): persist the flag FIRST (all guards live from
 *    that instant) → stop actuator services/timers (thermal guard, timing
 *    alarms, FPS overlay, mode notification) → disable the accessibility
 *    service via Settings.Secure (backing up its previous state) → release
 *    the profile engine to stock ONCE. After that the app performs **zero
 *    parameter writes**; read-only monitoring (battery/screen receivers) stays
 *    alive so the UI keeps working.
 *  - **exit**: clear the flag → restore accessibility if we disabled it →
 *    re-apply the normal engine boot state (init + last mode + daemons) →
 *    re-schedule timing alarms.
 *
 * Guards live at each writer (ModeSwitcher, ProfileController, ThermalService,
 * HwuiController, AppSwitchHandler, accessibility entry points, triggers,
 * BootWorker) so a missed caller still cannot write. `force` is only for the
 * enter/exit transitions themselves.
 *
 * Non-goals: swap/zRAM (hard rule: untouched), charge parameters (already
 * read-only), and reads/monitoring (explicitly allowed while OFF).
 */
object TrueOff {

    @Volatile
    private var off: Boolean = false

    /** Pure guard decision (JVM-testable): writes allowed unless OFF. */
    fun allowsWrite(off: Boolean, force: Boolean): Boolean = force || !off

    /** Cached flag; a cold call re-reads the persisted pref. */
    @Synchronized
    fun isOff(context: Context): Boolean {
        if (!off) {
            off = prefs(context).getBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF, false)
        }
        return off
    }

    fun allowsWrite(context: Context, force: Boolean = false): Boolean =
        allowsWrite(isOff(context), force)

    /** Guard + toast for manual UI actions. True = action may proceed. */
    fun guardOrToast(context: Context): Boolean {
        if (!isOff(context)) return true
        Toast.makeText(
            context,
            com.omarea.vtools.R.string.true_off_blocked,
            Toast.LENGTH_SHORT
        ).show()
        return false
    }

    // ------------------------------------------------------------ transitions
    /** Enter TRUE OFF. Blocking (shell I/O) — call off the main thread. */
    fun enter(context: Context) {
        val app = context.applicationContext
        // 1. Persist FIRST: every guard is live from here on.
        prefs(app).edit().putBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF, true).apply()
        off = true

        // 2. Stop actuator services, timers and overlays.
        runCatching { ThermalService.stop(app) }
        runCatching { TimingTaskManager(app).updateAlarmManager() } // OFF → cancels all alarms
        runCatching { FloatFpsWatch(app).hidePopupWindow() }
        runCatching { AlwaysNotification(app, true).hideNotify() }

        // 3. Disable the accessibility service (backup its previous state).
        //    Settings.Secure write — part of the transition, not control.
        val a11yWasOn = runCatching { AccessibleServiceHelper().serviceRunning(app) }.getOrDefault(false)
        prefs(app).edit().putBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF_A11Y, a11yWasOn).apply()
        if (a11yWasOn) {
            runCatching { AccessibleServiceHelper().stopSceneModeService(app) }
        }

        // 4. Release the engine to stock ONCE (force bypasses the guard).
        runCatching { ProfileController.setEngineEnabled(app, false, force = true) }
    }

    /** Leave TRUE OFF. Blocking (shell I/O) — call off the main thread. */
    fun exit(context: Context) {
        val app = context.applicationContext
        // Clear first: guards lift, then we re-apply the normal state.
        prefs(app).edit().putBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF, false).apply()
        off = false

        if (prefs(app).getBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF_A11Y, false)) {
            runCatching { AccessibleServiceHelper().startSceneModeService(app) }
            prefs(app).edit().putBoolean(SpfConfig.GLOBAL_SPF_TRUE_OFF_A11Y, false).apply()
        }

        // Engine was ON before → re-apply init + last mode + daemons + HWUI.
        // Engine OFF → the device is already stock (enter released it).
        if (!ProfileController.isEngineOff(app)) {
            runCatching { ModeSwitcher().applyBootState() }
        }
        runCatching { TimingTaskManager(app).updateAlarmManager() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
}
