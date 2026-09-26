package com.omarea.vtools.activities

import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.omarea.Scene
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.ThemeMode
import com.omarea.store.SpfConfig
import com.omarea.utils.WindowCompatHelper
import com.omarea.vtools.R

open class ActivityBase : AppCompatActivity() {
    public lateinit var themeMode: ThemeMode
    private var lastUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var lastThemePref = Int.MIN_VALUE
    private val themePrefs: SharedPreferences by lazy {
        getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Resolve the theme first: the system-bar icon colour below depends on it and
        // `themeMode` is a lateinit property, so reading it earlier would throw.
        this.themeMode = ThemeSwitch.switchTheme(this)
        lastUiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastThemePref = themePrefs.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)

        // With targetSdk 36 edge-to-edge is mandatory on Android 15+ and there is no
        // opt-out (android.R.attr.windowOptOutEdgeToEdgeEnforcement is deprecated and
        // disabled), so the window content extends behind the system bars. Mark the
        // window as edge-to-edge up front; each screen then applies its insets via
        // applyContentInsets() / WindowCompatHelper.applySystemBarInsets(...).
        // ThemeMode already tells us whether the status-bar icons should be dark.
        WindowCompatHelper.applyEdgeToEdge(
            window,
            lightStatusBars = themeMode.isLightStatusBar,
            lightNavBars = themeMode.isLightStatusBar
        )

        initBackHandling()
    }

    /**
     * Routes system back gestures through [onBackPressedDispatcher] instead of
     * overriding the deprecated `Activity.onBackPressed()`. Subclasses only need
     * to override [handleBackPressed] to customise the behaviour.
     */
    private fun initBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    /**
     * Applies system-bar insets to the activity's content view.
     *
     * Call this from a subclass after `setContentView()`. It is intentionally not
     * called automatically because a few screens (the splash and the floating
     * windows) must not be inset.
     */
    protected fun applyContentInsets(
        top: Boolean = true,
        bottom: Boolean = true,
        leftRight: Boolean = true,
        includeIme: Boolean = true
    ) {
        val content = findViewById<View>(android.R.id.content) ?: return
        WindowCompatHelper.applySystemBarInsets(
            content,
            top = top,
            bottom = bottom,
            leftRight = leftRight,
            includeIme = includeIme
        )
    }

    /**
     * Wires up the shared `layout_app_bar.xml` bar for edge-to-edge.
     *
     * Screens built from `layout_app_bar` place it at the very top of an
     * edge-to-edge window, so it must absorb the status-bar / cutout inset itself.
     * Call this from `onCreate()` after `setContentView()` — it is a no-op on
     * screens that do not include the shared bar.
     *
     * @param idBottom optional view (FAB, bottom-anchored list) that should also be
     *   lifted above the navigation bar
     */
    protected fun applyAppBarInsets(idBottom: Int? = null) {
        val appBar = findViewById<View>(R.id.app_bar)
        val toolbar = findViewById<View>(R.id.toolbar)
        WindowCompatHelper.applyAppBarInsets(appBar, toolbar)
        if (idBottom != null) {
            WindowCompatHelper.applyBottomInset(findViewById(idBottom))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)

        this.themeMode = ThemeSwitch.switchTheme(this)
        lastUiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        lastThemePref = themePrefs.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)
    }

    protected val context: Context
        get() {
            return this
        }

    protected fun setBackArrow() {
        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        // Show the back button
        supportActionBar!!.setHomeButtonEnabled(true)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /**
     * Prefer `onBackPressedDispatcher` over overriding `onBackPressed()`: the
     * platform method is deprecated for `android:enableOnBackInvokedCallback`
     * devices and cannot be intercepted by the predictive-back API.
     * See [initBackHandling].
     */
    protected open fun handleBackPressed() {
        // If this activity is the task root, return to main instead of exiting.
        if (isTaskRoot && this !is ActivityMain) {
            val intent = Intent(this, ActivityMain::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(ActivityMain.EXTRA_SELECT_TAB, ActivityMain.lastSelectedTab)
            }
            startActivity(intent)
        }
        // FIX: Activity(IRequestFinishCallback$Stub) memory leak
        finishAfterTransition()
    }

    protected fun excludeFromRecent() {
        try {
            val service = this.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (task in service.appTasks) {
                // taskInfo is nullable: it can be null if the task record has
                // already been removed by the system between the appTasks
                // snapshot and this read.
                if (task.taskInfo?.taskId == this.taskId) {
                    task.setExcludeFromRecents(true)
                }
            }
        } catch (ex: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Scene.postDelayed({
            System.gc()
        }, 500)
        // Note: this used to schedule `dumpsys meminfo <pkg> > /dev/null` on the
        // root shell after 100ms. It discards its output, so it freed nothing -
        // but it did occupy the shared root shell, and when a profile switch was
        // still running the main thread blocked on the shell lock long enough
        // for the system to show "Scene isn't responding". Removed.
    }

    override fun onResume() {
        super.onResume()
        val currentThemePref = themePrefs.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)
        if (currentThemePref != lastThemePref) {
            lastThemePref = currentThemePref
            themeMode = ThemeSwitch.switchTheme(this)
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newUiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newUiMode != lastUiMode) {
            lastUiMode = newUiMode
            themeMode = ThemeSwitch.switchTheme(this)
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }
}
