package com.omarea.vtools.popup

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView
import android.widget.Toast
import com.omarea.Scene
import com.omarea.data.GlobalStatus
import com.omarea.library.shell.*
import com.omarea.store.SpfConfig
import com.omarea.utils.WindowCompatHelper
import com.omarea.vtools.R
import java.util.*

public class FloatMonitorMini(private val mContext: Context) {
    private var startMonitorTime = 0L
    private var cpuLoadUtils = CpuLoadUtils()
    private var cpuFrequencyUtils = CpuFrequencyUtils()

    private val globalSPF = mContext.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

    /**
     * Show the popup window
     * @param context
     */
    fun showPopupWindow(): Boolean {
        if (show!!) {
            return true
        }
        startMonitorTime = System.currentTimeMillis()
        if (batteryManager == null) {
            batteryManager = mContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        }

        if (!(mContext is AccessibilityService)) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
                Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
                return false
            }
        }

        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view = setUpView(mContext)

        val params = LayoutParams()
        val monitorStorage = mContext.getSharedPreferences("float_monitor2_storage", Context.MODE_PRIVATE)

        // Type
        // Prefer the accessibility overlay type for an AccessibilityService context
        if (mContext is AccessibilityService) {
            params.type = LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            params.type = WindowCompatHelper.overlayWindowType()
        }
        params.format = PixelFormat.TRANSLUCENT

        params.width = LayoutParams.WRAP_CONTENT
        params.height = LayoutParams.WRAP_CONTENT

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = monitorStorage.getInt("x", 0)
        params.y = monitorStorage.getInt("y", 0)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val navHeight = 0
        if (navHeight > 0) {
            val p = WindowCompatHelper.getRealDisplaySize(mWindowManager!!)
            params.y = -navHeight
            params.x = 0
        }
        try {
            mWindowManager!!.addView(view, params)
            mView = view
            show = true

            startTimer()
            return true
        } catch (ex: Exception) {
            Scene.toast("FloatMonitorMini Error\n" + ex.message)
            return false
        }
    }

    private fun stopTimer() {
        if (timer != null) {
            timer!!.cancel()
            timer = null
        }
    }

    private var view: View? = null
    private var cpuLoadTextView: TextView? = null
    private var gpuLoadTextView: TextView? = null
    private var gpuPanel: View? = null
    private var temperaturePanel: View? = null
    private var temperatureText: TextView? = null
    private var fpsText: TextView? = null

    private var activityManager: ActivityManager? = null
    private var myHandler = Handler(Looper.getMainLooper())
    private val info = ActivityManager.MemoryInfo()
    private var coreCount = -1
    private var clusters = ArrayList<Array<String>>()

    private val fpsUtils = FpsUtils()
    private var batteryManager: BatteryManager? = null

    private var pollingPhase = 0

    private fun updateInfo() {
        pollingPhase += 1
        pollingPhase %= 4

        if (coreCount < 1) {
            coreCount = cpuFrequencyUtils.coreCount
            clusters = cpuFrequencyUtils.clusterInfo
        }
        val gpuLoad = GpuUtils.getGpuLoad()

        activityManager!!.getMemoryInfo(info)

        var cpuLoad = cpuLoadUtils.cpuLoadSum
        // Try to find the highest load among the big cores
        val loads = cpuLoadUtils.cpuLoad
        // On big.LITTLE SoCs the later cores are the big ones and games depend on big-core performance; little-core load usually comes from background processes, so it is not analysed
        val centerIndex = coreCount / 2
        var bigCoreLoadMax = 0.0
        if (centerIndex >= 2) {
            try {
                for (i in centerIndex until coreCount) {
                    val coreLoad = loads[i]!!
                    if (coreLoad > bigCoreLoadMax) {
                        bigCoreLoadMax = coreLoad
                    }
                }
                // If a big core exceeds 70% load, report the CPU load as that core's load
                // The mini monitor exists mainly to relate CPU load to game performance
                // A saturated single core usually causes stutter, so on high single-core load show that core instead of the multi-core average
                // so the user knows the CPU pressure may be causing stutter
                if (bigCoreLoadMax > 70 && bigCoreLoadMax > cpuLoad) {
                    cpuLoad = bigCoreLoadMax
                }
            } catch (ex: java.lang.Exception) {
                Log.e("", "" + ex.message)
            }
        }

        if (cpuLoad < 0) {
            cpuLoad = 0.toDouble()
        }

        val fps = fpsUtils.currentFps
        var batState: String? = null

        if (pollingPhase != 0) {
            // Battery current
            val now = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val nowMA = if (now != null) {
                (now / globalSPF.getInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT))
            } else {
                null
            }
            nowMA?.run {
                if (this > -20000 && this < 20000) {
                    batState = "" + (if (this > 0) ("+" + this) else this) + "mA"
                }
            }
        }
        if (batState == null) {
            batState = GlobalStatus.updateBatteryTemperature().toString() + "°C"
        }

        myHandler.post {
            cpuLoadTextView?.text = cpuLoad.toInt().toString() + "%"
            if (gpuLoad > -1) {
                gpuLoadTextView?.text = gpuLoad.toString() + "%"
            } else {
                gpuLoadTextView?.text = "--"
            }

            temperatureText!!.setText(batState!!)
            if (fps != null) {
                fpsText?.text = fps.toString()
            }
        }
    }

    private fun startTimer() {
        stopTimer()
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                updateInfo()
            }
        }, 0, 1500)
    }

    /**
     * Hide the popup window
     */
    fun hidePopupWindow() {
        stopTimer()
        if (show!! && null != mView) {
            try {
                mWindowManager?.removeViewImmediate(mView)
            } catch (ex: Exception) {}
            mView = null
        }
        show = false
    }

    @SuppressLint("ApplySharedPref", "ClickableViewAccessibility")
    private fun setUpView(context: Context): View {
        view = LayoutInflater.from(context).inflate(R.layout.fw_monitor_mini, null)
        gpuPanel = view!!.findViewById(R.id.fw_gpu)
        temperaturePanel = view!!.findViewById(R.id.fw_battery)

        cpuLoadTextView = view!!.findViewById(R.id.fw_cpu_load)
        gpuLoadTextView = view!!.findViewById(R.id.fw_gpu_load)
        temperatureText = view!!.findViewById(R.id.fw_battery_temp)
        fpsText = view!!.findViewById(R.id.fw_fps)

        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        return view!!
    }

    companion object {
        private var mWindowManager: WindowManager? = null
        public var show: Boolean? = false

        /**
         * Was a static `@SuppressLint("StaticFieldLeak")` View. Because this
         * class is built with an Activity context from `DialogMonitor` /
         * `FloatPowercfgSelector`, a static View pinned the detached Activity.
         * Now an instance field.
         */
        private var timer: Timer? = null
    }

    private var mView: View? = null
}
