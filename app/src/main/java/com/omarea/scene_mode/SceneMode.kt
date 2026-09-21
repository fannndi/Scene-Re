package com.omarea.scene_mode

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.*
import com.omarea.model.SceneConfigInfo
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig
import com.omarea.utils.ShellSafety
import com.omarea.vtools.AccessibilityScenceMode
import com.omarea.vtools.popup.FloatMonitorMini
import com.omarea.vtools.popup.FloatScreenRotation
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.ArrayList

class SceneMode private constructor(private val context: AccessibilityScenceMode, private var store: SceneConfigStore) {
    private var lastAppPackageName = "com.android.systemui"
    private var contentResolver: ContentResolver = context.contentResolver
    private var freezList = ArrayList<FreezeAppHistory>()
    private val config = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    // background timeout for biased apps
    private val freezeAppTimeLimit: Int
        get() {
            return config.getInt(SpfConfig.GLOBAL_SPF_FREEZE_TIME_LIMIT, 2) * 60 * 1000
        }

    // whether to freeze apps with the suspend command without hiding icons
    private val suspendMode: Boolean
        get() {
            return config.getBoolean(SpfConfig.GLOBAL_SPF_FREEZE_SUSPEND, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }

    private val floatScreenRotation = FloatScreenRotation(context)

    public fun cancelFreezeAppThread() {
        PropsUtils.setPorp("vtools.freeze_delay", "")
    }

    public class FreezeAppThread(
        private val context: Context,
        private val ignoreState: Boolean = false,
        private val delaySecond: Int = 0
    ) : Thread() {
        override fun run() {
            val globalConfig = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
            val launchedFreezeApp = if (ignoreState) null else getCurrentInstance()?.getLaunchedFreezeApp()
            val suspendMode = globalConfig.getBoolean(SpfConfig.GLOBAL_SPF_FREEZE_SUSPEND, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            val targetApps = ArrayList<String>()
            for (item in SceneConfigStore(context).freezeAppList) {
                if (launchedFreezeApp == null || !launchedFreezeApp.contains(item)) {
                    targetApps.add(item)
                }
            }
            if (targetApps.size > 0) {
                val cmds = StringBuilder("freeze_apps=\"")
                targetApps.forEach {
                    cmds.append("${it}\n")
                }
                cmds.append("\"\n")

                val writeSuccess = FileWrite.writePrivateFile(
                        cmds.toString().toByteArray(Charset.defaultCharset()),
                        "freeze_apps.sh",
                        context)
                val mode = if (suspendMode) "suspend" else "disable"
                val apps = if (writeSuccess) FileWrite.getPrivateFilePath(context, "freeze_apps.sh") else  null
                val executor = FileWrite.writePrivateShellFile("addin/freeze_executor.sh", "freeze_executor.sh", context)

                if (executor != null && apps != null) {
                    val delay = if (delaySecond > 0) ("" + delaySecond) else ""
                    KeepShellPublic.doCmdSync("nohup $executor $mode $apps $delay >/dev/null 2>&1 &")
                }
            }
        }
    }

    companion object {

        @Volatile
        private var instance: SceneMode? = null

        // get current instance
        fun getCurrentInstance(): SceneMode? {
            return instance
        }

        // create a new instance
        fun getNewInstance(context: AccessibilityScenceMode, store: SceneConfigStore): SceneMode? {
            if (instance != null) {
                instance?.clearState()
            }
            instance = SceneMode(context, store)
            return instance!!
        }

        fun suspendApp(app: String) {
            if (app.equals("com.android.vending")) {
                GAppsUtilis().disable(KeepShellPublic.secondaryKeepShell);
            } else if (ShellSafety.isValidPackageName(app)) {
                val packageArg = ShellSafety.quote(app)
                KeepShellPublic.doCmdSync("pm suspend $packageArg\nam force-stop $packageArg || am kill current $packageArg")
            }
        }

        fun freezeApp(app: String) {
            if (app.equals("com.android.vending")) {
                GAppsUtilis().disable(KeepShellPublic.secondaryKeepShell);
            } else if (ShellSafety.isValidPackageName(app)) {
                KeepShellPublic.doCmdSync("pm disable ${ShellSafety.quote(app)}")
            }
        }

        fun unfreezeApp(app: String) {
            if (!ShellSafety.isValidPackageName(app)) {
                return
            }
            getCurrentInstance()?.setFreezeAppLeaveTime(app)

            if (app.equals("com.android.vending")) {
                GAppsUtilis().enable(KeepShellPublic.secondaryKeepShell);
            } else {
                val packageArg = ShellSafety.quote(app)
                KeepShellPublic.doCmdSync("pm unsuspend $packageArg\npm enable $packageArg")
            }
        }
    }

    class FreezeAppHistory {
        var startTime: Long = 0
        var leaveTime: Long = 0
        var packageName: String = ""
    }


    fun getLaunchedFreezeApp(): List<String> {
        val apps = ArrayList<String>().apply {
            addAll(freezList.map { it.packageName })
        }
        val configList = SceneConfigStore(context).freezeAppList
        context.getForegroundApps().forEach {
            if (configList.contains(it) && !apps.contains(it)) {
                apps.add(it)
                setFreezeAppStartTime(it)
            }
        }
        return apps
    }

    fun setFreezeAppLeaveTime(packageName: String) {
        val currentHistory = removeFreezeAppHistory(packageName)

        val history = if (currentHistory != null) currentHistory else FreezeAppHistory()
        history.leaveTime = System.currentTimeMillis()
        history.packageName = packageName

        freezList.add(history)
    }

    fun setFreezeAppStartTime(packageName: String) {
        removeFreezeAppHistory(packageName)

        val history = FreezeAppHistory()
        history.startTime = System.currentTimeMillis()
        history.leaveTime = -1
        history.packageName = packageName

        freezList.add(history)
    }

    fun removeFreezeAppHistory(packageName: String): FreezeAppHistory? {
        for (it in freezList) {
            if (it.packageName == packageName) {
                freezList.remove(it)
                return it
            }
        }
        return null
    }

    // freeze biased apps whose background timeout has expired
    fun clearFreezeAppTimeLimit() {
        val freezAppTimeLimit = this.freezeAppTimeLimit
        if (freezAppTimeLimit > 0) {
            val currentTime = System.currentTimeMillis()
            val targetApps = freezList.filter {
                it.leaveTime > -1 && currentTime - it.leaveTime > freezAppTimeLimit && it.packageName != lastAppPackageName
            }
            if (targetApps.isNotEmpty()) {
                val foregroundApps = context.getForegroundApps()
                targetApps.forEach {
                    if(!foregroundApps.contains(it.packageName)) {
                        freezeApp(it)
                    }
                }
            }
        }
    }

    // freeze the specified app
    fun freezeApp(app: FreezeAppHistory) {
        val currentAppConfig = store.getAppConfig(app.packageName)
        if (currentAppConfig.freeze) {
            if (suspendMode) {
                suspendApp(app.packageName)
            } else {
                freezeApp(app.packageName)
            }
        }
        freezList.remove(app)
    }

    var brightnessMode = -1;
    var screenBrightness = -1;
    var currentSceneConfig: SceneConfigInfo? = null

    // back up brightness settings
    private fun backupBrightnessState(): Int {
        if (brightnessMode == -1) {
            try {
                brightnessMode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                screenBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Settings.SettingNotFoundException) {
                e.printStackTrace()
            }
        }
        return brightnessMode
    }

    // restore brightness settings
    private fun resumeBrightnessState() {
        try {
            val modeBackup = brightnessMode;
            if (modeBackup > -1) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, modeBackup)
                contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), null)
            }
            brightnessMode = -1
            if (screenBrightness > -1 && modeBackup == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, screenBrightness)
                contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
            }
            screenBrightness = -1
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // disable auto brightness
    private fun autoLightOff(lightValue: Int = -1): Boolean {
        try {
            if (Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)) {
                contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), null)
            } else {
                return false
            }

            if (lightValue > -1 && Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, lightValue)) {
                contentResolver.notifyChange(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), null)
            } else {
                return false
            }
        } catch (ex: Exception) {
            return false
        }
        return true
    }

    // set screen rotation
    private fun updateScreenRotation() {
        currentSceneConfig?.run {
            floatScreenRotation.update(this)
        }
    }

    /**
     * When a notification is received
     * @return whether to intercept
     */
    fun onNotificationPosted(): Boolean {
        if (currentSceneConfig != null) {
            return currentSceneConfig!!.disNotice
        }
        return false
    }

    private var locationMode = "none"
    // whether to hide the mini performance monitor when leaving the app
    private var hideMonitorOnLeave = false

    private fun getLocationProvidersAllowed(): String? {
        @Suppress("DEPRECATION")
        return Settings.Secure.getString(contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED)
    }

    // back up location settings
    private fun backupLocationModeState() {
        if (locationMode == "none") {
            locationMode = getLocationProvidersAllowed() ?: "none"
        }
    }

    // restore location settings
    private fun restoreLocationModeState() {
        if (locationMode != "none") {
            if (!locationMode.contains("gps")) {
                if (locationMode.contains("network")) {
                    LocationHelper().disableGPS()
                } else {
                    LocationHelper().disableLocation()
                }
            }
            locationMode = "none"
        }
    }

    private var headsup = -1

    // back up floating notifications
    private fun backupHeadUp() {
        if (headsup < 0) {
            try {
                headsup = Settings.Global.getInt(contentResolver, "heads_up_notifications_enabled")
            } catch (ex: Exception) {
            }
        }
    }

    // restore floating notifications
    private fun restoreHeaddUp() {
        try {
            if (headsup > -1) {
                Settings.Global.putInt(contentResolver, "heads_up_notifications_enabled", headsup)
                contentResolver.notifyChange(Settings.System.getUriFor("heads_up_notifications_enabled"), null)
                headsup = -1
            }
        } catch (ex: Exception) {

        }
    }

    /**
     * When leaving the app
     */
    fun onAppLeave(sceneConfigInfo: SceneConfigInfo) {
        // record the last active time when leaving a biased app
        if (sceneConfigInfo.freeze) {
            setFreezeAppLeaveTime(sceneConfigInfo.packageName)
        }

        if (sceneConfigInfo.aloneLight) {
            // independent brightness: record the last brightness value
            try {
                val light = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                if (light != sceneConfigInfo.aloneLightValue) {
                    sceneConfigInfo.aloneLightValue = light
                    store.setAppConfig(sceneConfigInfo)
                }
            } catch (ex: java.lang.Exception) {
            }
        }

        // experimental feature (automatic cgroup/memory config)
        if (sceneConfigInfo.fgCGroupMem != sceneConfigInfo.bgCGroupMem) {
                CGroupMemoryUtlis(Scene.context).run {
                    if (isSupported) {
                        if (sceneConfigInfo.bgCGroupMem?.isNotEmpty() == true) {
                            setGroupAutoDelay(this, sceneConfigInfo.packageName!!, sceneConfigInfo.bgCGroupMem)
                            // Scene.toast(sceneConfigInfo.packageName!! + "exited, cgroup set to [${sceneConfigInfo.bgCGroupMem}]\n(Scene experimental feature)")
                        } else {
                            setGroup(sceneConfigInfo.packageName!!, "")
                            // Scene.toast(sceneConfigInfo.packageName!! + "exited, cgroup set to [/]\n(Scene experimental feature)")
                        }
                    } else {
                        Scene.toast("Your kernel doesn't support cgroup settings! \n(Scene experimental feature)")
                    }
                }
        }

        // KeepShellPublic.doCmdSync("pm suspend ${sceneConfigInfo.packageName}")
    }

    /**
     * Foreground app switch
     */
    fun onAppEnter(packageName: String, forceUpdateConfig: Boolean = false) {
        if (lastAppPackageName == packageName && !forceUpdateConfig) {
            return
        }
        synchronized(this) {
            try {
                lastAppPackageName = packageName
                if (currentSceneConfig != null && currentSceneConfig?.packageName != packageName) {
                    onAppLeave(currentSceneConfig!!)
                }

                currentSceneConfig = store.getAppConfig(packageName)
                if (currentSceneConfig == null) {
                    restoreLocationModeState()
                    resumeBrightnessState()
                    restoreHeaddUp()
                    stoptMemoryDynamicBooster()
                } else {
                    if (currentSceneConfig!!.aloneLight) {
                        backupBrightnessState()
                        autoLightOff(currentSceneConfig!!.aloneLightValue)
                    } else {
                        resumeBrightnessState()
                    }

                    if (currentSceneConfig!!.showMonitor) {
                        if (FloatMonitorMini.show != true) {
                            Scene.post {
                                hideMonitorOnLeave = FloatMonitorMini(context).showPopupWindow()
                            }
                        }
                    } else if (hideMonitorOnLeave) {
                        Scene.post {
                            FloatMonitorMini(context).hidePopupWindow()
                        }
                        hideMonitorOnLeave = false
                    }

                    if (currentSceneConfig!!.gpsOn) {
                        backupLocationModeState()
                        val mode = getLocationProvidersAllowed() ?: ""
                        if (!mode.contains("gps")) {
                            LocationHelper().enableGPS()
                        }
                    } else {
                        restoreLocationModeState()
                    }

                    if (currentSceneConfig!!.disNotice) {
                        try {
                            val mode = Settings.Global.getInt(contentResolver, "heads_up_notifications_enabled")
                            backupHeadUp()
                            if (mode != 0) {
                                Settings.Global.putInt(contentResolver, "heads_up_notifications_enabled", 0)
                                contentResolver.notifyChange(Settings.System.getUriFor("heads_up_notifications_enabled"), null)
                            }
                        } catch (ex: Exception) {
                        }
                    } else {
                        restoreHeaddUp()
                    }

                    if (currentSceneConfig!!.freeze) {
                        setFreezeAppStartTime(packageName)
                    }

                    // experimental feature (automatic cgroup/memory config)
                    if (currentSceneConfig?.fgCGroupMem?.isNotEmpty() == true || currentSceneConfig?.bgCGroupMem != currentSceneConfig?.fgCGroupMem) {
                        CGroupMemoryUtlis(Scene.context).run {
                            if (isSupported) {
                                setGroup(currentSceneConfig!!.packageName!!, currentSceneConfig!!.fgCGroupMem)
                                // Scene.toast("entered " + currentSceneConfig!!.packageName!! + ", cgroup set to [${currentSceneConfig!!.fgCGroupMem}]\n(Scene experimental feature)")
                            } else {
                                Scene.toast("Your kernel doesn't support cgroup settings! \n(Scene experimental feature)")
                            }
                        }
                    }

                    // if (packageName.equals("com.miHoYo.Yuanshen") || packageName.equals("com.tencent.tmgp.sgame")) {
                    if (currentSceneConfig?.dynamicBoostMem == true) {
                        startMemoryDynamicBooster()
                    } else {
                        stoptMemoryDynamicBooster()
                    }
                }

                updateScreenRotation()
            } catch (ex: Exception) {
                Log.e(">>>>", "" + ex.message)
            }
        }
    }

    private fun setGroupAutoDelay(util: CGroupMemoryUtlis, app: String, mode: String) {
        if (mode == "scene_bg") {
            Scene.postDelayed({
                if (currentSceneConfig?.packageName != app) {
                    util.setGroup(app, mode)
                }
            }, 3000)
        } else if (mode == "scene_cache") {
            Scene.postDelayed({
                if (currentSceneConfig?.packageName != app) {
                    util.setGroup(app, mode)
                }
            }, 8000)
        } else {
            util.setGroup(app, mode)
        }
    }

    private var am: ActivityManager? = null
    private var memoryWatchTimer: Timer? = null
    private var memoryBoostUtils: MemoryBoostUtils? = null
    fun startMemoryDynamicBooster() {
        // get running memory info
        if (am == null) {
            am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        }
        val info = ActivityManager.MemoryInfo()

        memoryWatchTimer = Timer().apply {
            if (memoryBoostUtils == null) {
                memoryBoostUtils = MemoryBoostUtils(context)
            }
            schedule(object : TimerTask() {
                override fun run() {
                    am?.getMemoryInfo(info)
                    val total = info.totalMem
                    val availMem = info.availMem
                    val raito = availMem.toDouble() / total

                    if (raito < 0.16 && raito > 0.0) {
                        memoryBoostUtils?.forceKswapd(0)
                    }
                }
            }, 3000, 10000)
        }
    }

    private fun stoptMemoryDynamicBooster() {
        if (memoryWatchTimer != null) {
            memoryWatchTimer?.cancel()
            memoryWatchTimer = null
        }
    }

    fun updateAppConfig() {
        if (!lastAppPackageName.isEmpty()) {
            onAppEnter(lastAppPackageName, true)
        }
    }

    fun clearState() {
        lastAppPackageName = "com.android.systemui"
        restoreLocationModeState()
        resumeBrightnessState()
        currentSceneConfig = null
        floatScreenRotation.remove()
        instance = null
    }

    fun onScreenOn() {
        // restore auto-rotate setting after screen on
        updateScreenRotation()
    }

    fun onScreenOff() {
        // pause screen rotation changes while the screen is off
        floatScreenRotation.remove()
    }


    fun onScreenOffDelay() {
    }
}
