package com.omarea

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.ShellExecutor
import com.omarea.data.EventBus
import com.omarea.data.customer.ChargeCurve
import com.omarea.data.customer.PowerUtilizationCurve
import com.omarea.data.customer.ScreenOffCleanup
import com.omarea.data.publisher.BatteryState
import com.omarea.data.publisher.ScreenState
import com.omarea.permissions.Busybox
import com.omarea.permissions.CheckRootStatus
import com.omarea.scene_mode.game.GameSessionTracker
import com.omarea.scene_mode.trigger.TimingTaskManager
import com.omarea.scene_mode.trigger.TriggerIEventMonitor
import com.omarea.store.SpfConfig
import com.omarea.utils.CrashHandler
import com.omarea.utils.SceneLog
import com.omarea.vtools.R

class Scene : Application() {
    companion object {
        private val handler = Handler(Looper.getMainLooper())
        public lateinit var context: Application
        public lateinit var thisPackageName: String
        private var nightMode = false
        private var config: SharedPreferences? = null
        public val globalConfig:SharedPreferences
            get () {
                if (config == null) {
                    config = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                }
                return config!!
            }

        public val isNightMode: Boolean
            get() {
                return nightMode
            }

        public fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return globalConfig.getBoolean(key, defaultValue)
        }

        public fun setBoolean(key: String, value: Boolean) {
            globalConfig.edit().putBoolean(key, value).apply()
        }

        public fun getString(key: String, defaultValue: String): String? {
            return globalConfig.getString(key, defaultValue)
        }

        public fun toast(message: String, time: Int) {
            handler.post {
                Toast.makeText(context, message, time).show()
            }
        }

        public fun toast(message: String) {
            handler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        public fun toast(message: Int, time: Int) {
            handler.post {
                Toast.makeText(context, message, time).show()
            }
        }

        public fun post(runnable: Runnable) {
            handler.post(runnable)
        }

        public fun postDelayed(runnable: Runnable, delayMillis: Long) {
            handler.postDelayed(runnable, delayMillis)
        }
    }

    // Screen lock/unlock listener
    private lateinit var screenState: ScreenState

    private var lastThemeId = R.style.AppTheme
    private fun setAppTheme(theme: Int) {
        if (lastThemeId != theme) {
            setTheme(theme)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        /*
        try {
            val theme = (if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_YES) != 0) {
                R.style.AppThemeNight
            } else {
                R.style.AppTheme
            })
            setAppTheme(theme)
        } catch (ex: Exception) {
        }
        */
        nightMode = ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_YES) != 0)
    }

    override fun onCreate() {
        super.onCreate()
        // One background ticker for the game session report and thermal guard.
        GameSessionTracker.start(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = this
        // SceneLog has to come up first: CrashHandler reports through it, and anything
        // logged during startup should land in the same correlated buffer.
        SceneLog.init(this)
        CrashHandler().init(this)
        SceneLog.i("Boot", "Scene starting (API ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}, ${android.os.Build.DEVICE})")
        /*
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES) {
            setAppTheme(R.style.AppThemeNight)
        }
        */
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES) {
            nightMode = true
        }
        thisPackageName = this.packageName

        // Install busybox when the ROM does not ship one
        if (!Busybox.systemBusyboxInstalled()) {
            ShellExecutor.setExtraEnvPath(
                FileWrite.getPrivateFilePath(this, getString(R.string.toolkit_install_path))
            )
        }

        // Screen state tracking
        screenState = ScreenState(this)
        screenState.autoRegister()

        // Battery state tracking
        BatteryState(context).registerReceiver()

        // Timed tasks
        TimingTaskManager(this).updateAlarmManager()

        // Event-driven tasks
        EventBus.subscribe(TriggerIEventMonitor(this))

        // Charge curve
        EventBus.subscribe(ChargeCurve(this))
        // Power-usage curve
        EventBus.subscribe(PowerUtilizationCurve(this))

        // Hide floating windows when the screen turns off
        EventBus.subscribe(ScreenOffCleanup(context))

        // If root was granted on a previous launch, ask for it again now
        if (getBoolean("root", false)) {
            CheckRootStatus.checkRootAsync()
        }
    }
}
