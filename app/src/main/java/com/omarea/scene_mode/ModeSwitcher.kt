package com.omarea.scene_mode

import android.content.Context
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellEscape
import com.omarea.library.shell.PropsUtils
import com.omarea.store.CpuConfigStorage
import com.omarea.store.SpfConfig
import com.omarea.utils.SceneLog
import com.omarea.vtools.R
import com.omarea.scene_mode.options.ProfileOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Hello on 2018/06/03.
 */

open class ModeSwitcher {
    companion object {
        /**
         * Single worker for every profile switch. The switch runs the powercfg
         * provider plus the options layer through the shared root shell, which
         * takes seconds; doing that on a UI thread freezes input and the system
         * shows "isn't responding". A single thread also serialises switches, so
         * two rapid taps cannot interleave kernel tunables.
         */
        private val switchExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scene-profile-switch").apply { isDaemon = true }
        }

        /** True while a switch is queued or running; used to coalesce taps. */
        private val switchRunning = AtomicBoolean(false)

        /** Newest requested switch; a running worker picks it up when it drains. */
        private val pendingSwitch = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
        /** Completion callback of the newest request (replaced by newer taps). */
        private val pendingCallback = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

        fun isSwitching(): Boolean = switchRunning.get()
        const val SOURCE_UNKNOWN = "UNKNOWN"
        const val SOURCE_SCENE_ACTIVE = "SOURCE_SCENE_ACTIVE"
        const val SOURCE_SCENE_CONSERVATIVE = "SOURCE_SCENE_CONSERVATIVE"
        const val SOURCE_SCENE_CUSTOM = "SOURCE_SCENE_CUSTOM"
        const val SOURCE_SCENE_IMPORT = "SOURCE_SCENE_IMPORT"
        const val SOURCE_SCENE_ONLINE = "SOURCE_SCENE_ONLINE"
        const val SOURCE_OUTSIDE = "SOURCE_OUTSIDE"
        const val SOURCE_OUTSIDE_UPERF = "SOURCE_OUTSIDE_UPERF"
        const val SOURCE_NONE = "SOURCE_NONE"
        // config file installed in the data directory
        const val PROVIDER_INSIDE = "PROVIDER_INSIDE"
        // config file installed under /data
        const val PROVIDER_OUTSIDE = "PROVIDER_OUTSIDE"
        const val PROVIDER_NONE = "PROVIDER_NONE"

        private var inited = false
        // last used config provider
        var lastInitProvider = PROVIDER_NONE
        // config provider file
        private var configProvider: String = ""

        fun getCurrentSource(): String {
            if (CpuConfigInstaller().outsideConfigInstalled()) {
                return SOURCE_OUTSIDE
            }
            val config = Scene.context
                    .getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                    .getString(SpfConfig.GLOBAL_SPF_PROFILE_SOURCE, SOURCE_UNKNOWN)
            if (config == SOURCE_SCENE_CUSTOM || CpuConfigInstaller().insideConfigInstalled()) {
                return config!!
            }
            return SOURCE_NONE
        }

        fun getCurrentSourceName(): String {
            val source = getCurrentSource()
            return (when (source) {
                "SOURCE_OUTSIDE" -> {
                    "External Sources"
                }
                "SOURCE_SCENE_CONSERVATIVE" -> {
                    "Scene-Classic"
                }
                "SOURCE_SCENE_ACTIVE" -> {
                    "Scene-Performance"
                }
                "SOURCE_SCENE_CUSTOM" -> {
                    "Custom"
                }
                "SOURCE_SCENE_IMPORT" -> {
                    "File Import"
                }
                "SOURCE_SCENE_ONLINE" -> {
                    "Online Download"
                }
                "SOURCE_NONE" -> {
                    "Undefined"
                }
                else -> {
                    "Unknown"
                }
            })
        }

        // whether the bundled config file was auto-updated (when the Scene-bundled config is used, install it before every profile switch)
        private var innerConfigUpdated = false

        const val OUTSIDE_POWER_CFG_PATH = "/data/powercfg.sh"
        const val OUTSIDE_POWER_CFG_BASE = "/data/powercfg-base.sh"

        internal var POWERSAVE = "powersave"
        internal var PERFORMANCE = "performance"
        internal var FAST = "fast"
        internal var BALANCE = "balance"
        internal var IGONED = "igoned"
        /** No profile at all: restore the boot-stock kernel/system state. */
        internal var OFF = "off"
        /** Bundled profile for light games (no performance scheduler). */
        internal var LIGHT = "light"
        internal var DEFAULT = BALANCE
        private var INIT = "init"

        internal fun getModName(mode: String): String {
            when (mode) {
                POWERSAVE -> return "Power Save"
                PERFORMANCE -> return "Performance"
                FAST -> return "Custom"
                BALANCE -> return "Balanced"
                LIGHT -> return "Light"
                IGONED -> return "Maintain status"
                OFF -> return "Off"
                "" -> return "Global Default"
                else -> return "Unknown"
            }
        }

        private var currentPowercfg: String = ""
        private var currentPowercfgApp: String = ""

        public fun getCurrentPowerMode(): String {
            if (!currentPowercfg.isEmpty()) {
                return currentPowercfg
            }
            return PropsUtils.getProp("vtools.powercfg")
        }

        public fun getCurrentPowermodeApp(): String {
            if (!currentPowercfgApp.isEmpty()) {
                return currentPowercfgApp
            }
            return PropsUtils.getProp("vtools.powercfg_app")
        }

        /**
         * UI-safe wrapper around [executePowercfgMode]. The switch (powercfg
         * provider + options layer, both multi-second shell work) runs on
         * [switchExecutor]; [onApplied] is delivered back on the main thread so
         * callers can refresh views. Use this from every main-thread caller -
         * click handlers, dialogs, accessibility callbacks and receivers - and
         * keep the synchronous method for callers that are already on a worker
         * (BootWorker, GameSessionTracker).
         *
         * Taps made while a switch runs are coalesced, not dropped: the newest
         * request replaces any pending one and is applied as soon as the current
         * switch finishes, so tapping "Power saving" then "Balance" a second
         * apart ends on Balance instead of silently staying on Power saving.
         */
        fun executePowercfgModeAsync(mode: String, app: String, onApplied: (() -> Unit)? = null) {
            pendingSwitch.set(mode to app)
            pendingCallback.set(onApplied)
            if (!switchRunning.compareAndSet(false, true)) {
                SceneLog.i("ModeSwitcher", "queued '$mode' behind the running switch")
                return
            }
            SceneLog.i("ModeSwitcher", "switch requested: mode='$mode' app='$app'")
            switchExecutor.execute { drainSwitchQueue() }
        }

        /** Applies every queued request, newest last; runs on [switchExecutor]. */
        private fun drainSwitchQueue() {
            try {
                while (true) {
                    val request = pendingSwitch.getAndSet(null) ?: break
                    val started = System.currentTimeMillis()
                    try {
                        ModeSwitcher().executePowercfgMode(request.first, request.second)
                        SceneLog.i(
                            "ModeSwitcher",
                            "applied '${request.first}' in ${System.currentTimeMillis() - started}ms"
                        )
                    } catch (ex: Throwable) {
                        SceneLog.e("ModeSwitcher", "profile switch to '${request.first}' failed", ex)
                    }
                }
            } finally {
                val callback = pendingCallback.getAndSet(null)
                if (callback != null) {
                    Scene.post { callback() }
                }
                switchRunning.set(false)
                // A request may have arrived between the empty read above and the
                // flag reset; take ownership again instead of losing it.
                if (pendingSwitch.get() != null && switchRunning.compareAndSet(false, true)) {
                    switchExecutor.execute { drainSwitchQueue() }
                }
            }
        }

        /** Reads the active mode without touching the UI thread's shell path. */
        fun currentPowerModeAsync(onReady: (String) -> Unit) {
            switchExecutor.execute {
                val mode = try {
                    getCurrentPowerMode()
                } catch (ex: Throwable) {
                    ""
                }
                Scene.post { onReady(mode) }
            }
        }

        /**
         * Runs [compute] on the profile-switch worker and delivers its result on
         * the main thread. Used by screens whose refresh reads root-shell state
         * (active mode, thermal disguise, config files): those reads must queue
         * behind a running switch instead of freezing input. [onResult] receives
         * null when [compute] threw.
         */
        fun <T : Any> computeAsync(compute: () -> T, onResult: (T?) -> Unit) {
            switchExecutor.execute {
                val value: T? = try {
                    compute()
                } catch (ex: Throwable) {
                    SceneLog.e("ModeSwitcher", "background read failed", ex)
                    null
                }
                Scene.post { onResult(value) }
            }
        }
    }

    internal fun getModIcon(mode: String): Int {
        when (mode) {
            POWERSAVE -> return R.drawable.p1
            BALANCE -> return R.drawable.p2
            PERFORMANCE -> return R.drawable.p3
            FAST -> return R.drawable.p4
            LIGHT -> return R.drawable.p2
            OFF -> return R.drawable.p1
            else -> return R.drawable.p3
        }
    }

    internal fun getModImage(mode: String): Int {
        return when (mode) {
            POWERSAVE -> R.drawable.shortcut_p1
            BALANCE -> R.drawable.shortcut_p2
            PERFORMANCE -> R.drawable.shortcut_p3
            FAST -> R.drawable.shortcut_p4
            LIGHT -> R.drawable.shortcut_p2
            OFF -> R.drawable.shortcut_p1
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
        PropsUtils.setProp("vtools.powercfg", powerCfg)
        return this
    }

    internal fun setCurrentPowercfgApp(app: String): ModeSwitcher {
        currentPowercfgApp = app
        PropsUtils.setProp("vtools.powercfg_app", app)
        return this
    }

    private fun keepShellExec(cmd: String) {
        KeepShellPublic.secondaryKeepShell.doCmdSync(cmd)
    }

    // init
    // TODO: decide when to clear the cache
    internal fun initPowerCfg(): ModeSwitcher {
        val installer = CpuConfigInstaller()
        if (installer.outsideConfigInstalled()) {
            configProvider = OUTSIDE_POWER_CFG_PATH
            installer.configCodeVerify()
            lastInitProvider = PROVIDER_OUTSIDE
        } else {
            if (!innerConfigUpdated) {
                installer.applyConfigNewVersion(Scene.context)
                innerConfigUpdated = true
            }
            lastInitProvider = PROVIDER_INSIDE
            configProvider = FileWrite.getPrivateFilePath(Scene.context, "powercfg.sh")
        }

        if (configProvider.isNotEmpty()) {
            keepShellExec("sh " + ShellEscape.quote(configProvider) + " $INIT > /dev/null 2>&1")
            setCurrentPowercfg("")

            inited = true
            ProfileOptions.apply(Scene.context, INIT)
        }
        return this
    }

    // switch mode
    private fun executeMode(mode: String, packageName: String): ModeSwitcher {
        if (mode == IGONED) {
            SceneLog.i("ModeSwitcher", "executeMode('$mode') skipped: ignored mode")
            return this
        }

        // Custom mode: a config saved from CPU Control wins over the bundled
        // profile; without one the bundled profile is the fallback.
        if (mode == FAST) {
            val custom = CpuConfigStorage(Scene.context)
            if (custom.exists(FAST)) {
                SceneLog.i("ModeSwitcher", "executeMode('$mode'): applying the saved CPU Control config")
                custom.applyCpuConfig(FAST)
                setCurrentPowercfg(mode)
                ProfileOptions.apply(Scene.context, mode, packageName)
                return this
            }
        }

        val source = getCurrentSource()
        SceneLog.i("ModeSwitcher", "executeMode('$mode'): source='$source' inited=$inited provider='$configProvider'")
        if (source == SOURCE_SCENE_CUSTOM && mode != OFF) {
            val cpuConfigStorage = CpuConfigStorage(Scene.context)
            if (cpuConfigStorage.exists(mode)) {
                cpuConfigStorage.applyCpuConfig(mode)
                setCurrentPowercfg(mode)
            } else {
                Log.e("Scene", "" + mode + "Profile lost!")
            }
        } else {
            // Script-backed modes (and Off, which restores the boot stock
            // state through the script) run the installed powercfg provider.
            val outside = source == SOURCE_OUTSIDE
            if (!inited || lastInitProvider != (if (outside) PROVIDER_OUTSIDE else PROVIDER_INSIDE)) {
                initPowerCfg()
            }
            if (configProvider.isNotEmpty()) {
                keepShellExec(
                        "export top_app=\n" +
                                "sh " + ShellEscape.quote(configProvider) + " '$mode' > /dev/null 2>&1"
                )
                setCurrentPowercfg(mode)
            } else {
                Log.e("Scene", "" + mode + "Profile lost!")
            }
        }

        // Off = system and kernel only; ProfileOptions.apply resets the layer
        // once per entry and keeps it silent while Off is selected.
        ProfileOptions.apply(Scene.context, mode, packageName)
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

    // whether the given mode has been customized
    public fun modeReplaced(mode: String): Boolean {
        return CpuConfigStorage(Scene.context).exists(mode)
    }

    // whether all four modes have been configured
    public fun modeConfigCompleted(): Boolean {
        if (CpuConfigInstaller().outsideConfigInstalled()) {
            return true
        } else {
            val source = getCurrentSource()
            when (source) {
                SOURCE_SCENE_CUSTOM -> {
                    return allModeReplaced()
                }
                SOURCE_SCENE_ACTIVE,
                SOURCE_SCENE_CONSERVATIVE,
                SOURCE_SCENE_IMPORT,
                SOURCE_SCENE_ONLINE -> {
                    return CpuConfigInstaller().insideConfigInstalled()
                }
            }
        }
        return false
    }

    // whether every mode has been customized
    public fun allModeReplaced(): Boolean {
        val storage = CpuConfigStorage(Scene.context)

        return storage.exists(POWERSAVE) &&
                storage.exists(BALANCE) &&
                storage.exists(PERFORMANCE) &&
                storage.exists(FAST)
    }

    public fun clearInitedState() {
        inited = false
    }
}
