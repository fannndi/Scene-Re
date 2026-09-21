package com.omarea.scene_mode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.PropsUtils
import com.omarea.store.CpuConfigStorage
import com.omarea.store.SpfConfig
import com.omarea.utils.ShellSafety
import com.omarea.vtools.R

/**
 * Created by Hello on 2018/06/03.
 */

open class ModeSwitcher {
    companion object {
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
        // config file installed in /data
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

        // whether built-in config auto update is complete (when using Scene built-in configs, install config before each scheduling switch)
        private var innerConfigUpdated = false

        const val OUTSIDE_POWER_CFG_PATH = "/data/powercfg.sh"
        const val OUTSIDE_POWER_CFG_BASE = "/data/powercfg-base.sh"

        internal var POWERSAVE = "powersave"
        internal var PERFORMANCE = "performance"
        internal var FAST = "fast"
        internal var BALANCE = "balance"
        internal var IGONED = "igoned"
        internal var DEFAULT = BALANCE
        private var INIT = "init"

        internal fun getModName(mode: String): String {
            when (mode) {
                POWERSAVE -> return "Power Save"
                PERFORMANCE -> return "Performance"
                FAST -> return "Speed Mode"
                BALANCE -> return "Balanced"
                IGONED -> return "Maintain status"
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

    // init
    // TODO: figure out when to clear the cache
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
            keepShellExec("sh $configProvider $INIT > /dev/null 2>&1")
            setCurrentPowercfg("")

            inited = true
        }
        return this
    }

    // switch mode
    private fun executeMode(mode: String, packageName: String): ModeSwitcher {
        // TODO: handle mode == IGONED
        if (mode != IGONED) {
            val source = getCurrentSource()
            when (source) {
                SOURCE_SCENE_CUSTOM -> {
                    val cpuConfigStorage = CpuConfigStorage(Scene.context)
                    if (cpuConfigStorage.exists(mode)) {
                        cpuConfigStorage.applyCpuConfig(mode)
                        setCurrentPowercfg(mode)
                    } else {
                        Log.e("Scene", "" + mode + "Profile lost!")
                    }
                }
                SOURCE_OUTSIDE -> {
                    if (!inited || lastInitProvider != PROVIDER_OUTSIDE) {
                        initPowerCfg()
                    }

                    if (configProvider.isNotEmpty()) {
                        val dynamic = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
                        val strictMode = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
                        if (dynamic && strictMode) {
                            keepShellExec(
                                    "export top_app=${ShellSafety.quote(packageName)}\n" +
                                            "sh $configProvider '$mode' > /dev/null 2>&1"
                            )
                        } else {
                            keepShellExec(
                                    "export top_app=\n" +
                                        "sh $configProvider '$mode' > /dev/null 2>&1"
                            )
                        }
                        setCurrentPowercfg(mode)
                    } else {
                        Log.e("Scene", "" + mode + "Profile lost!")
                    }
                }
                else -> {
                    if (!inited || lastInitProvider != PROVIDER_INSIDE) {
                        initPowerCfg()
                    }

                    if (configProvider.isNotEmpty()) {
                        val dynamic = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT)
                        val strictMode = Scene.getBoolean(SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_STRICT, false)
                        if (dynamic && strictMode) {
                            val currentTime = SystemClock.elapsedRealtime()
                            keepShellExec(
                                    "export top_app=${ShellSafety.quote(packageName)}\n" +
                                            "sh $configProvider '$mode' 'task$currentTime' > /dev/null 2>&1"
                            )
                        } else {
                            keepShellExec(
                                    "export top_app=''\n" +
                                            "sh $configProvider '$mode' > /dev/null 2>&1"
                            )
                        }
                        setCurrentPowercfg(mode)
                    } else {
                        Log.e("Scene", "" + mode + "Profile lost!")
                    }
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

    // whether the specified mode has been customized
    public fun modeReplaced(mode: String): Boolean {
        return CpuConfigStorage(Scene.context).exists(mode)
    }

    // whether all four modes are configured
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

    // whether all modes have been customized
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
