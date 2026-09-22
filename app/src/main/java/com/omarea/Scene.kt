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
import com.omarea.scene_mode.TimingTaskManager
import com.omarea.scene_mode.TriggerIEventMonitor
import com.omarea.store.SpfConfig
import com.omarea.utils.CrashHandler
import com.omarea.vtools.R
import com.omarea.vtools.privilege.PrivilegeManager

class Scene : Application() {
    companion object {
        private val handler = Handler(Looper.getMainLooper())
        public lateinit var context: Application
        public lateinit var thisPackageName: String
        // Random token valid only within this process; used to distinguish intents sent by the app itself from forged intents of external apps
        public val internalIntentToken: String = java.util.UUID.randomUUID().toString()
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

    // Lock screen state listener
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
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = this
        CrashHandler().init(this)

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

        // Install busybox
        if (!Busybox.systemBusyboxInstalled()) {
            ShellExecutor.setExtraEnvPath(
                FileWrite.getPrivateFilePath(this, getString(R.string.toolkit_install_path))
            )
        }

        // Lock screen state detection
        screenState = ScreenState(this)
        screenState.autoRegister()

        // Battery state detection
        BatteryState(context).registerReceiver()

        // Timed tasks
        TimingTaskManager(this).updateAlarmManager()

        // Event tasks
        EventBus.subscribe(TriggerIEventMonitor(this))

        // Charging curve
        EventBus.subscribe(ChargeCurve(this))
        // Power consumption curve
        EventBus.subscribe(PowerUtilizationCurve(this))

        // Auto-close floating windows on screen off
        EventBus.subscribe(ScreenOffCleanup(context))

        // Privilege tier routing (root / Shizuku / non-root) for all shell commands
        PrivilegeManager.init(this)

        // If root was obtained the last time the app opened, trigger a root permission request
        if (getBoolean("root", false)) {
            CheckRootStatus.checkRootAsync()
        }
    }
}
