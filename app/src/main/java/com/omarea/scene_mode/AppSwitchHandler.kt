package com.omarea.scene_mode

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.basic.InputMethodApp
import com.omarea.library.basic.ScreenState
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig
import com.omarea.utils.CommonCmds
import com.omarea.vtools.AccessibilityScenceMode
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList
import com.omarea.scene_mode.options.BatterySaverFollow
import com.omarea.scene_mode.game.GameListStore
import com.omarea.scene_mode.game.GameProfileStore

/**
 *
 * Created by helloklf on 2016/10/1.
 */
@OptIn(DelicateCoroutinesApi::class)
class AppSwitchHandler(private var context: AccessibilityScenceMode, override val isAsync: Boolean = false) : ModeSwitcher(), IEventReceiver {
    private var lastPackage: String? = null
    private var lastModePackage: String? = "com.system.ui"
    private var lastMode = ""
    private var sceneBlackList = context.getSharedPreferences(SpfConfig.SCENE_BLACK_LIST, Context.MODE_PRIVATE)
    private val spfGlobal: SharedPreferences
        get() {
            return Scene.globalConfig
        }
    private var ignoredList = ArrayList<String>()
    // Mode captured when a game session starts; switched back when the game
    // leaves the foreground. The game whitelist (GameListStore) is the only
    // trigger for automatic mode switching now.
    private var gameBackupMode = ""
    private var firstMode = spfGlobal.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, BALANCE)
    private var screenOn = false
    private var lastScreenOnOff: Long = 0
    private var pendingSwitch: Runnable? = null

    //屏幕关闭后切换网络延迟（ms）
    private val SCREEN_OFF_SWITCH_NETWORK_DELAY: Long = 25000
    private var handler = Handler(Looper.getMainLooper())
    private var notifyHelper = AlwaysNotification(context, true)
    private val sceneMode = SceneMode.getNewInstance(context, SceneConfigStore(context))!!
    private var timer: Timer? = null
    private var powerSaveReceiver: BroadcastReceiver? = null
    private var screenState = ScreenState(context)

    /**
     * 更新设置
     */
    private fun updateConfig() {
        clearInitedState()
        lastMode = ""
        firstMode = spfGlobal.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, BALANCE)
        pendingSwitch?.let { handler.removeCallbacks(it) }
        pendingSwitch = null

        initConfig()
        notifyHelper.setNotify(true)
        stopTimer()
        startTimer()
    }

    private fun startTimer() {
        if (timer == null && screenOn && screenState.isScreenOn()) {
            if (screenOn) {
                timer = Timer(true).apply {
                    val interval = 6
                    scheduleAtFixedRate(object : TimerTask() {
                        private var ticks = 0
                        override fun run() {
                            updateModeNoitfy() // 耗电统计 定时更新通知显示

                            ticks += interval
                            ticks %= 60
                            if (ticks == 0) {
                                sceneMode.clearFreezeAppTimeLimit()
                                ProfileWatchdog.tick(context)
                            }
                        }
                    }, 0, interval * 1000L)
                }
            }
        }
    }

    private fun stopTimer() {
        try {
            if (timer != null) {
                timer!!.cancel()
                timer!!.purge()
                timer = null
            }
        } catch (ex: Exception) {
        }
    }

    /**
     * 屏幕关闭时执行
     */
    private fun onScreenOff() {
        if (!screenOn)
            return

        screenOn = false
        lastScreenOnOff = System.currentTimeMillis()
        sceneMode.onScreenOff()

        handler.postDelayed({
            onScreenOffCloseNetwork()
        }, SCREEN_OFF_SWITCH_NETWORK_DELAY + 1000)

        handler.postDelayed({
            if (!screenOn) {
                notifyHelper.hideNotify()
                stopTimer()

                // 息屏30秒后冻结偏见应用
                SceneMode.FreezeAppThread(context.applicationContext, true, 30).start()

                // 息屏后自动切换为省电模式
                if (lastMode.isNotEmpty()) {
                    val sleepMode = spfGlobal.getString(SpfConfig.GLOBAL_SPF_POWERCFG_SLEEP_MODE, POWERSAVE)
                    if (sleepMode != null && sleepMode != IGONED) {
                        toggleConfig(sleepMode, context.packageName)
                    }
                }
            }
        }, 10000)
    }

    /**
     * 屏幕关闭后 - 关闭网络
     */
    private fun onScreenOffCloseNetwork() {
        if (!screenOn) {
            if (System.currentTimeMillis() - lastScreenOnOff >= SCREEN_OFF_SWITCH_NETWORK_DELAY) {
                sceneMode.onScreenOffDelay()
                System.gc()
            }
        }
    }

    /**
     * 点亮屏幕且解锁后执行
     */
    private fun onScreenOn() {
        lastScreenOnOff = System.currentTimeMillis()
        BatterySaverFollow.check(context)

        handler.postDelayed({
            if (lastMode.isNotEmpty()) {
                lastPackage = null
                lastModePackage = null
                EventBus.publish(EventType.STATE_RESUME)
                // toggleConfig(lastMode, context.packageName)
                sceneMode.cancelFreezeAppThread()
            }
        }, 1000)
        sceneMode.onScreenOn()

        if (!screenOn) {
            screenOn = true
            startTimer() // 屏幕开启后开始定时更新通知
            updateModeNoitfy() // 屏幕点亮后更新通知
        }
    }

    /**
     * 更新通知
     */
    private fun updateModeNoitfy() {
        if (screenOn) {
            notifyHelper.notify()
        }
    }

    //自动切换模式
    private fun autoToggleMode(packageName: String?) {
        if (packageName != null && packageName != lastModePackage) {
            lastModePackage = packageName
            // Game-only automation: the whitelist decides whether the app is a
            // game, GameProfileStore resolves the per-game profile and the mode
            // from before the game returns when the game leaves.
            if (GameListStore.isGame(context, packageName)) {
                if (gameBackupMode.isEmpty()) {
                    val current = ModeSwitcher.getCurrentPowerMode()
                    gameBackupMode = if (current.isNotEmpty()) current else (firstMode ?: BALANCE)
                }
                val profile = GameProfileStore.modeFor(context, packageName)
                val target = if (profile == GameProfileStore.KEEP || profile.isEmpty()) {
                    // Keep the tuning, but still apply the game options for
                    // this package (priority, DND, preload, session).
                    ModeSwitcher.getCurrentPowerMode().ifEmpty { gameBackupMode }
                } else {
                    profile
                }
                if (target.isNotEmpty()) {
                    scheduleToggle(target, packageName)
                }
            } else if (gameBackupMode.isNotEmpty()) {
                val restore = gameBackupMode
                gameBackupMode = ""
                scheduleToggle(restore, packageName)
            }
            setCurrentPowercfgApp(packageName)
            updateModeNoitfy() // 应用改变后更新通知
        }
    }

    private fun toggleConfig(mode: String, packageName: String) {
        lastMode = mode
        executePowercfgMode(mode, packageName)
        // Re-evaluate the battery saver override after every real switch so
        // the MIUI broadcast gap does not have to wait for the watchdog and
        // the app-switch debounce never races the saver switch.
        BatterySaverFollow.check(context)
    }

    /**
     * Apply a mode after a short, cancellable grace period. Without it a quick
     * notification pull or task switch thrashes the kernel tunables back and
     * forth; with it only the settled foreground app is applied.
     */
    private fun scheduleToggle(mode: String, packageName: String) {
        pendingSwitch?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingSwitch = null
            if (lastModePackage == packageName) {
                toggleConfig(mode, packageName)
            }
        }
        pendingSwitch = runnable
        handler.postDelayed(runnable, 1500L)
    }
    //#endregion

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        when (eventType) {
            EventType.APP_SWITCH ->
                onFocusedAppChanged(GlobalStatus.lastPackageName)
            EventType.SCREEN_ON -> {
                onScreenOn()
            }
            EventType.SCREEN_OFF -> {
                if (ScreenState(context).isScreenLocked()) {
                    onScreenOff()
                }
            }
            EventType.SCENE_CONFIG -> {
                updateConfig()
                Scene.toast("The performance adjustment configuration parameters have been updated and will take effect the next time the application is switched!", Toast.LENGTH_SHORT)
            }
            EventType.SCENE_APP_CONFIG -> {
                data?.run {
                    if (containsKey("app")) {
                        sceneMode.updateAppConfig()
                    }
                }
            }
            else -> return
        }
    }

    override fun eventFilter(eventType: EventType): Boolean {
        return when (eventType) {
            EventType.APP_SWITCH, EventType.SCREEN_OFF, EventType.SCREEN_ON, EventType.SCENE_CONFIG, EventType.SCENE_APP_CONFIG -> true
            else -> false
        }
    }

    override fun onSubscribe() {

    }

    override fun onUnsubscribe() {
        sceneMode.clearState()
        notifyHelper.hideNotify()
        stopTimer()
        if (powerSaveReceiver != null) {
            context.unregisterReceiver(powerSaveReceiver)
            powerSaveReceiver = null
        }
        EventBus.unsubscribe(notifyHelper)
        EventBus.unsubscribe(this)
    }

    /**
     * 焦点应用改变
     */
    private fun onFocusedAppChanged(packageName: String) {
        if (!screenOn && screenState.isScreenOn()) {
            onScreenOn() // 如果切换应用时发现屏幕出于开启状态 而记录的状态是关闭，通知开启
        }

        if (lastPackage == packageName || ignoredList.contains(packageName) || sceneBlackList.contains(packageName)) return
        if (lastPackage == null) lastPackage = "com.android.systemui"

        autoToggleMode(packageName)
        sceneMode.onAppEnter(packageName)
        lastPackage = packageName
    }

    @SuppressLint("ApplySharedPref")
    private fun initConfig() {
        ignoredList.clear()
        // 添加强制忽略列表
        ignoredList.addAll(context.resources.getStringArray(R.array.powercfg_force_igoned))
        // 添加输入法到忽略列表
        ignoredList.addAll(InputMethodApp(context).getInputMethods())

        // 是否已经完成性能调节配置安装或自定义
        if (modeConfigCompleted()) {
            val installer = CpuConfigInstaller()
            if (installer.outsideConfigInstalled()) {
                installer.configCodeVerify()
            }
            initPowerCfg()
        }
        spfGlobal.edit().putString(SpfConfig.GLOBAL_SPF_POWERCFG, "").apply()
    }

    init {
        screenState = ScreenState(context)

        // If the service restarts while the screen is already on, ensure updates resume.
        screenOn = screenState.isScreenOn()
        if (screenOn) {
            lastScreenOnOff = System.currentTimeMillis()
            startTimer()
        }
        updateModeNoitfy() // 服务启动后 更新通知

        // 禁用SeLinux
        if (spfGlobal.getBoolean(SpfConfig.GLOBAL_SPF_DISABLE_ENFORCE, false)) {
            KeepShellPublic.doCmdSync(CommonCmds.DisableSELinux)
        }

        GlobalScope.launch(Dispatchers.IO) {
            initConfig()
        }

        EventBus.subscribe(notifyHelper)
        EventBus.subscribe(this)

        // Battery saver is a user explicit choice, so react to it as well.
        try {
            powerSaveReceiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    BatterySaverFollow.check(context)
                }
            }
            ContextCompat.registerReceiver(
                context,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ex: Exception) {
        }
    }
}
