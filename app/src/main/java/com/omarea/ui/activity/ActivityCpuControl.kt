package com.omarea.ui.activity

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.graphics.ColorUtils
import com.omarea.common.model.SelectItem
import com.omarea.common.ui.DialogHelper
import com.omarea.engine.DeviceCaps
import com.omarea.engine.KernelCompat
import com.omarea.engine.ProfileController
import com.omarea.engine.ProfileDiff
import com.omarea.engine.ProfileKey
import com.omarea.engine.ProfileStore
import com.omarea.runtime.ModeSwitcher
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import com.omarea.common.ui.DialogItemChooser
import com.omarea.common.ui.DialogItemChooser2
import com.omarea.ui.dialog.DialogNumberInput
import com.omarea.util.CpuFrequencyUtils
import com.omarea.util.CpuLoadUtils
import com.omarea.util.GpuUtils
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityCpuControlBinding
import org.json.JSONObject
import java.util.*
import kotlin.math.roundToInt

/**
 * Profile editor (CPU control screen).
 *
 * Editing is a pure config operation: values are read from the profile in the
 * tuning JSON, edited in memory ("draft"), and written back on Save. Nothing
 * is written to the kernel — profiles are applied by [ProfileController] when
 * the engine runs, so this screen never fights a running profile.
 *
 * Locking rules:
 *  - engine ON  → the profile owns the kernel, the editor does not open;
 *  - TRUE OFF   → the editor opens read-only (no saves);
 *  - engine OFF → full editing of any profile, no "load" step.
 *
 * Responsibility: binding the [JSONObject] draft to rows, Save/Reset.
 * Non-goals: applying tuning, deciding values (tuning JSON does).
 */
class ActivityCpuControl : ActivityBase() {
    private lateinit var binding: ActivityCpuControlBinding

    private val cpuFrequencyUtils = CpuFrequencyUtils()

    // ------------------------------------------------------------- draft model
    private var mode: String = ProfileKey.BALANCE
    private var draft: JSONObject = JSONObject()
    private var preset: JSONObject? = null
    private var baseline: String = ""
    private var readOnly = false

    // -------------------------------------------------- device capability cache
    private var ready = false
    private var clusterCount = 0
    private var coreCount = 0
    private var coreLists: List<List<Int>> = emptyList()
    private val clusterFreqs = HashMap<Int, Array<String>>()
    private val clusterGovernors = HashMap<Int, Array<String>>()
    private var supportedGPU = false
    private var adrenoGPU = false
    private var gpuFreqTable: Array<String> = emptyArray()
    private var adrenoPLevels: Array<String> = emptyArray()
    private var locked: Set<String> = emptySet()

    // ------------------------------------------------------------------ monitor
    /**
     * Snapshot of the monitor rows, replaced (never mutated) by the UI thread
     * so the timer thread can iterate it without racing [rebuild].
     */
    @Volatile
    private var monitorRows: List<Pair<TextView, () -> String>> = emptyList()
    private var timer: Timer? = null
    private var monitorRunning = false

    // ------------------------------------------------------------------ picking
    interface PickerCallback {
        fun onSelected(result: String)
    }

    interface PickerCallback2 {
        fun onSelected(result: BooleanArray)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCpuControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()

        // A running profile owns the kernel: no editing while it is on.
        // TRUE OFF halts every writer, so the editor still opens read-only
        // (viewing the profile config is harmless and never writes anything).
        // Monitor mode (no root) has zero writers too — read-only as well.
        readOnly = TrueOff.isOff(this) || !CheckRootStatus.isAvailable()
        if (!readOnly && !ProfileController.isEngineOff(this)) {
            Toast.makeText(this, R.string.profile_editor_locked_running, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val requested = intent?.getStringExtra("profile")
        val saved = ProfileKey.canonical(ModeSwitcher.getCurrentPowerMode())
        mode = when {
            requested != null -> ProfileKey.canonical(requested)
            ProfileKey.ALL.contains(saved) -> saved
            else -> ProfileKey.BALANCE
        }
        title = getString(R.string.profile_editor_editing, profileTitle(mode))

        bindActions()
        loadDraft()
        Thread {
            initData()
            runOnUiThread {
                rebuild()
                startMonitor()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        startMonitor()
    }

    override fun onPause() {
        super.onPause()
        stopMonitor()
    }

    override fun onBackPressed() {
        discardOr { super.onBackPressed() }
    }

    // ------------------------------------------------------------------- setup
    private fun initData() {
        try {
            val clusters = cpuFrequencyUtils.clusterInfo
            clusterCount = clusters.size
            coreLists = clusters.map { cluster -> cluster.mapNotNull { it.toIntOrNull() } }
            coreCount = cpuFrequencyUtils.coreCount

            for (cluster in 0 until clusterCount) {
                clusterFreqs[cluster] = (cpuFrequencyUtils.getAvailableFrequencies(cluster) ?: emptyArray())
                    .filter { it.isNotEmpty() }
                    .sortedBy { it.toLongOrNull() ?: 0L }
                    .toTypedArray()
                clusterGovernors[cluster] = (cpuFrequencyUtils.getAvailableGovernors(cluster) ?: emptyArray())
                    .filter { it.isNotEmpty() }
                    .toTypedArray()
            }

            supportedGPU = GpuUtils.supported()
            adrenoGPU = GpuUtils.isAdrenoGPU()
            if (adrenoGPU) {
                gpuFreqTable = GpuUtils.getFreqTableMhz() ?: emptyArray()
                adrenoPLevels = GpuUtils.getAdrenoGPUPowerLevels() ?: emptyArray()
            }
            locked = KernelCompat.snapshot(this).locked.map { it.id }.toSet()
        } catch (_: Exception) {
        } finally {
            ready = true
        }
    }

    private fun bindActions() {
        binding.cpuSaveProfile.setOnClickListener { saveProfile() }
        binding.cpuResetProfile.setOnClickListener { resetProfile() }
    }

    private fun loadDraft() {
        val doc = ProfileStore.doc(this)
        draft = doc?.effectiveProfile(mode) ?: JSONObject()
        preset = doc?.presetProfile(mode)?.let { JSONObject(it.toString()) }
        baseline = draft.toString()
    }

    // ------------------------------------------------------------- rebuild (UI)
    private fun rebuild() {
        if (!ready) return
        binding.cpuMonitorList.removeAllViews()
        binding.cpuClusterList.removeAllViews()
        binding.cpuBoostList.removeAllViews()
        binding.cpuGpuList.removeAllViews()
        binding.cpuCpusetList.removeAllViews()
        binding.cpuSchedList.removeAllViews()
        binding.cpuCores.removeAllViews()
        binding.cpuMiscList.removeAllViews()

        binding.cpuEditorTitle.text = getString(R.string.profile_editor_editing, profileTitle(mode))
        binding.cpuEditorSubtitle.setText(
            when {
                !CheckRootStatus.isAvailable() -> R.string.profile_editor_no_root
                readOnly -> R.string.profile_editor_true_off
                else -> R.string.profile_editor_subtitle
            }
        )

        buildChips()
        buildMonitor()
        buildClusters()
        buildBoost()
        buildGpu()
        buildCpuset()
        buildSched()
        buildCores()
        buildMisc()
        updateStatus()

        binding.cpuMonitorCard.visibility = cardVisibility(binding.cpuMonitorList)
        binding.cpuClusterCard.visibility = cardVisibility(binding.cpuClusterList)
        binding.cpuBoostCard.visibility = cardVisibility(binding.cpuBoostList)
        binding.cpuGpuCard.visibility = cardVisibility(binding.cpuGpuList)
        binding.cpuCpusetCard.visibility = cardVisibility(binding.cpuCpusetList)
        binding.cpuSchedCard.visibility = cardVisibility(binding.cpuSchedList)
        binding.cpuCoresCard.visibility = cardVisibility(binding.cpuCores)
        binding.cpuMiscCard.visibility = cardVisibility(binding.cpuMiscList)
        binding.cpuEditorActions.visibility = if (readOnly) View.GONE else View.VISIBLE
    }

    private fun cardVisibility(group: ViewGroup): Int =
        if (group.childCount > 0) View.VISIBLE else View.GONE

    private fun updateStatus() {
        val changed = ProfileDiff.changedPaths(draft, preset).size
        val text = if (changed == 0) {
            getString(R.string.profile_editor_preset_match)
        } else {
            getString(R.string.profile_editor_summary, changed)
        }
        binding.cpuEditorStatus.text = if (isDirty()) {
            "$text · ${getString(R.string.profile_editor_unsaved)}"
        } else {
            text
        }
    }

    private fun isDirty(): Boolean = draft.toString() != baseline

    private fun discardOr(action: () -> Unit) {
        if (!isDirty()) {
            action()
            return
        }
        DialogHelper.confirm(
            this,
            getString(R.string.profile_discard_title),
            getString(R.string.profile_discard_message),
            DialogHelper.DialogButton(getString(R.string.profile_discard_confirm), Runnable { action() }),
            DialogHelper.DialogButton(getString(R.string.profile_discard_keep), null)
        )
    }

    // ------------------------------------------------------------------- chips
    private fun buildChips() {
        val container = binding.cpuProfileChips
        container.removeAllViews()
        val accent = attrColor(R.attr.sceneActionBg, 0xFF7C3AED.toInt())
        val onAccent = attrColor(R.attr.sceneOnAction, Color.WHITE)
        var selectedChip: View? = null
        for (candidate in ProfileKey.ALL) {
            val selected = candidate == mode
            val chip = TextView(this).apply {
                text = profileTitle(candidate)
                textSize = 13f
                gravity = Gravity.CENTER
                isAllCaps = false
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(if (selected) accent else ColorUtils.setAlphaComponent(accent, 28))
                }
                setTextColor(if (selected) onAccent else accent)
                setOnClickListener { switchMode(candidate) }
            }
            container.addView(
                chip,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
            )
            if (selected) selectedChip = chip
        }
        selectedChip?.let { chip ->
            binding.cpuProfileScroll.post {
                binding.cpuProfileScroll.smoothScrollTo(chip.left - dp(16), 0)
            }
        }
    }

    private fun switchMode(newMode: String) {
        if (newMode == mode) return
        discardOr {
            mode = newMode
            title = getString(R.string.profile_editor_editing, profileTitle(mode))
            Thread {
                loadDraft()
                runOnUiThread { rebuild() }
            }.start()
        }
    }

    // ----------------------------------------------------------------- monitor
    private fun buildMonitor() {
        val container = binding.cpuMonitorList
        val rows = ArrayList<Pair<TextView, () -> String>>()

        fun add(title: String, read: () -> String) {
            val row = layoutInflater.inflate(R.layout.cpu_row_value, container, false)
            row.findViewById<TextView>(R.id.row_title).text = title
            val value = row.findViewById<TextView>(R.id.row_value)
            value.text = "--"
            container.addView(row)
            rows += value to read
        }

        for (cluster in 0 until clusterCount) {
            add("${getString(R.string.profile_term_current)} · ${clusterLabel(cluster)}") {
                khzLabel(cpuFrequencyUtils.getCurrentFrequency(cluster))
            }
        }
        add(getString(R.string.profile_term_cpu_load)) {
            val load = CpuLoadUtils().cpuLoadSum
            val loadText = if (load < 0) "--" else "${load.roundToInt()}%"
            "$loadText · ${CpuLoadUtils().cpuTemperatureText}"
        }
        if (supportedGPU) {
            add(getString(R.string.profile_term_gpu_load)) {
                val freq = GpuUtils.getGpuFreq()
                val load = GpuUtils.getGpuLoad()
                val freqText = if (freq.isEmpty()) "--" else "$freq MHz"
                val loadText = if (load < 0) "--" else "$load%"
                "$freqText · $loadText"
            }
        }
        monitorRows = rows
    }

    private fun startMonitor() {
        if (!ready || monitorRunning) return
        monitorRunning = true
        refreshMonitor()
        timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    refreshMonitor()
                }
            }, 2000, 2000)
        }
    }

    private fun stopMonitor() {
        monitorRunning = false
        timer?.cancel()
        timer = null
    }

    private fun refreshMonitor() {
        val rows = monitorRows
        if (rows.isEmpty()) return
        val values = rows.map { runCatching { it.second() }.getOrDefault("--") }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            rows.forEachIndexed { index, row ->
                row.first.text = values.getOrNull(index) ?: "--"
            }
        }
    }

    // ------------------------------------------------------------- CPU clusters
    private fun buildClusters() {
        val container = binding.cpuClusterList
        for (cluster in 0 until clusterCount) {
            val policy = policyFor(cluster)
            val base = listOf("cpu", policy)
            sectionTitle(container, "${clusterLabel(cluster)} · $policy")

            pathRow(container, getString(R.string.profile_term_governor), base + "governor", ::plainLabel, {
                pick(getString(R.string.profile_picker_governor), governorItems(cluster), getValue(base + "governor")) { value ->
                    setValue(base + "governor", value)
                    rebuild()
                }
            })
            freqRow(container, R.string.profile_term_min, cluster, base + "min")
            freqRow(container, R.string.profile_term_max, cluster, base + "max")
            freqRow(container, R.string.profile_term_hispeed, cluster, base + "hispeed")
            rateRow(container, R.string.profile_term_up_rate, base + "up_rate_limit_us")
            rateRow(container, R.string.profile_term_down_rate, base + "down_rate_limit_us")
        }

        // core_ctl (hotplug) keys the profile manages.
        for (cpu in jsonKeys(listOf("core_ctl"))) {
            val path = listOf("core_ctl", cpu)
            val on = getValue(path) == "on"
            switchRow(
                container,
                getString(R.string.profile_term_core_ctl, cpu),
                on,
                isChanged(path),
                if (readOnly) null else { checked ->
                    setValue(path, if (checked) "on" else "off")
                    rebuild()
                }
            )
        }
    }

    private fun freqRow(container: LinearLayout, titleRes: Int, cluster: Int, path: List<String>) {
        pathRow(container, getString(titleRes), path, ::khzLabel, {
            val freqs = clusterFreqs[cluster] ?: return@pathRow
            pick(
                getString(R.string.profile_picker_frequency),
                freqs.map { it to khzLabel(it) },
                getValue(path)
            ) { value ->
                setValue(path, value)
                rebuild()
            }
        })
    }

    private fun rateRow(container: LinearLayout, titleRes: Int, path: List<String>) {
        pathRow(container, getString(titleRes), path, ::rateLabel, {
            pick(
                getString(R.string.profile_picker_rate),
                RATE_LIMITS.map { it to rateLabel(it) },
                getValue(path)
            ) { value ->
                setValue(path, value)
                rebuild()
            }
        })
    }

    // --------------------------------------------------------------- CPU boost
    private fun buildBoost() {
        val container = binding.cpuBoostList
        if (!hasKey(listOf("input_boost"))) return

        for (cluster in 0 until clusterCount) {
            val cores = coreLists.getOrNull(cluster).orEmpty()
            if (cores.isEmpty()) continue
            val paths = cores.map { listOf("input_boost", it.toString()) }
            val current = getValue(paths.first())
            val mixed = paths.any { getValue(it) != current }
            val label = if (mixed) getString(R.string.profile_value_mixed) else khzLabel(current)
            valueRow(
                container,
                getString(R.string.profile_term_boost_freq, clusterLabel(cluster)),
                label,
                paths.any { isChanged(it) },
                if (readOnly) null else { _ ->
                    val freqs = clusterFreqs[cluster] ?: emptyArray()
                    pick(
                        getString(R.string.profile_picker_frequency),
                        freqs.map { it to khzLabel(it) },
                        current
                    ) { value ->
                        paths.forEach { setValue(it, value) }
                        rebuild()
                    }
                }
            )
        }

        pathRow(container, getString(R.string.profile_term_boost_ms), listOf("input_boost", "ms"), ::msLabel, {
            pick(
                getString(R.string.profile_picker_ms),
                MS_VALUES.map { it to msLabel(it) },
                getValue(listOf("input_boost", "ms"))
            ) { value ->
                setValue(listOf("input_boost", "ms"), value)
                rebuild()
            }
        })

        val boostOnInputPath = listOf("input_boost", "sched_boost_on_input")
        val msValue = getValue(listOf("input_boost", "ms"))?.toIntOrNull() ?: 0
        switchRow(
            container,
            getString(R.string.profile_term_boost_on_input),
            (getValue(boostOnInputPath) ?: if (msValue > 0) "1" else "0") == "1",
            isChanged(boostOnInputPath),
            if (readOnly) null else { checked ->
                setValue(boostOnInputPath, if (checked) "1" else "0")
                rebuild()
            }
        )
    }

    // --------------------------------------------------------------------- GPU
    private fun buildGpu() {
        val container = binding.cpuGpuList
        if (locked.contains("adrenoboost")) {
            infoRow(container, getString(R.string.profile_term_adreno_boost))
        }
        if (!supportedGPU) {
            infoRow(container, getString(R.string.profile_group_gpu), getString(R.string.profile_not_supported))
            return
        }

        pLevelRow(container, R.string.profile_term_gpu_min_pl, "min_pwrlevel")
        pLevelRow(container, R.string.profile_term_gpu_max_pl, "max_pwrlevel")
        pLevelRow(container, R.string.profile_term_gpu_default_pl, "default_pwrlevel")
        pLevelRow(container, R.string.profile_term_gpu_thermal_pl, "thermal_pwrlevel")

        val throttlingPath = listOf("gpu", "throttling")
        if (hasKey(throttlingPath)) {
            switchRow(
                container,
                getString(R.string.profile_term_gpu_throttling),
                getValue(throttlingPath) == "1",
                isChanged(throttlingPath),
                if (readOnly) null else { checked ->
                    setValue(throttlingPath, if (checked) "1" else "0")
                    rebuild()
                }
            )
        }
    }

    private fun pLevelRow(container: LinearLayout, titleRes: Int, key: String) {
        val path = listOf("gpu", key)
        if (!hasKey(path)) return
        pathRow(container, getString(titleRes), path, ::pLevelLabel, {
            pick(
                getString(R.string.profile_picker_level),
                adrenoPLevels.map { it to pLevelLabel(it) },
                getValue(path)
            ) { value ->
                setValue(path, value)
                rebuild()
            }
        })
    }

    // ------------------------------------------------------------------ cpusets
    private fun buildCpuset() {
        val container = binding.cpuCpusetList
        for (key in jsonKeys(listOf("cpuset"))) {
            val path = listOf("cpuset", key)
            pathRow(container, cpusetLabel(key), path, ::plainLabel, {
                val current = parseCpuset(getValue(path).orEmpty())
                val options = ArrayList<SelectItem>()
                for (cpu in 0 until coreCount) {
                    options += SelectItem().apply {
                        title = "CPU $cpu"
                        value = "$cpu"
                        selected = current.getOrElse(cpu) { false }
                    }
                }
                openMultiplePicker(getString(R.string.profile_picker_cores), options, object : PickerCallback2 {
                    override fun onSelected(result: BooleanArray) {
                        if (result.isEmpty()) return
                        setValue(path, parseCpuset(result))
                        rebuild()
                    }
                })
            })
        }
    }

    // ----------------------------------------------------------------- scheduler
    private fun buildSched() {
        val container = binding.cpuSchedList
        for (key in jsonKeys(listOf("sched"))) {
            val path = listOf("sched", key)
            if (locked.contains("sched_boost_top_app") && key == "boost_top_app") {
                infoRow(container, getString(R.string.profile_term_sched_boost_top_app))
                continue
            }
            when (key) {
                "walt_rotate_big_tasks", "prefer_sync_wakee_to_waker", "top_app_prefer_idle" -> switchRow(
                    container,
                    schedLabel(key),
                    getValue(path) == "1",
                    isChanged(path),
                    if (readOnly) null else { checked ->
                        setValue(path, if (checked) "1" else "0")
                        rebuild()
                    }
                )
                else -> pathRow(container, schedLabel(key), path, ::plainLabel, {
                    val range = schedRange(key)
                    val minValue = range.first
                    val maxValue = range.second
                    DialogNumberInput(this).showDialog(object : DialogNumberInput.DialogNumberInputRequest {
                        override var min: Int = minValue
                        override var max: Int = maxValue
                        override var default: Int = getValue(path)?.toIntOrNull() ?: minValue
                        override fun onApply(value: Int) {
                            setValue(path, "$value")
                            rebuild()
                        }
                    }, schedLabel(key))
                })
            }
        }
        if (locked.contains("uclamp")) {
            infoRow(container, getString(R.string.profile_term_uclamp))
        }
    }

    // --------------------------------------------------------------- CPU cores
    private fun buildCores() {
        val container = binding.cpuCores
        var row: LinearLayout? = null
        for (cpu in 0 until coreCount) {
            if (cpu % CORES_PER_ROW == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val path = listOf("cores_online", "$cpu")
            val effective = getValue(path)?.let { it == "1" } ?: cpuFrequencyUtils.getCoreOnlineState(cpu)
            val checkBox = CheckBox(this).apply {
                text = "CPU$cpu"
                isChecked = effective
                isEnabled = !readOnly
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setOnClickListener {
                    setValue(path, if ((it as CheckBox).isChecked) "1" else "0")
                    updateStatus()
                }
            }
            row?.addView(checkBox)
        }
    }

    // -------------------------------------------------------- thermal/mem/storage
    private fun buildMisc() {
        val container = binding.cpuMiscList

        if (hasKey(listOf("thermal_sconfig"))) {
            val path = listOf("thermal_sconfig")
            pathRow(container, getString(R.string.profile_term_thermal_sconfig), path, ::plainLabel, {
                DialogNumberInput(this).showDialog(object : DialogNumberInput.DialogNumberInputRequest {
                    override var min: Int = 0
                    override var max: Int = 12
                    override var default: Int = getValue(path)?.toIntOrNull() ?: 0
                    override fun onApply(value: Int) {
                        setValue(path, "$value")
                        rebuild()
                    }
                }, getString(R.string.profile_term_thermal_sconfig))
            })
        }

        if (hasKey(listOf("lmk"))) {
            val path = listOf("lmk", "minfree")
            pathRow(container, getString(R.string.profile_term_lmk), path, ::lmkLabel, {
                pick(
                    getString(R.string.profile_picker_lmk),
                    lmkItems(),
                    getValue(path)
                ) { value ->
                    setValue(path, value)
                    rebuild()
                }
            })
        }

        if (hasKey(listOf("ufs"))) {
            val path = listOf("ufs")
            pathRow(container, getString(R.string.profile_term_ufs), path, ::ufsLabel, {
                pick(
                    getString(R.string.profile_term_ufs),
                    listOf("save" to getString(R.string.profile_value_power_save), "perf" to getString(R.string.profile_value_performance)),
                    getValue(path)
                ) { value ->
                    setValue(path, value)
                    rebuild()
                }
            })
        }

        if (locked.contains("msm_thermal")) {
            infoRow(container, getString(R.string.profile_term_msm_thermal))
        }
    }

    // ------------------------------------------------------------- row builders
    private fun sectionTitle(container: LinearLayout, text: String) {
        val view = layoutInflater.inflate(R.layout.cpu_section_title, container, false) as TextView
        view.text = text
        container.addView(view)
    }

    private fun pathRow(
        container: LinearLayout,
        title: String,
        path: List<String>,
        label: (String?) -> String,
        pick: (() -> Unit)?
    ) {
        valueRow(
            container,
            title,
            label(getValue(path)),
            isChanged(path),
            if (readOnly) null else pick?.let { action -> { _: View -> action() } }
        )
    }

    private fun valueRow(
        container: LinearLayout,
        title: String,
        value: String,
        changed: Boolean,
        onClick: ((View) -> Unit)?
    ): View {
        val row = layoutInflater.inflate(R.layout.cpu_row_value, container, false)
        row.findViewById<TextView>(R.id.row_title).text = title
        val valueView = row.findViewById<TextView>(R.id.row_value)
        valueView.text = if (changed) "● $value" else value
        if (!changed) {
            valueView.alpha = 0.9f
        }
        if (onClick != null) {
            row.setOnClickListener(onClick)
        } else {
            row.isClickable = false
            row.alpha = 0.8f
        }
        container.addView(row)
        return row
    }

    private fun switchRow(
        container: LinearLayout,
        title: String,
        checked: Boolean,
        changed: Boolean,
        onChange: ((Boolean) -> Unit)?
    ): View {
        val row = layoutInflater.inflate(R.layout.cpu_row_switch, container, false)
        val titleView = row.findViewById<TextView>(R.id.row_title)
        titleView.text = title
        if (changed) titleView.setTextColor(attrColor(R.attr.sceneActionBg, Color.GRAY))
        val switch = row.findViewById<Switch>(R.id.row_switch)
        switch.isChecked = checked
        switch.isEnabled = onChange != null
        if (onChange != null) {
            switch.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        container.addView(row)
        return row
    }

    private fun infoRow(container: ViewGroup, title: String, desc: String? = null) {
        val row = layoutInflater.inflate(R.layout.cpu_row_info, container, false)
        row.findViewById<TextView>(R.id.row_title).text = title
        if (desc != null) {
            row.findViewById<TextView>(R.id.row_desc).apply {
                text = desc
                visibility = View.VISIBLE
            }
        }
        row.findViewById<TextView>(R.id.row_value).apply {
            text = getString(R.string.profile_not_supported)
            visibility = View.VISIBLE
        }
        container.addView(row)
    }

    // ------------------------------------------------------------- JSON helpers
    private fun getValue(path: List<String>): String? {
        val parent = navigate(draft, path, false) ?: return null
        val leaf = path.last()
        if (!parent.has(leaf) || parent.isNull(leaf)) return null
        return parent.opt(leaf)?.toString()
    }

    private fun setValue(path: List<String>, value: String) {
        val parent = navigate(draft, path, true) ?: return
        parent.put(path.last(), value.toLongOrNull() ?: value)
    }

    private fun getPresetValue(path: List<String>): String? {
        val root = preset ?: return null
        val parent = navigate(root, path, false) ?: return null
        val leaf = path.last()
        if (!parent.has(leaf) || parent.isNull(leaf)) return null
        return parent.opt(leaf)?.toString()
    }

    private fun isChanged(path: List<String>): Boolean =
        !valuesEqual(getValue(path), getPresetValue(path))

    private fun valuesEqual(a: String?, b: String?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        return if (na != null && nb != null) na == nb else a == b
    }

    private fun hasKey(path: List<String>): Boolean {
        if (path.isEmpty()) return false
        val leaf = path.last()
        navigate(draft, path, false)?.let { return it.has(leaf) }
        val root = preset ?: return false
        return navigate(root, path, false)?.has(leaf) ?: false
    }

    /** Union of object keys at [path] across draft and preset (sorted). */
    private fun jsonKeys(path: List<String>): List<String> {
        val keys = LinkedHashSet<String>()
        objectAt(draft, path)?.keys()?.forEach { keys += it }
        preset?.let { root -> objectAt(root, path)?.keys()?.forEach { keys += it } }
        return keys.sorted()
    }

    /** Object reached by descending through every element of [path]. */
    private fun objectAt(root: JSONObject, path: List<String>): JSONObject? {
        var current = root
        for (key in path) {
            current = current.optJSONObject(key) ?: return null
        }
        return current
    }

    private fun navigate(root: JSONObject, path: List<String>, create: Boolean): JSONObject? {
        var current = root
        for (index in 0 until path.size - 1) {
            val key = path[index]
            var next = current.optJSONObject(key)
            if (next == null) {
                if (!create) return null
                next = JSONObject()
                current.put(key, next)
            }
            current = next
        }
        return current
    }

    // ------------------------------------------------------------------ saving
    private fun saveProfile() {
        val snapshot = JSONObject(draft.toString())
        Thread {
            val result = ProfileStore.save(this, mode, snapshot)
            runOnUiThread {
                if (result.isSuccess) {
                    Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                    Thread {
                        loadDraft()
                        runOnUiThread { rebuild() }
                    }.start()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.profile_save_failed, result.exceptionOrNull()?.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun resetProfile() {
        DialogHelper.confirm(
            this,
            getString(R.string.profile_reset),
            getString(R.string.profile_reset_confirm),
            DialogHelper.DialogButton(getString(R.string.profile_reset), Runnable {
                Thread {
                    val result = ProfileStore.resetToPreset(this, mode)
                    runOnUiThread {
                        if (result.isSuccess) {
                            Toast.makeText(this, R.string.profile_reset_done, Toast.LENGTH_SHORT).show()
                        }
                        Thread {
                            loadDraft()
                            runOnUiThread { rebuild() }
                        }.start()
                    }
                }.start()
            }),
            DialogHelper.DialogButton(getString(R.string.profile_cancel), null)
        )
    }

    // -------------------------------------------------------------- pickers/UI
    private fun pick(title: String, items: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
        val options = ArrayList<SelectItem>()
        var selectedIndex = -1
        items.forEachIndexed { index, (value, labelText) ->
            options += SelectItem().apply {
                this.title = labelText
                this.value = value
            }
            if (value == current) selectedIndex = index
        }
        openMultiplePicker(title, options, selectedIndex, object : PickerCallback {
            override fun onSelected(result: String) {
                onPick(result)
            }
        })
    }

    private fun openMultiplePicker(
        dialogTitle: String,
        options: ArrayList<SelectItem>,
        selectedIndex: Int,
        pickerCallback: PickerCallback
    ) {
        val selected = ArrayList<SelectItem>().apply {
            if (selectedIndex > -1 && selectedIndex < options.size) add(options[selectedIndex])
        }
        DialogItemChooser2(themeMode.isDarkMode, options, selected, false, object : DialogItemChooser2.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                if (selected.isNotEmpty()) {
                    pickerCallback.onSelected("" + selected.first().value)
                }
            }
        }).setTitle(dialogTitle).show(supportFragmentManager, "cpu-control")
    }

    private fun openMultiplePicker(dialogTitle: String, options: ArrayList<SelectItem>, pickerCallback: PickerCallback2) {
        DialogItemChooser(themeMode.isDarkMode, options, true, object : DialogItemChooser.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                if (status.isNotEmpty()) {
                    pickerCallback.onSelected(status)
                }
            }
        }).setTitle(dialogTitle).show(supportFragmentManager, "cpu-control")
    }

    // ------------------------------------------------------------------ labels
    private fun profileTitle(mode: String): String = getString(
        when (mode) {
            ProfileKey.POWERSAVE -> R.string.powersave
            ProfileKey.BALANCE -> R.string.balance
            ProfileKey.PERFORMANCE -> R.string.performance
            else -> R.string.fast
        }
    )

    private fun clusterLabel(cluster: Int): String {
        val cores = coreLists.getOrNull(cluster).orEmpty()
        return when {
            cores.size > 1 -> "CPU ${cores.first()}–${cores.last()}"
            cores.size == 1 -> "CPU ${cores.first()}"
            else -> "CPU $cluster"
        }
    }

    private fun policyFor(cluster: Int): String =
        DeviceCaps.POLICIES.getOrNull(cluster) ?: "policy$cluster"

    private fun khzLabel(raw: String?): String {
        val value = raw?.toLongOrNull() ?: return "--"
        return if (value >= 1000) "${value / 1000} MHz" else "$value kHz"
    }

    private fun rateLabel(raw: String?): String {
        val value = raw?.toLongOrNull() ?: return "--"
        return if (value <= 0L) getString(R.string.profile_value_unlimited) else "$value µs"
    }

    private fun msLabel(raw: String?): String {
        val value = raw?.toLongOrNull() ?: return "--"
        return if (value <= 0L) "0 ms" else "$value ms"
    }

    private fun plainLabel(raw: String?): String = raw ?: "--"

    private fun pLevelLabel(raw: String?): String {
        val level = raw?.toIntOrNull() ?: return "--"
        val mhz = gpuFreqTable.getOrNull(level)
        return if (mhz != null) "p$level · $mhz MHz" else "p$level"
    }

    private fun ufsLabel(raw: String?): String = when (raw) {
        "save" -> getString(R.string.profile_value_power_save)
        "perf" -> getString(R.string.profile_value_performance)
        null -> "--"
        else -> raw
    }

    private fun lmkLabel(raw: String?): String {
        val pages = raw?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: return "--"
        if (pages.isEmpty()) return "--"
        val first = pages.first() * 4 / 1024
        val last = pages.last() * 4 / 1024
        return "$first–$last MB"
    }

    private fun cpusetLabel(key: String): String = when (key) {
        "background" -> "Background"
        "system-background" -> "System background"
        "foreground" -> "Foreground"
        "top-app" -> "Top app"
        "restricted" -> "Restricted"
        else -> key
    }

    private fun schedLabel(key: String): String = when (key) {
        "downmigrate" -> getString(R.string.profile_term_sched_downmigrate)
        "upmigrate" -> getString(R.string.profile_term_sched_upmigrate)
        "group_downmigrate" -> getString(R.string.profile_term_sched_group_down)
        "group_upmigrate" -> getString(R.string.profile_term_sched_group_up)
        "boost" -> getString(R.string.profile_term_sched_boost)
        "boost_top_app" -> getString(R.string.profile_term_sched_boost_top_app)
        "top_app_boost" -> getString(R.string.profile_term_top_app_boost)
        "top_app_prefer_idle" -> getString(R.string.profile_term_top_app_prefer_idle)
        else -> key
    }

    private fun schedRange(key: String): Pair<Int, Int> = when (key) {
        "top_app_boost" -> -100 to 100
        "boost" -> 0 to 3
        "sched_latency_ns", "sched_min_granularity_ns", "sched_wakeup_granularity_ns" -> 0 to 100_000_000
        else -> 0 to 1024
    }

    private fun lmkItems(): List<Pair<String, String>> {
        val values = LinkedHashSet<String>()
        getValue(listOf("lmk", "minfree"))?.let { values += it }
        ProfileStore.doc(this)?.let { doc ->
            for (candidate in ProfileKey.ALL_WITH_RELEASE) {
                doc.presetProfile(candidate)
                    ?.optJSONObject("lmk")
                    ?.optString("minfree")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { values += it }
            }
        }
        return values.map { it to lmkLabel(it) }
    }

    private fun governorItems(cluster: Int): List<Pair<String, String>> =
        (clusterGovernors[cluster] ?: emptyArray()).map { it to it }

    // ------------------------------------------------------------- small utils
    private fun parseCpuset(value: String): List<Boolean> {
        val cores = ArrayList<Boolean>()
        for (cpu in 0 until coreCount) cores.add(false)
        if (value.isEmpty() || value == "error") return cores
        for (group in value.split(",")) {
            if (group.contains("-")) {
                val range = group.split("-")
                val min = range.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val max = range.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                for (cpu in min..max) if (cpu in 0 until cores.size) cores[cpu] = true
            } else {
                val cpu = group.trim().toIntOrNull() ?: continue
                if (cpu in 0 until cores.size) cores[cpu] = true
            }
        }
        return cores
    }

    private fun parseCpuset(cores: BooleanArray): String {
        val selected = ArrayList<String>()
        for (index in cores.indices) if (cores[index]) selected += "$index"
        return if (selected.isEmpty()) "" else selected.joinToString(",")
    }

    private fun attrColor(attr: Int, fallback: Int): Int {
        val typed = TypedValue()
        return if (theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, typed.resourceId)
            else typed.data
        } else {
            fallback
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CORES_PER_ROW = 4
        private val RATE_LIMITS = listOf("0", "500", "1000", "2000", "5000", "10000", "20000", "50000")
        private val MS_VALUES = listOf("0", "20", "30", "50", "80", "120", "200", "300", "500", "800", "1000")
    }
}
