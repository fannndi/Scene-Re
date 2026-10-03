package com.omarea.runtime

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import com.omarea.Scene
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
    private val config = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)


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

    }

    var currentSceneConfig: SceneConfigInfo? = null

    // 是否需要在离开应用时隐藏迷你性能监视器
    private var hideMonitorOnLeave = false

    /**
     * 从应用离开时
     */
    fun onAppLeave(sceneConfigInfo: SceneConfigInfo) {
        // TRUE OFF: no per-app scene control.
        if (!TrueOff.allowsWrite(context)) return
        // 离开偏见应用时，记录偏见应用最后活动时间

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
        // TRUE OFF: no per-app scene control.
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
