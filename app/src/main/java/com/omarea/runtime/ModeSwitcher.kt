package com.omarea.runtime

import android.content.Context
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.engine.DaemonController
import com.omarea.engine.ModeState
import com.omarea.engine.ProfileController
import com.omarea.util.CheckRootStatus
import com.omarea.util.PropsUtils
import com.omarea.data.SpfConfig
import com.omarea.vtools.R

/**
 * Runtime mode switching.
 *
 * Two configuration sources exist:
 *  - "engine"  : the device tuning.json, applied by [ProfileController]
 *  - "external": /data/powercfg.sh installed by a power user or module
 *
 * The engine is the default and the external script wins when present.
 * While the profile engine is OFF nothing is applied (device stays stock).
 *
 * Responsibility: mode orchestration + source selection.
 * Non-goals: planning/applying tuning (ProfileController), daemons (DaemonController).
 */
open class ModeSwitcher {
    companion object {
        const val SOURCE_ENGINE = "SOURCE_ENGINE"
        const val SOURCE_OUTSIDE = "SOURCE_OUTSIDE"

        // Mode ids. FAST is the legacy id of the "Custom" profile and is kept
        // for stored preferences; ProfileKey.canonical() maps it to "custom".
        internal var POWERSAVE = "powersave"
        internal var PERFORMANCE = "performance"
        internal var FAST = "fast"
        internal var BALANCE = "balance"
        internal var IGONED = "igoned"
        internal var DEFAULT = BALANCE

        private const val INIT = "init"

        const val OUTSIDE_POWER_CFG_PATH = "/data/powercfg.sh"
        const val OUTSIDE_POWER_CFG_BASE = "/data/powercfg-base.sh"

        private const val PROVIDER_ENGINE = "engine"
        private const val PROVIDER_OUTSIDE = "outside"

        private var inited = false
        private var provider = ""

        /** Active configuration source. */
        fun getCurrentSource(): String =
            if (CpuConfigInstaller().outsideConfigInstalled()) SOURCE_OUTSIDE else SOURCE_ENGINE

        fun getCurrentSourceName(): String = when (getCurrentSource()) {
            SOURCE_OUTSIDE -> "External script"
            else -> "Tuning JSON"
        }

        internal fun getModName(mode: String): String {
            when (mode) {
                POWERSAVE -> return "Power Save"
                PERFORMANCE -> return "Performance"
                FAST, "custom" -> return "Custom"
                BALANCE -> return "Balanced"
                IGONED -> return "Maintain status"
                "" -> return "Global Default"
                else -> return "Unknown"
            }
        }

        private var currentPowercfg: String = ""
        private var currentPowercfgApp: String = ""

        /** Serializes init/restore so the Tuner worker and UI taps cannot interleave. */
        private val initLock = Any()

        /** One-shot per process: the saved mode is re-applied right after init. */
        private var savedModeRestored = false

        /** Persisted last mode (survives reboot; the prop is volatile). */
        internal fun savedMode(): String =
            Scene.context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                .getString(SpfConfig.GLOBAL_SPF_LAST_MODE, "") ?: ""

        /**
         * Active mode. First non-empty store wins: runtime → prop → persisted.
         * The resolved value is cached so the Home tick does not query the
         * property bus every frame.
         */
        public fun getCurrentPowerMode(): String {
            if (currentPowercfg.isNotEmpty()) return currentPowercfg
            val resolved = ModeState.resolve(
                "", PropsUtils.getProp("vtools.powercfg"), savedMode()
            )
            if (resolved.isNotEmpty()) currentPowercfg = resolved
            return resolved
        }

        /** Home/notification label; engine OFF wins over the remembered mode. */
        public fun getCurrentPowerModeName(): String =
            ModeState.displayName(
                getCurrentPowerMode(),
                ProfileController.isEngineOff(Scene.context)
            ) { getModName(it) }

        public fun getCurrentPowermodeApp(): String {
            if (!currentPowercfgApp.isEmpty()) return currentPowercfgApp
            return PropsUtils.getProp("vtools.powercfg_app")
        }
    }

    internal fun getModIcon(mode: String): Int {
        when (mode) {
            POWERSAVE -> return R.drawable.p1
            BALANCE -> return R.drawable.p2
            PERFORMANCE -> return R.drawable.p3
            FAST -> return R.drawable.p4
            else -> return R.drawable.p3
        }
    }

    internal fun getModImage(mode: String): Int {
        return when (mode) {
            POWERSAVE -> R.drawable.shortcut_p1
            BALANCE -> R.drawable.shortcut_p2
            PERFORMANCE -> R.drawable.shortcut_p3
            FAST -> R.drawable.shortcut_p4
            else -> R.drawable.shortcut_p3
        }
    }

    internal fun setCurrent(powerCfg: String, app: String): ModeSwitcher {
        setCurrentPowercfg(powerCfg)
        setCurrentPowercfgApp(app)
        return this
    }

    internal fun setCurrentPowercfg(powerCfg: String): ModeSwitcher {
        currentPowercfg = powerCfg
        PropsUtils.setPorp("vtools.powercfg", powerCfg)
        if (powerCfg.isNotEmpty()) {
            // Props are volatile; persist the last mode for boot re-apply.
            Scene.context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                .edit().putString(SpfConfig.GLOBAL_SPF_LAST_MODE, powerCfg).apply()
        }
        return this
    }

    internal fun setCurrentPowercfgApp(app: String): ModeSwitcher {
        currentPowercfgApp = app
        PropsUtils.setPorp("vtools.powercfg_app", app)
        return this
    }

    private fun keepShellExec(cmd: String) {
        KeepShellPublic.secondaryKeepShell.doCmdSync(cmd)
    }

    /**
     * Applies the source-specific init block. Skipped entirely while the
     * profile engine is OFF.
     */
    /**
     * Applies the source init block (never the mode itself — callers own the
     * ordering). init and profiles overlap on boost/sched/core_ctl keys, so a
     * mode must be applied afterwards; this used to run on every Tuner visit
     * and *cleared* the active mode instead.
     */
    internal fun initPowerCfg(): ModeSwitcher {
        synchronized(initLock) {
            ProfileController.syncCatalog(Scene.context)

            if (ProfileController.isEngineOff(Scene.context)) {
                inited = true
                provider = ""
                return this
            }

            // Monitor mode: root hilang -> tak ada jalur tulis; jangan sentuh
            // shell sama sekali (dulu spam su mati di tiap ganti app).
            if (!CheckRootStatus.isAvailable()) {
                inited = true
                provider = ""
                return this
            }

            val installer = CpuConfigInstaller()
            if (installer.outsideConfigInstalled()) {
                installer.configCodeVerify()
                keepShellExec("sh $OUTSIDE_POWER_CFG_PATH $INIT > /dev/null 2>&1")
                provider = PROVIDER_OUTSIDE
            } else {
                ProfileController.applyInit(Scene.context)
                provider = PROVIDER_ENGINE
            }
            inited = true
        }
        return this
    }

    /**
     * One-shot per process for UI/boot callers: init + re-apply the saved
     * mode. Idempotent and synchronized — the Tuner worker thread and UI
     * taps used to race here, which silently reset the active mode.
     */
    internal fun ensureReady() {
        synchronized(initLock) {
            if (ProfileController.isEngineOff(Scene.context)) {
                // Engine OFF: hand any per-app refresh override back (own change).
                runCatching { RefreshRateController.restore(Scene.context) }
                // Engine OFF: platform downscales must not be left behind.
                runCatching { DownscaleController.resetAll(Scene.context) }
                // Engine OFF: never leave charging paused.
                runCatching { BypassCharging.forceReset(Scene.context) }
                // Engine OFF: hand the opt-in extras back.
                runCatching { LoggingReduction.restore(Scene.context) }
                runCatching { KernelCrashGuard.restore(Scene.context) }
                runCatching { SfFramePacing.restore(Scene.context) }
                runCatching { IrqAffinity.restore(Scene.context) }
                runCatching { RootForegroundWatch.stop(Scene.context) }
                inited = true
                return
            }
            if (!CheckRootStatus.isAvailable()) {
                inited = true
                return
            }
            if (!inited || provider != currentProvider()) initPowerCfg()
            if (!savedModeRestored) {
                savedModeRestored = true
                restoreSavedMode()
            }
            // The saver may have toggled while the process was dead.
            BatterySaverMode.evaluate(Scene.context)
            // Charging may have started/stopped while the process was dead.
            BypassCharging.evaluate(Scene.context)
            // Opt-in extras follow the engine state.
            if (LoggingReduction.isEnabled(Scene.context)) LoggingReduction.apply(Scene.context)
            if (KernelCrashGuard.isEnabled(Scene.context)) KernelCrashGuard.apply(Scene.context)
            if (SfFramePacing.isEnabled(Scene.context)) SfFramePacing.apply(Scene.context)
            if (IrqAffinity.isEnabled(Scene.context)) IrqAffinity.apply(Scene.context)
            if (RootForegroundWatch.isEnabled(Scene.context)) RootForegroundWatch.start(Scene.context)
        }
    }

    /**
     * Boot / TRUE-OFF exit: init + re-apply the persisted mode + daemons.
     * Mode state (prop + pref) stays in sync through [setCurrentPowercfg].
     */
    internal fun applyBootState() {
        synchronized(initLock) {
            if (ProfileController.isEngineOff(Scene.context)) return
            if (!TrueOff.allowsWrite(Scene.context)) return
            if (!CheckRootStatus.isAvailable()) return
            if (!inited || provider != currentProvider()) initPowerCfg()
            if (!restoreSavedMode()) {
                DaemonController.ensureOn(Scene.context)
            }
            savedModeRestored = true
        }
    }

    private fun currentProvider(): String =
        if (CpuConfigInstaller().outsideConfigInstalled()) PROVIDER_OUTSIDE else PROVIDER_ENGINE

    /**
     * Re-applies the persisted mode through the active source.
     * @return true when a mode was applied (mode state synced too).
     */
    private fun restoreSavedMode(): Boolean {
        if (!TrueOff.allowsWrite(Scene.context)) return false
        if (ProfileController.isEngineOff(Scene.context)) return false
        if (!CheckRootStatus.isAvailable()) return false
        val mode = savedMode()
        if (mode.isEmpty()) return false
        return if (getCurrentSource() == SOURCE_OUTSIDE) {
            if (!inited || provider != PROVIDER_OUTSIDE) initPowerCfg()
            keepShellExec("sh $OUTSIDE_POWER_CFG_PATH '$mode' > /dev/null 2>&1")
            setCurrentPowercfg(mode)
            true
        } else {
            if (!inited || provider != PROVIDER_ENGINE) initPowerCfg()
            if (ProfileController.applyMode(Scene.context, mode)) {
                setCurrentPowercfg(mode)
                true
            } else {
                false
            }
        }
    }

    /** Switches mode. No-op for [IGONED] and while the engine is OFF. */
    private fun executeMode(mode: String, packageName: String): ModeSwitcher {
        if (mode == IGONED) return this

        if (ProfileController.isEngineOff(Scene.context)) {
            // Profiles OFF: device stays stock, only remember the requested mode.
            setCurrentPowercfg(mode)
            return this
        }

        // Monitor mode: remember the requested mode for the eventual restore,
        // but never touch the kernel.
        if (!CheckRootStatus.isAvailable()) {
            setCurrentPowercfg(mode)
            return this
        }

        when (getCurrentSource()) {
            SOURCE_OUTSIDE -> {
                if (!inited || provider != PROVIDER_OUTSIDE) initPowerCfg()
                val dynamic = Scene.getBoolean(
                    SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT
                )
                val strictMode = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
                val topApp = if (dynamic && strictMode) packageName else ""
                keepShellExec("export top_app=$topApp\nsh $OUTSIDE_POWER_CFG_PATH '$mode' > /dev/null 2>&1")
                setCurrentPowercfg(mode)
            }
            else -> {
                if (!inited || provider != PROVIDER_ENGINE) initPowerCfg()
                if (ProfileController.applyMode(Scene.context, mode)) {
                    setCurrentPowercfg(mode)
                } else {
                    Log.e("Scene", "$mode profile apply failed")
                }
            }
        }
        return this
    }

    internal fun executePowercfgMode(mode: String, app: String): ModeSwitcher {
        // TRUE OFF: no mode may be applied — not from UI, popup, tile or
        // app-switch. The request is dropped entirely (no state remembered).
        if (!TrueOff.allowsWrite(Scene.context)) {
            Log.i("Scene", "TRUE OFF: dropped mode switch '$mode'")
            return this
        }
        // Explicit user intent wins over the battery-saver overlay: drop it so
        // a later saver-OFF cannot restore a stale base mode.
        BatterySaverMode.clearOverlay(Scene.context)
        val targetApp = if (app != Scene.thisPackageName) app else ""
        // Sync the app prop BEFORE the apply: HwuiController resolves the
        // per-app layer from it (a late write resolved the previous app).
        setCurrentPowercfgApp(targetApp)
        executeMode(mode, targetApp)
        return this
    }

    /**
     * Applies a mode on behalf of the battery-saver overlay. Both the
     * powersave apply and the base-mode restore use this internal path, so the
     * overlay state survives; explicit user actions go through
     * [executePowercfgMode] and end it first.
     *
     * [keepSavedMode] keeps `GLOBAL_SPF_LAST_MODE` on the pre-overlay base
     * (the runtime prop/cache still reflect the overlay mode) so a reboot
     * while saver is ON can re-derive the overlay from the real base.
     */
    internal fun applyOverlayMode(mode: String, keepSavedMode: Boolean = false): ModeSwitcher {
        if (!TrueOff.allowsWrite(Scene.context)) return this
        if (ProfileController.isEngineOff(Scene.context)) return this
        if (!CheckRootStatus.isAvailable()) return this
        executeMode(mode, "")
        if (keepSavedMode) {
            val base = BatterySaverMode.baseMode(Scene.context)
            if (base.isNotEmpty()) {
                Scene.context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                    .edit().putString(SpfConfig.GLOBAL_SPF_LAST_MODE, base).apply()
            }
        }
        return this
    }

    /**
     * Configuration is ready when either the engine ships a tuning for this
     * platform or an external script is installed.
     */
    public fun modeConfigCompleted(): Boolean {
        val installer = CpuConfigInstaller()
        return installer.outsideConfigInstalled() || installer.dynamicSupport(Scene.context)
    }

    public fun clearInitedState() {
        inited = false
        provider = ""
        savedModeRestored = false
    }
}
