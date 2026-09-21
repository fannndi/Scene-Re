package com.omarea.vtools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.content.res.Configuration
import android.graphics.Rect
import android.util.LruCache
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.omarea.Scene
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.library.basic.InputMethodApp
import com.omarea.library.basic.LauncherApps
import com.omarea.library.calculator.Flags
import com.omarea.scene_mode.AppSwitchHandler
import com.omarea.scene_mode.AutoClickInstall
import com.omarea.scene_mode.AutoSkipAd
import com.omarea.store.SpfConfig
import com.omarea.utils.AutoSkipCloudData
import com.omarea.utils.WindowCompatHelper
import com.omarea.vtools.popup.FloatLogView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

/**
 * Created by helloklf on 2016/8/27.
 */
public class AccessibilityScenceMode : AccessibilityService(), IEventReceiver {
    override val isAsync: Boolean
        get() = false

    override fun onSubscribe() {
    }

    override fun onUnsubscribe() {
    }

    private var isLandscape = false
    private val alwaysLandscapeOpt = true
    public val landscapeOptimized: Boolean
        get () {
            if (alwaysLandscapeOpt || isLandscape) {
                return true
            }
            return false
        }
    private var inputMethods = ArrayList<String>()

    private var displayWidth = 1080
    private var displayHeight = 2340
    // Whether this is a tablet
    private var isTablet: Boolean = false

    companion object {
        private var lastAnalyseThread: Long = 0
    }

    private var floatLogView: FloatLogView? = null

    internal var appSwitchHandler: AppSwitchHandler? = null

    private lateinit var spf: SharedPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Apps to ignore for the skip ads feature
    private var skipAdIgnoredApps = ArrayList<String>().apply {
        add("com.android.systemui")
    }

    /**
     * Screen configuration changed (rotation, resolution change, DPI change, etc.)
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onScreenConfigurationChanged(newConfig)
    }

    private fun getIsLandscape(): Boolean {
        val config = resources.configuration
        val orientation = config.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return true
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            return false
        }
        return false
    }

    private fun onScreenConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            isLandscape = false
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            isLandscape = true
        }
        getDisplaySize()
    }

    private fun getDisplaySize() {
        // Re-get the screen resolution
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = WindowCompatHelper.getRealDisplaySize(wm)
        if (point.x != displayWidth || point.y != displayHeight) {
            displayWidth = point.x
            displayHeight = point.y
        }

        isTablet = resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }

    private fun updateConfig() {
        val info = serviceInfo // AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED

        info.notificationTimeout = 0

        if (spf.getBoolean(SpfConfig.GLOBAL_SPF_AUTO_INSTALL, false) || spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD, false)) {
            info.eventTypes = Flags(info.eventTypes).addFlag(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            if (spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD, false)) {
                // Only used to capture ad buttons during debugging; remove this flag in release builds
                // info.eventTypes = Flags(info.eventTypes).addFlag(AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
        }

        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0
        info.packageNames = null

        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        // Capture physical key events
        // info.flags = Flags(info.flags).addFlag(AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)

        serviceInfo = info
    }

    override fun onCreate() {
        super.onCreate()
        EventBus.subscribe(this)
    }

    override fun eventFilter(eventType: EventType): Boolean {
        return eventType == EventType.SERVICE_DEBUG || eventType == EventType.SERVICE_UPDATE || eventType == EventType.SCREEN_ON || eventType == EventType.STATE_RESUME
    }

    override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
        if (eventType == EventType.SERVICE_DEBUG) {
            if (setLogView()) {
                modernModeEvent()
            }
        } else if (eventType == EventType.SCREEN_ON) {
            if (!serviceIsConnected) {
                Scene.toast("The accessibility service has expired, please reactivate the accessibility service!")
            }
        } else if (eventType == EventType.STATE_RESUME) {
            modernModeEvent(null)
        } else if (eventType == EventType.SERVICE_UPDATE) {
            updateConfig()
            Scene.toast("Ancillary service configuration has been updated~", Toast.LENGTH_SHORT)
        }
    }

    private fun setLogView(): Boolean {
        val showLogView = spf.getBoolean(SpfConfig.GLOBAL_SPF_SCENE_LOG, false)
        if (showLogView && floatLogView == null) {
            floatLogView = FloatLogView(this)
            return true
        } else if (!showLogView && floatLogView != null) {
            floatLogView?.hide()
            floatLogView = null
        }
        return false
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        spf = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        // Get screen orientation
        onScreenConfigurationChanged(this.resources.configuration)

        serviceIsConnected = true

        updateConfig()

        if (appSwitchHandler == null) {
            appSwitchHandler = AppSwitchHandler(this)
        }

        getDisplaySize()
        setLogView()
        if (spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD, false) && spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD_PRECISE, false)) {
            AutoSkipCloudData().updateConfig(this, false)
        }

        // Get input methods
        serviceScope.launch {
            inputMethods = InputMethodApp(applicationContext).getInputMethods()
            skipAdIgnoredApps.addAll(LauncherApps(applicationContext).launcherApps)
            skipAdIgnoredApps.addAll(inputMethods)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        /* // Used during development to analyse UI clicks (capture ad buttons)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val viewId = event.source?.viewIdResourceName // Some skip buttons are not text // if (event.text?.contains("skip") == true) event.source?.viewIdResourceName else null
            Log.d("@Scene", "Clicked [$viewId], in ${event.className}")
        }
        */

        /*
        when(event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.e(">>>>", "TYPE_WINDOW_CONTENT_CHANGED " + event.eventType)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                Log.e(">>>>", "TYPE_WINDOWS_CHANGED " + event.eventType)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.e(">>>>", "TYPE_WINDOW_STATE_CHANGED " + event.eventType)
            }
            else -> {
                Log.e(">>>>", "???? " + event.eventType)
            }
        }
        */

        val packageName = event.packageName
        if (packageName != null) {
            when {
                packageName == "com.omarea.gesture" || packageName == "com.omarea.filter" -> {
                    return
                }
                /*
                packageName == "com.android.systemui" -> {
                    return
                }
                */
                // packageName == "com.omarea.vtools" -> return
                packageName.contains("packageinstaller") -> {
                    if (event.className == "com.android.packageinstaller.permission.ui.GrantPermissionsActivity") // MIUI permission controller
                        return

                    try {
                        AutoClickInstall().packageinstallerAutoClick(this, event)
                    } catch (ex: Exception) {
                    }
                }
                packageName == "com.miui.securitycenter" -> {
                    try {
                        AutoClickInstall().miuiUsbInstallAutoClick(this, event)
                    } catch (ex: Exception) {
                    }
                    return
                }
                packageName == "com.android.permissioncontroller" -> { // Stock permission controller
                    return
                }
                spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD, false) -> {
                    trySkipAD(event)
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return
        }

        val t = event.eventTime
        if (lastOriginEventTime != t && t > lastOriginEventTime) {
            lastOriginEventTime = t
            lastWindowChanged = System.currentTimeMillis()
            modernModeEvent(event)
        }
    }

    private var lastWindowChanged = 0L
    private var lastOriginEventTime = 0L
    private var autoSkipAd: AutoSkipAd? = null
    private fun trySkipAD(event: AccessibilityEvent) {
        // Only auto-skip ads within 3 seconds after the window content changes; reduces performance cost and false clicks
        if (System.currentTimeMillis() - lastWindowChanged < 3000) {
            if (autoSkipAd == null) {
                autoSkipAd = AutoSkipAd(this)
            }

            val packageName = event.packageName
            if (packageName == null || skipAdIgnoredApps.contains(packageName) || event.className === "android.widget.EditText") {
                // Log.d("@Scene", "SkipAD -> ignore")
                return
            }

            autoSkipAd?.skipAd(event, spf.getBoolean(SpfConfig.GLOBAL_SPF_SKIP_AD_PRECISE, false), displayWidth, displayHeight)
        }
    }

    private val blackTypeList = arrayListOf(
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
            AccessibilityWindowInfo.TYPE_SYSTEM
    )

    private val blackTypeListBasic = arrayListOf(
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
    )

    public fun getEffectiveWindows(includeSystemApp: Boolean = false): List<AccessibilityWindowInfo> {
        val windowsList = windows
        if (windowsList != null && windowsList.size > 1) {
            val effectiveWindows = windowsList.filter {
                // Picture-in-picture apps are no longer filtered out: some apps such as Telegram are still detected as PiP with type -1 after switching from PiP to fullscreen (likely a MIUI modification), but for users fullscreen means foreground app
                if (includeSystemApp) {
                    !blackTypeListBasic.contains(it.type)
                } else {
                    !blackTypeList.contains(it.type)
                }

                // (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.isInPictureInPictureMode)) && (it.type == AccessibilityWindowInfo.TYPE_APPLICATION)
            } // .sortedBy { it.layer }
            return effectiveWindows
        }
        return ArrayList()
    }

    public fun getForegroundApps(): Array<String> {
        val windows = this.getEffectiveWindows(true)
        return windows.map {
            it.root?.packageName
        }.filter { it != null && it != "com.android.systemui" }.map { it.toString() }.toTypedArray()
    }

    // New foreground app window detection logic
    private fun modernModeEvent(event: AccessibilityEvent? = null) {
        val effectiveWindows = this.getEffectiveWindows()

        if (effectiveWindows.isNotEmpty()) {
            try {
                var lastWindow: AccessibilityWindowInfo? = null
                // Minimum window resolution requirement
                val minWindowSize = if (landscapeOptimized && !isTablet) {
                    // In landscape, care about window size and pick the main app with the larger display area (do not filter window size on tablets)
                    // Half of the screen: used to decide whether a window is small (apps smaller than half the screen are considered windowed)
                    displayHeight * displayWidth / 2
                } else {
                    // In portrait, use the focused window as the foreground app and ignore window size
                    0
                }

                val logs = if (floatLogView == null) null else StringBuilder()
                logs?.run {
                    append("Scene window detection\n", "Screen: ${displayHeight}x${displayWidth}")
                    if (isLandscape) {
                        append(" Horizontal")
                    } else {
                        append(" Vertical")
                    }
                    if (isTablet) {
                        append(" Tablet")
                    }
                    append("\n")
                    if (event != null) {
                        append("event: ${event.source?.packageName}\n")
                    } else {
                        append("event: Active polling${Date().time / 1000}\n")
                    }
                }
                // TODO:
                //      Earlier tests on MIUI only considered apps shown fullscreen (window size exactly matching the screen resolution), which was very accurate
                //      But it performs poorly on AOSP-like systems, e.g. screens with a notch or systems with navigation keys: the reported window size may exclude the notch area and navigation bar height
                //      So the logic is now adjusted to: pick the closest-to-fullscreen window from all app windows and treat it as the foreground app
                //      This is not perfect, but there is no better solution for now...

                var lastWindowSize = 0
                var lastWindowFocus = false

                // No focused window (usually during a transition animation or window switch)
                if (effectiveWindows.find { it.isActive || it.isFocused } == null) {
                    return
                }

                for (window in effectiveWindows) {
                    /*
                    val wp = window.root?.packageName
                    // Getting the window root node has performance issues, so this check was removed
                    if (wp == null || wp == "android" || wp == "com.android.systemui" || wp == "com.miui.freeform" || wp == "com.omarea.gesture" || wp == "com.omarea.filter" || wp == "com.android.permissioncontroller") {
                        continue
                    }
                    */
                    if (landscapeOptimized) {
                        val outBounds = Rect()
                        window.getBoundsInScreen(outBounds)

                        logs?.run {
                            val windowFocused = (window.isActive || window.isFocused)

                            val wp = try {
                                window.root?.packageName
                            } catch (ex: java.lang.Exception) {
                                null
                            }
                            append("\nlevel: ${window.layer} ${wp} Focused：${windowFocused}\nType: ${window.type} Rect[${outBounds.left},${outBounds.top},${outBounds.right},${outBounds.bottom}]")
                        }

                        val size = (outBounds.right - outBounds.left) * (outBounds.bottom - outBounds.top)
                        if (size >= lastWindowSize) {
                            lastWindow = window
                            lastWindowSize = size
                        }
                    } else {
                        val windowFocused = (window.isActive || window.isFocused)

                        logs?.run {
                            val outBounds = Rect()
                            window.getBoundsInScreen(outBounds)

                            val wp = try {
                                window.root?.packageName
                            } catch (ex: java.lang.Exception) {
                                null
                            }
                            append("\nLevel: ${window.layer} ${wp} Focused：${windowFocused}\nType: ${window.type} Rect[${outBounds.left},${outBounds.top},${outBounds.right},${outBounds.bottom}]")
                        }

                        if (lastWindowFocus && !windowFocused) {
                            continue
                        }

                        val outBounds = Rect()
                        window.getBoundsInScreen(outBounds)
                        val size = (outBounds.right - outBounds.left) * (outBounds.bottom - outBounds.top)
                        if (size >= lastWindowSize || (windowFocused && !lastWindowFocus)) {
                            lastWindow = window
                            lastWindowSize = size
                            lastWindowFocus = windowFocused
                        }
                    }
                }
                logs?.append("\n")
                if (lastWindow != null && lastWindowSize >= minWindowSize) {
                    val eventWindowId = event?.windowId
                    val lastWindowId = lastWindow.id

                    if (logs == null) {
                        if (eventWindowId == lastWindowId && event.packageName != null) {
                            val pa = event.packageName
                            if (!(landscapeOptimized && inputMethods.contains(pa))) {
                                GlobalStatus.lastPackageName = pa.toString()
                                EventBus.publish(EventType.APP_SWITCH)
                            }
                        } else {
                            lastAnalyseThread = System.currentTimeMillis()
                            windowAnalyse(lastWindow, lastAnalyseThread)
                            if (event != null) {
                                startActivityPolling()
                            }
                        }
                    } else {
                        val wp = if (eventWindowId == lastWindowId) {
                            event.packageName
                        } else {
                            try {
                                lastWindow.root.packageName
                            } catch (ex: java.lang.Exception) {
                                null
                            }
                        }
                        // MIUI optimization: treat opening the MIUI recents screen as no app switch
                        if (wp?.equals("com.miui.home") == true) {
                            /*
                            val node = root?.findAccessibilityNodeInfosByText("Small window application")?.firstOrNull()
                            Log.d("Scene-MIUI", "" + node?.parent?.viewIdResourceName)
                            Log.d("Scene-MIUI", "" + node?.viewIdResourceName)
                            */
                            val node = lastWindow.root?.findAccessibilityNodeInfosByViewId("com.miui.home:id/txtSmallWindowContainer")?.firstOrNull()
                            if (node != null) {
                                return
                            }
                        }
                        if (wp != null) {
                            logs.append("\nBefore: ${GlobalStatus.lastPackageName}")
                            val pa = wp.toString()
                            if (!(landscapeOptimized && inputMethods.contains(pa))) {
                                GlobalStatus.lastPackageName = pa
                                EventBus.publish(EventType.APP_SWITCH)
                            }
                            if (event != null) {
                                startActivityPolling()
                            }
                        }

                        logs.append("\nNow: ${GlobalStatus.lastPackageName}")
                        floatLogView?.update(logs.toString())
                    }
                } else {
                    logs?.append("\nNow: ${GlobalStatus.lastPackageName}")
                    floatLogView?.update(logs.toString())
                    return
                }
            } catch (ex: Exception) {
                return
            }
        }
    }

    // Window id cache (for the same window id, read the cached packageName directly to avoid re-analysing window nodes and reduce performance cost)
    private val windowIdCaches = LruCache<Int, String>(10)
    // Analyse the window using a coroutine
    private fun windowAnalyse(windowInfo: AccessibilityWindowInfo, tid: Long) {
        serviceScope.launch {
            var root: AccessibilityNodeInfo? = null
            val windowId = windowInfo.id
            val wp = (try {
                val cache = windowIdCaches.get(windowId)
                if (cache == null) {
                    // If the app that owns the window is unresponsive, this may time out and return null after 5 seconds, so it must run asynchronously on a thread
                    root = (try {
                        windowInfo.root
                    } catch (ex: Exception) {
                        null
                    })
                    root?.packageName.apply {
                        if (this != null) {
                            windowIdCaches.put(windowId, toString())
                        }
                    }
                } else {
                    // Log.d("@Scene", "windowCacheHit " + cache)
                    cache
                }
            } catch (ex: Exception) {
                null
            })
            // MIUI optimization: treat opening the MIUI recents screen as no app switch
            if (wp?.equals("com.miui.home") == true) {
                // During gesture swipes the launcher is not focused
                if (!windowInfo.isFocused) {
                    return@launch
                }
                /*
                val node = root?.findAccessibilityNodeInfosByText("Small window application")?.firstOrNull()
                Log.d("Scene-MIUI", "" + node?.parent?.viewIdResourceName)
                Log.d("Scene-MIUI", "" + node?.viewIdResourceName)
                */
                val node = root?.findAccessibilityNodeInfosByViewId("com.miui.home:id/txtSmallWindowContainer")?.firstOrNull()
                if (node != null) {
                    return@launch
                }
            }

            if (lastAnalyseThread == tid && wp != null) {
                val pa = wp.toString()
                if (!(landscapeOptimized && inputMethods.contains(pa))) {
                    GlobalStatus.lastPackageName = pa
                    EventBus.publish(EventType.APP_SWITCH)
                }
            }
        }
    }

    private var pollingTimer: Timer? = null // Polling timer
    private var lastEventTime: Long = 0 // Time of the last triggered event
    private val pollingTimeout: Long = 7000 // Polling timeout
    private val pollingInterval: Long = 3000 // Polling interval
    private fun startActivityPolling(delay: Long? = null) {
        stopActivityPolling()
        synchronized(this) {
            lastEventTime = System.currentTimeMillis()
            if (pollingTimer == null) {
                pollingTimer = Timer()
                pollingTimer?.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        val interval = System.currentTimeMillis() - lastEventTime
                        if (interval <= pollingTimeout) {
                            // Log.d(">>>>", "Scene Get Windows")
                            modernModeEvent()
                        } else {
                            stopActivityPolling()
                        }
                    }
                }, delay ?: pollingInterval, pollingInterval)
            }
        }
    }

    private fun stopActivityPolling() {
        synchronized(this) {
            if (pollingTimer != null) {
                pollingTimer?.cancel()
                pollingTimer?.purge()
                pollingTimer = null
            }
        }
    }

    private fun destroy() {
        EventBus.unsubscribe(this)
        if (appSwitchHandler != null) {
            appSwitchHandler?.run {
                EventBus.unsubscribe(this)
            }
            appSwitchHandler = null
            Toast.makeText(applicationContext, "Scene - Ancillary service is closed!", Toast.LENGTH_SHORT).show()
            // disableSelf()
            stopSelf()
        }
    }

    private var serviceIsConnected = false

    override fun onUnbind(intent: Intent?): Boolean {
        serviceIsConnected = false
        destroy()
        stopSelf()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        serviceScope.cancel()
        this.destroy()
        super.onDestroy()
    }
}
