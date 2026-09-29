package com.omarea.runtime

import android.content.Context
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.engine.ProfileController
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
                FAST -> return "Custom"
                BALANCE -> return "Balanced"
                IGONED -> return "Maintain status"
                "" -> return "Global Default"
                else -> return "Unknown"
            }
        }

        private var currentPowercfg: String = ""
        private var currentPowercfgApp: String = ""

        public fun getCurrentPowerMode(): String {
            if (!currentPowercfg.isEmpty()) return currentPowercfg
            return PropsUtils.getProp("vtools.powercfg")
        }

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
    internal fun initPowerCfg(): ModeSwitcher {
        ProfileController.syncCatalog(Scene.context)

        if (ProfileController.isEngineOff(Scene.context)) {
            inited = true
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
        setCurrentPowercfg("")
        inited = true
        return this
    }

    /** Switches mode. No-op for [IGONED] and while the engine is OFF. */
    private fun executeMode(mode: String, packageName: String): ModeSwitcher {
        if (mode == IGONED) return this

        if (ProfileController.isEngineOff(Scene.context)) {
            // Profiles OFF: device stays stock, only remember the requested mode.
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
        if (app != Scene.thisPackageName) {
            executeMode(mode, app)
            setCurrentPowercfgApp(app)
        } else {
            executeMode(mode, "")
            setCurrentPowercfgApp("")
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
    }
}
