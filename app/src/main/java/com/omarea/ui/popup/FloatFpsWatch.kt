@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.omarea.ui.popup

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.data.GlobalStatus
import com.omarea.data.IEventReceiver
import com.omarea.util.CpuFrequencyUtils
import com.omarea.util.CpuLoadUtils
import com.omarea.util.fps.FpsSampler
import com.omarea.util.measure.MeasureLog
import com.omarea.util.GpuUtils
import com.omarea.runtime.ModeSwitcher
import com.omarea.data.FpsWatchStore
import com.omarea.util.WindowCompatHelper
import com.omarea.vtools.R
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

public class FloatFpsWatch(private val mContext: Context) {
    private val fpsWatchStore = FpsWatchStore(mContext)
    private var sessionId = 0L
    private var sessionApp: String? = null

    /**
     * dp转换成px
     */
    private fun dp2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    /**
     * 显示弹出框
     * @param context
     */
    fun showPopupWindow(): Boolean {
        if (show!!) {
            return true
        }

        if (!(mContext is AccessibilityService)) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(mContext)) {
                Toast.makeText(mContext, mContext.getString(R.string.permission_float), Toast.LENGTH_LONG).show()
                return false
            }
        }

        show = true
        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        mView = setUpView(mContext)

        val params = LayoutParams()

        // 类型
        // 优先使用辅助服务叠加层（如果是辅助服务Context）
        if (mContext is AccessibilityService) {
            params.type = LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            params.type = WindowCompatHelper.overlayWindowType()
        }
        params.format = PixelFormat.TRANSLUCENT

        params.width = LayoutParams.WRAP_CONTENT
        params.height = LayoutParams.WRAP_CONTENT

        params.gravity = Gravity.TOP or Gravity.RIGHT
        params.y = dp2px(mContext, 55f)

        @Suppress("DEPRECATION")
        params.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_FULLSCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        mWindowManager!!.addView(mView, params)

        startTimer()
        EventBus.subscribe(appWatch)
        GlobalScope.launch {
            coreCount = CpuFrequencyUtils().coreCount
            clusters = CpuFrequencyUtils().clusterInfo
        }

        return true
    }

    private val appWatch = object : IEventReceiver {
        override fun eventFilter(eventType: EventType): Boolean {
            return (eventType == EventType.APP_SWITCH || eventType == EventType.SCREEN_OFF || eventType == EventType.SCREEN_ON)
        }

        override fun onReceive(eventType: EventType, data: HashMap<String, Any>?) {
            if (sessionId > 0) {
                if ((eventType == EventType.SCREEN_OFF || eventType == EventType.SCREEN_ON) || (GlobalStatus.lastPackageName != sessionApp)) {
                    endSession()
                    myHandler.post {
                        if (eventType == EventType.SCREEN_OFF) {
                            Toast.makeText(mContext, "The screen display status changes, and the frame rate recording ends", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(mContext, "The foreground application changes and the frame rate recording ends", Toast.LENGTH_SHORT).show()
                        }
                        recordBtn?.run {
                            setImageResource(R.drawable.play)
                            view?.alpha = 1f
                        }
                    }
                }
            }
        }

        override val isAsync: Boolean
            get() = false

        override fun onSubscribe() {}

        override fun onUnsubscribe() {}
    }

    /** Ends the session once (store timestamp + log evidence). */
    private fun endSession() {
        val ended = sessionId
        sessionId = -1
        if (ended > 0) {
            fpsWatchStore.endSession(ended)
            MeasureLog.sample("fps.session", "end", source = "record", extra = "session=$ended")
        }
    }

    private fun stopTimer() {
        sampler?.shutdownNow()
        sampler = null
    }

    private var view: View? = null
    private var fpsText: TextView? = null
    private var recordBtn: ImageButton? = null

    private var myHandler = Handler(Looper.getMainLooper())

    private val fpsSampler = FpsSampler()
    private val cpu = CpuLoadUtils()
    private var coreCount = -1
    private var clusters = ArrayList<Array<String>>()

    /** Wall elapsed at the previous sample start; dt must reflect real spacing. */
    private var lastSampleAt = 0L

    private fun updateInfo() {
        val sampleAt = SystemClock.elapsedRealtime()
        val dtMs = if (lastSampleAt > 0) (sampleAt - lastSampleAt).coerceIn(100L, 10_000L) else 1000L
        lastSampleAt = sampleAt

        val fpsSample = fpsSampler.sample(sessionApp)
        val gpuLoad = GpuUtils.getGpuLoad()
        if (coreCount < 1) {
            coreCount = CpuFrequencyUtils().coreCount
            clusters = CpuFrequencyUtils().clusterInfo
        }

        // 尝试获得大核心的最高负载
        val loads = cpu.cpuLoad
        var cpuLoad = (loads.getValue(-1) ?: -1.0).toDouble()
        // 一般BigLittle架构的处理器，后面几个核心都是大核，游戏主要依赖大核性能。而小核负载一般来源于后台进程，因此不需要分析小核负载
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
                // 如果某个大核负载超过70%，则将CPU负载显示为此大核的负载
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

        val temperature = GlobalStatus.updateBatteryTemperature().takeIf { it > 5.0 && it < 100.0 }
        val capacity = GlobalStatus.batteryCapacity

        if (sessionId > 0) {
            if (fpsSample != null) {
                val mode = ModeSwitcher.getCurrentPowerMode()
                fpsWatchStore.addHistory2(
                    sessionId,
                    fpsSample.fps,
                    cpuLoad,
                    gpuLoad.toDouble(),
                    capacity,
                    temperature ?: -1.0,
                    mode,
                    dtMs,
                    fpsSample.source,
                    0,
                    fpsSample.jankFrames,
                    fpsSample.frames,
                    true
                )
                MeasureLog.sample("fps", "%.1f".format(fpsSample.fps), "fps", fpsSample.source, true,
                    "frames=${fpsSample.frames} span=${fpsSample.spanMs} jank=${fpsSample.jankFrames} dt=$dtMs")
                MeasureLog.sample("cpu.load", "%.1f".format(cpuLoad), "%", "proc_stat")
                MeasureLog.sample("gpu.load", gpuLoad, "%", "kgsl")
                MeasureLog.sample("battery.capacity", capacity, "%", "GlobalStatus")
                MeasureLog.sample("battery.temperature", temperature, "°C", "battery")
                for (policy in arrayOf("policy0", "policy6")) {
                    val khz = com.omarea.util.measure.SysReader
                        .readFirst("/sys/devices/system/cpu/cpufreq/$policy/scaling_cur_freq")
                        ?.toLongOrNull()
                    MeasureLog.sample("cpu.freq.$policy", khz?.div(1000), "MHz", "scaling_cur_freq")
                }
            } else {
                // No source produced a valid reading: do NOT store a sentinel.
                MeasureLog.sample("fps", "invalid", "fps", "sampler", false, "dt=$dtMs")
            }
        }

        myHandler.post {
            fpsText?.text = when {
                fpsSample == null -> "--"
                fpsSample.fps >= 100 -> fpsSample.fps.toInt().toString()
                else -> String.format(Locale.US, "%.1f", fpsSample.fps)
            }
        }
    }

    private fun startTimer() {
        stopTimer()
        lastSampleAt = 0L
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fps-watch-sampler").apply { isDaemon = true }
        }
        // Fixed delay: a slow tick never causes a catch-up burst (accuracy of dt).
        executor.scheduleWithFixedDelay({ runCatching { updateInfo() } }, 0, 1000, TimeUnit.MILLISECONDS)
        sampler = executor
    }

    /**
     * 隐藏弹出框
     */
    fun hidePopupWindow() {
        stopTimer()
        endSession()
        if (show!! && null != mView) {
            mWindowManager!!.removeView(mView)
            mView = null
            show = false
        }
        EventBus.unsubscribe(appWatch)
    }

    @SuppressLint("ApplySharedPref", "ClickableViewAccessibility")
    private fun setUpView(context: Context): View {
        view = LayoutInflater.from(context).inflate(R.layout.fw_fps_watch, null)
        fpsText = view!!.findViewById(R.id.fw_fps)
        recordBtn = view!!.findViewById(R.id.fw_action)
        view?.setOnClickListener {
            recordBtn?.run {
                if (sessionId > 0) {
                    endSession()
                    setImageResource(R.drawable.play)
                    view?.alpha = 1f
                    Toast.makeText(mContext, "Frame rate recording is over!", Toast.LENGTH_SHORT).show()
                } else {
                    val app = if (GlobalStatus.lastPackageName.isNullOrEmpty()) "android" else GlobalStatus.lastPackageName
                    sessionId = fpsWatchStore.createSession(app)
                    sessionApp = GlobalStatus.lastPackageName
                    lastSampleAt = 0L
                    setImageResource(R.drawable.stop)
                    view?.alpha = 0.6f
                    MeasureLog.sample("fps.session", "start", source = "record", extra = "app=$app session=$sessionId")
                    Toast.makeText(mContext, "Frame rate recording starts, please do not use the game toolbox or leave the current application!", Toast.LENGTH_LONG).show()
                }
            }
        }

        return view!!
    }

    companion object {
        private var mWindowManager: WindowManager? = null
        public var show: Boolean? = false

        @SuppressLint("StaticFieldLeak")
        private var mView: View? = null
        private var sampler: ScheduledExecutorService? = null
    }
}
