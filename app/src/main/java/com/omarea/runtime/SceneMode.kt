package com.omarea.runtime

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.engine.TweakCommands
import com.omarea.util.*
import com.omarea.data.SceneConfigInfo
import com.omarea.data.SceneConfigStore
import com.omarea.data.SpfConfig
import com.omarea.runtime.AccessibilityScenceMode
import com.omarea.ui.popup.FloatMonitorMini
import java.util.*
import kotlin.collections.ArrayList

class SceneMode private constructor(private val context: AccessibilityScenceMode, private var store: SceneConfigStore) {
    private var lastAppPackageName = "com.android.systemui"
    private var contentResolver: ContentResolver = context.contentResolver
    private var freezList = ArrayList<FreezeAppHistory>()
    private val config = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    // 偏见应用后台超时时间
    private val freezeAppTimeLimit: Int
        get() {
            return config.getInt(SpfConfig.GLOBAL_SPF_FREEZE_TIME_LIMIT, 2) * 60 * 1000
        }

    // 是否使用suspend命令冻结应用，不隐藏图标
    private val suspendMode: Boolean
        get() {
            return config.getBoolean(SpfConfig.GLOBAL_SPF_FREEZE_SUSPEND, Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        }


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
            if (targetApps.isEmpty()) {
                return
            }

            // Kotlin port of the legacy freeze_executor addin: same prop-token delay so
            // cancelFreezeAppThread() cancels a pending freeze exactly as before.
            if (delaySecond > 0) {
                val uuid = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                PropsUtils.setPorp("vtools.freeze_delay", uuid)
                try {
                    sleep(delaySecond * 1000L)
                } catch (ex: InterruptedException) {
                    return
                }
                if (PropsUtils.getProp("vtools.freeze_delay") != uuid) {
                    return // superseded or cancelled
                }
            }

            for (app in targetApps) {
                if (suspendMode) {
                    suspendApp(app)
                } else {
                    freezeApp(app)
                }
            }
        }
    }

    companion object {

        @Volatile
        private var instance: SceneMode? = null

        // 获取当前实例
        fun getCurrentInstance(): SceneMode? {
            return instance
        }

        // 创建一个新实例
        fun getNewInstance(context: AccessibilityScenceMode, store: SceneConfigStore): SceneMode? {
            if (instance != null) {
                instance?.clearState()
            }
            instance = SceneMode(context, store)
            return instance!!
        }

        fun suspendApp(app: String) {
            // TRUE OFF: app freezing is control — blocked. Unfreezing stays
            // allowed so nothing can remain stuck while OFF.
            if (!TrueOff.allowsWrite(com.omarea.Scene.context)) return
            if (!CheckRootStatus.isAvailable()) return
            if (app.equals("com.android.vending")) {
                GAppsUtilis().disable(KeepShellPublic.secondaryKeepShell);
                TweakCommands.gappsPackages.forEach { PmStateJournal.record(com.omarea.Scene.context, "disable", it) }
            } else {
                KeepShellPublic.doCmdSync("pm suspend ${app}\nam force-stop ${app} || am kill current ${app}")
                PmStateJournal.record(com.omarea.Scene.context, "suspend", app)
            }
        }

        fun freezeApp(app: String) {
            // TRUE OFF: app freezing is control — blocked.
            if (!TrueOff.allowsWrite(com.omarea.Scene.context)) return
            if (!CheckRootStatus.isAvailable()) return
            if (app.equals("com.android.vending")) {
                GAppsUtilis().disable(KeepShellPublic.secondaryKeepShell);
                TweakCommands.gappsPackages.forEach { PmStateJournal.record(com.omarea.Scene.context, "disable", it) }
            } else {
                KeepShellPublic.doCmdSync("pm disable ${app}")
                PmStateJournal.record(com.omarea.Scene.context, "disable", app)
            }
        }

        fun unfreezeApp(app: String) {
            getCurrentInstance()?.setFreezeAppLeaveTime(app)

            if (app.equals("com.android.vending")) {
                GAppsUtilis().enable(KeepShellPublic.secondaryKeepShell);
                TweakCommands.gappsPackages.forEach { PmStateJournal.clear(com.omarea.Scene.context, "disable", it) }
            } else {
                KeepShellPublic.doCmdSync("pm unsuspend ${app}\npm enable ${app}")
                PmStateJournal.clearPackage(com.omarea.Scene.context, app)
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

    // 冻结已经后台超时的偏见应用
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

    // 冻结指定应用
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

    var currentSceneConfig: SceneConfigInfo? = null

    // 是否需要在离开应用时隐藏迷你性能监视器
    private var hideMonitorOnLeave = false

    /**
     * 从应用离开时
     */
    fun onAppLeave(sceneConfigInfo: SceneConfigInfo) {
        // TRUE OFF: no per-app scene control (brightness/freeze/location...).
        if (!TrueOff.allowsWrite(context)) return
        // 离开偏见应用时，记录偏见应用最后活动时间
        if (sceneConfigInfo.freeze) {
            setFreezeAppLeaveTime(sceneConfigInfo.packageName)
        }

        // 实验性新特性（cgroup/memory自动配置）
        if (sceneConfigInfo.fgCGroupMem != sceneConfigInfo.bgCGroupMem) {
                CGroupMemoryUtlis(Scene.context).run {
                    if (isSupported) {
                        if (sceneConfigInfo.bgCGroupMem?.isNotEmpty() == true) {
                            setGroupAutoDelay(this, sceneConfigInfo.packageName!!, sceneConfigInfo.bgCGroupMem)
                            // Scene.toast(sceneConfigInfo.packageName!! + "退出，cgroup调为[${sceneConfigInfo.bgCGroupMem}]\n(Scene试验性功能)")
                        } else {
                            setGroup(sceneConfigInfo.packageName!!, "")
                            // Scene.toast(sceneConfigInfo.packageName!! + "退出，cgroup调为[/]\n(Scene试验性功能)")
                        }
                    } else {
                        Scene.toast("Your kernel doesn't support cgroup settings! \n(Scene experimental feature)")
                    }
                }
        }

        // KeepShellPublic.doCmdSync("pm suspend ${sceneConfigInfo.packageName}")
    }

    /**
     * 前台应用切换
     */
    fun onAppEnter(packageName: String, forceUpdateConfig: Boolean = false) {
        // TRUE OFF: no per-app scene control (brightness/freeze/location...).
        if (!TrueOff.allowsWrite(context)) return
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
                    stoptMemoryDynamicBooster()
                } else {
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

                    if (currentSceneConfig!!.freeze) {
                        setFreezeAppStartTime(packageName)
                    }

                    // 实验性新特性（cgroup/memory自动配置）
                    if (currentSceneConfig?.fgCGroupMem?.isNotEmpty() == true || currentSceneConfig?.bgCGroupMem != currentSceneConfig?.fgCGroupMem) {
                        CGroupMemoryUtlis(Scene.context).run {
                            if (isSupported) {
                                setGroup(currentSceneConfig!!.packageName!!, currentSceneConfig!!.fgCGroupMem)
                                // Scene.toast("进入" + currentSceneConfig!!.packageName!! + "，cgroup调为[${currentSceneConfig!!.fgCGroupMem}]\n(Scene试验性功能)")
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
    private var swapUtils: SwapUtils? = null
    fun startMemoryDynamicBooster() {
        //获取运行内存的信息
        if (am == null) {
            am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        }
        val info = ActivityManager.MemoryInfo()

        memoryWatchTimer = Timer().apply {
            if (swapUtils == null) {
                swapUtils = SwapUtils(context)
            }
            schedule(object : TimerTask() {
                override fun run() {
                    am?.getMemoryInfo(info)
                    val total = info.totalMem
                    val availMem = info.availMem
                    val raito = availMem.toDouble() / total

                    if (raito < 0.16 && raito > 0.0) {
                        swapUtils?.forceKswapd(0)
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
        currentSceneConfig = null
        instance = null
    }
}
