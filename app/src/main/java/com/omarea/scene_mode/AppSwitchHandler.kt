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
import com.omarea.library.shell.PropsUtils
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
    // trigger for automatic mode switching now. Persisted in a prop so a
    // service restart in the middle of a game keeps the real backup.
    private var gameBackupMode = ""
    private val PROP_GAME_BACKUP = "vtools.scene.game.backup"
    private var firstMode = spfGlobal.getString(SpfConfig.GLOBAL_SPF_POWERCFG_FIRST_MODE, BALANCE)
    private var screenOn = false
    private var lastScreenOnOff: Long = 0
    private var pendingSwitch: Runnable? = null

    // network-switch delay after the screen turns off (ms)
    private val SCREEN_OFF_SWITCH_NETWORK_DELAY: Long = 25000
    private var handler = Handler(Looper.getMainLooper())
    private var notifyHelper = AlwaysNotification(context, true)
    private val sceneMode = SceneMode.getNewInstance(context, SceneConfigStore(context))!!
    private var timer: Timer? = null
    private var powerSaveReceiver: BroadcastReceiver? = null
    private var screenState = ScreenState(context)

    /**
     * Update the settings.
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
                            updateModeNoitfy() // battery statistics: refresh the notification periodically

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
     * Run when the screen turns off.
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

                // freeze freeze-list apps 30 seconds after the screen turns off
                SceneMode.FreezeAppThread(context.applicationContext, true, 30).start()

                // switch to powersave mode automatically after the screen turns off
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
     * After the screen turns off - disable the network.
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
     * Run after the screen turns on and the device is unlocked.
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
            startTimer() // start the periodic notification refresh once the screen is on
            updateModeNoitfy() // refresh the notification after the screen turns on
        }
    }

    /**
     * Refresh the notification.
     */
    private fun updateModeNoitfy() {
        if (screenOn) {
            notifyHelper.notify()
        }
    }

    // switch modes automatically
    private fun autoToggleMode(packageName: String?) {
        if (packageName != null && packageName != lastModePackage) {
            lastModePackage = packageName
            // Game-only automation: the whitelist decides whether the app is a
            // game, GameProfileStore resolves the per-game profile and the mode
            // from before the game returns when the game leaves.
            if (GameListStore.isGame(context, packageName)) {
                val profile = GameProfileStore.modeFor(context, packageName)
                val keep = profile == GameProfileStore.KEEP || profile.isEmpty()
                val target = if (keep) {
                    // Keep the tuning, but still apply the game options for
                    // this package (priority, DND, preload, session).
                    ModeSwitcher.getCurrentPowerMode().ifEmpty { firstMode ?: BALANCE }
                } else {
                    profile
                }
                if (gameBackupMode.isEmpty()) {
                    val current = ModeSwitcher.getCurrentPowerMode()
                    val persisted = PropsUtils.getProp(PROP_GAME_BACKUP)
                    gameBackupMode = when {
                        // The service can be restarted mid-game: when the
                        // current mode already is the game's profile the
                        // persisted pre-game mode is the real backup.
                        !keep && persisted.isNotEmpty() && current == target -> persisted
                        current.isNotEmpty() -> current
                        else -> firstMode ?: BALANCE
                    }
                    PropsUtils.setProp(PROP_GAME_BACKUP, gameBackupMode)
                }
                if (target.isNotEmpty()) {
                    scheduleToggle(target, packageName)
                }
            } else if (gameBackupMode.isNotEmpty()) {
                val restore = gameBackupMode
                gameBackupMode = ""
                PropsUtils.setProp(PROP_GAME_BACKUP, "")
                scheduleToggle(restore, packageName)
            }
            setCurrentPowercfgApp(packageName)
            updateModeNoitfy() // refresh the notification after the app changed
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
     * Focused app changed.
     */
    private fun onFocusedAppChanged(packageName: String) {
        if (!screenOn && screenState.isScreenOn()) {
            onScreenOn() // if an app switch reveals the screen is on while the recorded state is off, notify screen on
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
        // add the force-ignore list
        ignoredList.addAll(context.resources.getStringArray(R.array.powercfg_force_igoned))
        // add input methods to the ignore list
        ignoredList.addAll(InputMethodApp(context).getInputMethods())

        // whether the performance tuning config has been installed or customized
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
        updateModeNoitfy() // refresh the notification after the service starts

        // disable SELinux enforcement
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
