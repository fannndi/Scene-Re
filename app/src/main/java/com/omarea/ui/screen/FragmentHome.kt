@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.omarea.ui.screen

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.*
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.omarea.Scene
import com.omarea.runtime.ModeSwitcher
import com.omarea.common.model.SelectItem
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellTranslation
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.DialogItemChooser
import com.omarea.common.ui.OverScrollGridView
import com.omarea.data.GlobalStatus
import com.omarea.util.GpuInfo
import com.omarea.util.*
import com.omarea.data.CpuCoreInfo
import com.omarea.data.ProcessInfo
import com.omarea.data.SpfConfig
import com.omarea.ui.AdapterCpuCores
import com.omarea.ui.AdapterProcessMini
import com.omarea.ui.CpuBigBarView
import com.omarea.ui.MemoryChartView
import com.omarea.ui.RamBarView
import com.omarea.vtools.R
import com.omarea.ui.activity.*
import com.omarea.ui.dialog.DialogElectricityUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.omarea.ui.home.HomeUiState

class FragmentHome : Fragment() {
    private var composeView: androidx.compose.ui.platform.ComposeView? = null

    private var CpuFrequencyUtil = CpuFrequencyUtils()
    private lateinit var globalSPF: SharedPreferences
    private var timer: Timer? = null

    private var myHandler = Handler(Looper.getMainLooper())
    private var cpuLoadUtils = CpuLoadUtils()
    private val memoryUtils = MemoryUtils()
    private var mGpuInfo: GpuInfo? = null

    private lateinit var batteryManager: BatteryManager
    private lateinit var activityManager: ActivityManager
    private val platformUtils = PlatformUtils()
    private val processUtils = ProcessUtilsSimple(Scene.context)

    private var minFreqList = HashMap<Int, String>()
    private var maxFreqList = HashMap<Int, String>()

    private val uiState = mutableStateOf(HomeUiState())
    private val cpuGridHeightDp = mutableIntStateOf(170)
    private val cpuGridColumns = mutableIntStateOf(4)

    private var cpuChartView: CpuBigBarView? = null
    private var cpuCoreListView: OverScrollGridView? = null
    private var processAdapter: AdapterProcessMini? = null
    private var cpuAdapter: AdapterCpuCores? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        composeView = view as androidx.compose.ui.platform.ComposeView
        return view
    }

    private suspend fun forceKSWAPD(mode: Int): String {
        return withContext(Dispatchers.Default) {
            ShellTranslation(context!!).resolveRow(SwapUtils(context!!).forceKswapd(mode))
        }
    }

    private suspend fun dropCaches() {
        return withContext(Dispatchers.Default) {
            KeepShellPublic.doCmdSync(
                    "sync\n" +
                            "echo 3 > /proc/sys/vm/drop_caches\n" +
                            "echo 1 > /proc/sys/vm/compact_memory")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activityManager = context!!.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        batteryManager = context!!.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        globalSPF = context!!.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)

        val deviceName = when (Build.VERSION.SDK_INT) {
            31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            28 -> "Android 9"
            27 -> "Android 8.1"
            26 -> "Android 8.0"
            25 -> "Android 7.0"
            24 -> "Android 7.0"
            23 -> "Android 6.0"
            22 -> "Android 5.1"
            21 -> "Android 5.0"
            else -> "SDK(" + Build.VERSION.SDK_INT + ")"
        }
        uiState.value = uiState.value.copy(deviceName = deviceName)

        composeView?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView?.setContent {
            val themeMode = (activity as? ActivityBase)?.themeMode
            val controller = ThemeController(
                if (themeMode?.isDarkMode == true) {
                    ColorSchemeMode.Dark
                } else {
                    ColorSchemeMode.Light
                }
            )
            MiuixTheme(controller = controller) {
                val state = uiState.value
                HomeScreen(
                    state = state,
                    cpuGridHeight = cpuGridHeightDp.intValue,
                    onMemoryClear = { onMemoryClear() },
                    onMemoryCompact = { onMemoryCompact(false) },
                    onMemoryCompactLong = { onMemoryCompact(true) },
                    onBatteryEdit = { onBatteryEdit() },
                    onMemoryClick = { onMemoryCardClick() },
                    onBatteryClick = { onBatteryCardClick() },
                    onCpuClick = { setCpuOnline() },
                    processListViewFactory = { createProcessListView(it) },
                    cpuGridViewFactory = { createCpuGridView(it) },
                    onGpuInfoContainerReady = { container ->
                        if (mGpuInfo == null) {
                            GpuInfo.getGpuInfo(container) { gpuInfo ->
                                mGpuInfo = gpuInfo
                                uiState.value = uiState.value.copy(
                                    gpuInfoText = "${gpuInfo.glVendor} ${gpuInfo.glRender}\n${gpuInfo.glVersion}"
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    private fun createProcessListView(context: Context): ListView {
        return ListView(context).apply {
            divider = null
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // Keep visible rows balanced with the CPU panel by adding vertical inset.
            setPadding(0, 26, 0, 26)
            clipToPadding = true
            adapter = AdapterProcessMini(context).apply {
                updateFilterMode(AdapterProcessMini.FILTER_ANDROID)
                processAdapter = this
            }
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                } else {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { parent, _, index, _ ->
                val item = parent.getItemAtPosition(index) as ProcessInfo?
                val intent = Intent(context, ActivityProcess::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("name", item?.name)
                }
                startActivity(intent)
            }
        }
    }

    private fun createCpuGridView(context: Context): OverScrollGridView {
        return OverScrollGridView(context).apply {
            numColumns = cpuGridColumns.intValue
            isVerticalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            cpuCoreListView = this
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                CpuFrequencyUtil.getCoregGovernorParams(position)?.run {
                    val msg = StringBuilder()
                    for (param in this) {
                        msg.append("\n")
                        msg.append(param.key)
                        msg.append("：")
                        msg.append(param.value)
                        msg.append("\n")
                    }
                    activity?.let { DialogHelper.helpInfo(it, "Governor Params", msg.toString()) }
                }
            }
        }
    }

    private fun onMemoryClear() {
        uiState.value = uiState.value.copy(ramInfoText = getString(R.string.please_wait))
        GlobalScope.launch(Dispatchers.Main) {
            dropCaches()
            Scene.toast(getString(R.string.home_cache_cleared), Toast.LENGTH_SHORT)
        }
    }

    private fun onMemoryCompact(isLong: Boolean) {
        uiState.value = uiState.value.copy(zramInfoText = getString(R.string.please_wait))
        if (!isLong) {
            Toast.makeText(context!!, R.string.home_shell_begin, Toast.LENGTH_SHORT).show()
        }
        GlobalScope.launch(Dispatchers.Main) {
            val result = forceKSWAPD(if (isLong) 2 else 1)
            Scene.toast(result, Toast.LENGTH_SHORT)
        }
    }

    private fun onOpenHelp() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://vtools.omarea.com/"))
            )
        } catch (ex: Exception) {
            Toast.makeText(context!!, R.string.home_browser_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBatteryEdit() {
        DialogElectricityUnit().showDialog(context!!)
    }

    private fun onMemoryCardClick() {
        startActivity(Intent(context, ActivitySwap::class.java))
    }

    private fun onBatteryCardClick() {
        if (GlobalStatus.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            startActivity(Intent(context, ActivityPowerUtilization::class.java))
        } else {
            startActivity(Intent(context, ActivityCharge::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (isDetached) {
            return
        }
        activity!!.title = getString(R.string.app_name)

        maxFreqList.clear()
        minFreqList.clear()
        stopTimer()
        updateTick = 0
        timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    updateInfo()
                }
            }, 0, 1500)
        }
    }

    private val coreCount = object : TripleCacheValue(Scene.context, "CoreCount") {
        override fun initValue(): String {
            return "" + CpuFrequencyUtil.coreCount
        }
    }.toInt()

    private fun formatNumber(value: Double): String {
        var bd = BigDecimal(value)
        bd = bd.setScale(1, RoundingMode.HALF_UP)
        return bd.toString()
    }

    @SuppressLint("SetTextI18n")
    private fun updateRamInfo() {
        try {
            val info = ActivityManager.MemoryInfo().apply {
                activityManager.getMemoryInfo(this)
            }
            val totalMem = (info.totalMem / 1024 / 1024f).toInt()
            val availMem = (info.availMem / 1024 / 1024f).toInt()

            val swapInfo = KeepShellPublic.doCmdSync("free -m | grep Swap")
            var swapTotal = 0
            var swapUsed = 0
            if (swapInfo.contains("Swap")) {
                try {
                    val swapInfos = swapInfo.substring(swapInfo.indexOf(" "), swapInfo.lastIndexOf(" ")).trim()
                    if (Regex("[\\d]+[\\s]+[\\d]+").matches(swapInfos)) {
                        swapTotal = swapInfos.substring(0, swapInfos.indexOf(" ")).trim().toInt()
                        swapUsed = swapInfos.substring(swapInfos.indexOf(" ")).trim().toInt()
                    }
                } catch (ex: java.lang.Exception) {
                }
            }

            myHandler.post {
                val ramInfoText = "${((totalMem - availMem) * 100 / totalMem)}% (${totalMem / 1024 + 1}GB)"
                val zramText = if (swapTotal > 0) {
                    if (swapTotal > 99) {
                        "${(swapUsed * 100.0 / swapTotal).toInt()}% (${formatNumber(swapTotal / 1024.0)}GB)"
                    } else {
                        "${(swapUsed * 100.0 / swapTotal).toInt()}% (${swapTotal}MB)"
                    }
                } else {
                    "0% (0MB)"
                }
                val ramUsedPercent = ((totalMem - availMem) * 100 / totalMem).toInt()
                uiState.value = uiState.value.copy(
                    ramInfoText = ramInfoText,
                    zramInfoText = zramText,
                    ramUsedPercent = ramUsedPercent
                )
            }
        } catch (ex: Exception) {
        }
    }

    /**
     * dp转换成px
     */
    private fun dp2px(dpValue: Float): Int {
        val scale = context!!.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    private fun elapsedRealtimeStr(): String {
        val timer = SystemClock.elapsedRealtime() / 1000
        return String.format("%02d:%02d:%02d", timer / 3600, timer % 3600 / 60, timer % 60)
    }

    private var updateTick = 0

    private fun gpuFreqToMhz(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return if (v.length > 6) v.substring(0, v.length - 6) else v
    }

    @SuppressLint("SetTextI18n")
    private fun updateInfo() {
        val cores = ArrayList<CpuCoreInfo>()
        for (coreIndex in 0 until coreCount) {
            val core = CpuCoreInfo(coreIndex)

            core.currentFreq = CpuFrequencyUtil.getCurrentFrequency("cpu$coreIndex")
            if (!maxFreqList.containsKey(coreIndex) || (core.currentFreq != "" && maxFreqList[coreIndex].isNullOrEmpty())) {
                maxFreqList[coreIndex] = CpuFrequencyUtil.getCurrentMaxFrequency("cpu$coreIndex")
            }
            core.maxFreq = maxFreqList[coreIndex]

            if (!minFreqList.containsKey(coreIndex) || (core.currentFreq != "" && minFreqList[coreIndex].isNullOrEmpty())) {
                minFreqList[coreIndex] = CpuFrequencyUtil.getCurrentMinFrequency("cpu$coreIndex")
            }
            core.minFreq = minFreqList[coreIndex]
            cores.add(core)
        }
        val loads = cpuLoadUtils.cpuLoad
        for (core in cores) {
            if (loads.containsKey(core.coreIndex)) {
                core.loadRatio = loads[core.coreIndex]!!
            }
        }

        val gpuFreq = GpuUtils.getGpuFreq() + "Mhz"
        val gpuLoad = GpuUtils.getGpuLoad()
        val gpuGovernor = GpuUtils.getGovernor()
        val gpuMinFreq = GpuUtils.getMinFreq()
        val gpuMaxFreq = GpuUtils.getMaxFreq()
        val cpuTemperatureText = cpuLoadUtils.cpuTemperatureText

        val batteryReading = com.omarea.util.battery.BatterySampler.sample(context!!)
        val batteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryVoltage = (GlobalStatus.batteryVoltage * 10).toInt() / 10.0
        val temperature = GlobalStatus.updateBatteryTemperature()

        updateRamInfo()
        val memInfo = memoryUtils.memoryInfo
        val platform = platformUtils.getCPUName()
        if (updateTick == 0 || updateTick == 3) {
            GlobalScope.launch(Dispatchers.IO) {
                val processList = processUtils.allProcess
                myHandler.post {
                    processAdapter?.setList(processList)
                }
            }
        }

        myHandler.post {
            try {
                val batteryNow = if (batteryReading.valid) "${batteryReading.currentMa}mA" else "--"
                val batteryCapacityText = "$batteryCapacity%  ${batteryVoltage}v"
                val batteryTempText = "${temperature}°C"

                val gpuLoadText = getString(R.string.home_utilization) + "$gpuLoad%"
                val gpuGovernorText = gpuGovernor
                val gpuFreqRangeText = if (gpuMinFreq.isNotEmpty() && gpuMaxFreq.isNotEmpty()) {
                    "${gpuFreqToMhz(gpuMinFreq)} - ${gpuFreqToMhz(gpuMaxFreq)} MHz"
                } else {
                    ""
                }
                val cpuTotalLoadText = if (loads.containsKey(-1)) {
                    getString(R.string.home_utilization) + loads[-1]!!.toInt().toString() + "%"
                } else {
                    "--"
                }

                val modeName = ModeSwitcher.getModName(ModeSwitcher.getCurrentPowerMode())
                val soc = com.omarea.engine.SocInfo.forPlatform(platform)
                val coresOnline = KeepShellPublic.doCmdSync("cat /sys/devices/system/cpu/online").trim()
                val cpuRange = { policy: String ->
                    val base = "/sys/devices/system/cpu/cpufreq/" + policy + "/"
                    val cur = KeepShellPublic.doCmdSync("cat " + base + "scaling_cur_freq").trim()
                    val min = KeepShellPublic.doCmdSync("cat " + base + "scaling_min_freq").trim()
                    val max = KeepShellPublic.doCmdSync("cat " + base + "scaling_max_freq").trim()
                    fun mhz(v: String) = (v.toLongOrNull() ?: 0L) / 1000
                    if (min.isNotEmpty() && max.isNotEmpty()) mhz(cur).toString() + "/" + mhz(min) + "\u2013" + mhz(max) + "MHz" else "--"
                }
                fun clusterText(policy: String): String {
                    val base = "/sys/devices/system/cpu/cpufreq/" + policy + "/"
                    val gov = KeepShellPublic.doCmdSync("cat " + base + "scaling_governor").trim().ifEmpty { "?" }
                    val cur = KeepShellPublic.doCmdSync("cat " + base + "scaling_cur_freq").trim()
                    val min = KeepShellPublic.doCmdSync("cat " + base + "scaling_min_freq").trim()
                    val max = KeepShellPublic.doCmdSync("cat " + base + "scaling_max_freq").trim()
                    fun mhz(v: String) = ((v.toLongOrNull() ?: 0L) / 1000).toString()
                    return gov + "\n" + mhz(cur) + " MHz  (" + mhz(min) + "\u2013" + mhz(max) + ")"
                }
                val cluster0Text = clusterText("policy0")
                val cluster6Text = clusterText("policy6")
                val gpuFreqShort = gpuFreqToMhz(gpuMinFreq) + "/" + gpuFreqToMhz(gpuMaxFreq) + "MHz"
                val gpuDetailText = gpuGovernor + "\n" + gpuFreq + "  (" + gpuFreqRangeText + ")"
                val thermalText = "sconfig " + com.omarea.engine.ThermalProfiles.label(
                    KeepShellPublic.doCmdSync("cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null").trim()
                )
                val gpuLoadPercent = if (gpuLoad > -1) gpuLoad else 0
                val ramUsedPercent = if (memInfo.memTotal > 0) (((memInfo.memTotal - memInfo.memAvailable) * 100) / memInfo.memTotal).toInt() else 0

                uiState.value = uiState.value.copy(
                    modeName = modeName,
                    coresOnline = coresOnline,
                    gpuFreqShort = gpuFreqShort,
                    thermalText = thermalText,
                    cluster0Text = cluster0Text,
                    cluster6Text = cluster6Text,
                    gpuDetailText = gpuDetailText,
                    gpuLoadPercent = gpuLoadPercent,
                    cpuLoadPercent = if (loads.containsKey(-1)) loads[-1]!!.toInt() else 0,
                    ramUsedPercent = ramUsedPercent,
                    swapCached = "" + (memInfo.swapCached / 1024) + "MB",
                    dirty = "" + (memInfo.dirty / 1024) + "MB",
                    runningTime = elapsedRealtimeStr(),
                    batteryNow = batteryNow,
                    batteryCapacity = batteryCapacityText,
                    batteryTemperature = batteryTempText,
                    gpuFreq = gpuFreq,
                    gpuLoadText = gpuLoadText,
                    gpuGovernorText = gpuGovernorText,
                    gpuFreqRangeText = gpuFreqRangeText,
                    cpuTotalLoad = cpuTotalLoadText,
                    cpuPlatform = platform.uppercase(Locale.getDefault()) + " (" + coreCount + " Cores)",
                    cpuTemperatureText = cpuTemperatureText,
                    socText = soc.soc,
                    cpuArchText = soc.cpu
                )

                if (cpuAdapter == null) {
                    val layoutHeight = when {
                        cores.size < 6 -> {
                            cpuGridColumns.intValue = 2
                            dp2px(85 * 2F)
                        }
                        cores.size > 12 -> {
                            cpuGridColumns.intValue = 4
                            dp2px(85 * 4F)
                        }
                        cores.size > 8 -> {
                            cpuGridColumns.intValue = 4
                            dp2px(85 * 3F)
                        }
                        else -> {
                            cpuGridColumns.intValue = 4
                            dp2px(85 * 2F)
                        }
                    }
                    cpuGridHeightDp.intValue = (layoutHeight / resources.displayMetrics.density).toInt()
                    cpuCoreListView?.numColumns = cpuGridColumns.intValue
                    cpuAdapter = AdapterCpuCores(context!!, cores)
                    cpuCoreListView?.adapter = cpuAdapter
                } else {
                    cpuAdapter?.setData(cores)
                }
            } catch (ex: Exception) {
            }
        }
        updateTick++
        if (updateTick > 5) {
            updateTick = 0
            minFreqList.clear()
            maxFreqList.clear()
        }
    }

    private fun stopTimer() {
        if (this.timer != null) {
            updateTick = 0
            timer!!.cancel()
            timer = null
        }
    }

    // 选择开关核心
    private fun setCpuOnline() {
        val activity = (activity as ActivityBase?)
        if (activity != null) {
            val options = ArrayList<SelectItem>().apply {
                for (i in 0 until coreCount) {
                    add(SelectItem().apply {
                        title = "CPU $i"
                        value = "" + i
                        selected = CpuFrequencyUtil.getCoreOnlineState(i)
                    })
                }
            }
            DialogItemChooser(activity.themeMode.isDarkMode, options, true, object : DialogItemChooser.Callback {
                override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                    if (status.isNotEmpty() && status.find { it } != null) {
                        status.forEachIndexed { index, b ->
                            CpuFrequencyUtil.setCoreOnlineState(index, b)
                            updateInfo()
                        }
                    } else {
                        Toast.makeText(activity,  getString(R.string.home_core_required), Toast.LENGTH_SHORT).show()
                    }
                }
            }, true)
            .setTitle(getString(R.string.home_core_switch))
            .show(activity.supportFragmentManager, "home-cpu-control")
        }
    }

    override fun onPause() {
        stopTimer()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        composeView = null
    }
}

