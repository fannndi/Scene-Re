package com.omarea.vtools.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.omarea.Scene
import com.omarea.common.model.SelectItem
import com.omarea.common.ui.DialogItemChooser
import com.omarea.common.ui.DialogItemChooser2
import com.omarea.library.shell.CpuFrequencyUtils
import com.omarea.library.shell.GpuUtils
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.ProfileStore
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityProfileEditorBinding
import com.omarea.vtools.dialogs.DialogNumberInput
import java.io.File
import java.util.Locale

/**
 * 调频模式编辑器（Device Profile -> Power profiles）
 *
 * 每个模式的数据来自 profiles/<mode>.json，编辑保存后写入用户版，
 * 调频脚本（powercfg.sh）优先读取用户版。
 */
class ActivityProfileEditor : ActivityBase() {
    private enum class FieldType { FREQ_LITTLE, FREQ_BIG, NUMBER, CHOICE, GPU_PL, CPUSET }

    private class Field(
        val key: String,
        val label: String,
        val type: FieldType,
        val numeric: Boolean,
        val min: Int = 0,
        val max: Int = 100,
        val options: Array<String> = arrayOf()
    )

    private lateinit var binding: ActivityProfileEditorBinding
    private lateinit var mode: String
    private val store by lazy { ProfileStore(this) }
    private val values = HashMap<String, Any>()
    private val valueViews = HashMap<String, TextView>()

    private var littleFreqs: Array<String> = arrayOf()
    private var bigFreqs: Array<String> = arrayOf()
    private var gpuFreqsMhz: Array<String> = arrayOf()
    private var gpuLevels: Array<String> = arrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()

        mode = intent.getStringExtra("mode") ?: ModeSwitcher.BALANCE

        littleFreqs = CpuFrequencyUtils().getAvailableFrequencies(0)
        bigFreqs = CpuFrequencyUtils().getAvailableFrequencies(1)
        if (GpuUtils.supported() && GpuUtils.isAdrenoGPU()) {
            gpuFreqsMhz = GpuUtils.getFreqTableMhz()
            gpuLevels = GpuUtils.getAdrenoGPUPowerLevels()
        }

        loadValues()
        buildFields()

        binding.profileSave.setOnClickListener { save() }
        binding.profileReset.setOnClickListener { reset() }
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.profile_editor_title) + " · " + ModeSwitcher.getModName(mode)
        updateHint()
    }

    // ---------------- 字段定义 ----------------

    private fun sections(): List<Pair<String, List<Field>>> {
        val cpu = listOf(
            Field("little_min", "Little min frequency", FieldType.FREQ_LITTLE, true),
            Field("little_max", "Little max frequency", FieldType.FREQ_LITTLE, true),
            Field("big_min", "Big min frequency", FieldType.FREQ_BIG, true),
            Field("big_max", "Big max frequency", FieldType.FREQ_BIG, true),
            Field("hispeed_little", "Little hispeed_freq", FieldType.FREQ_LITTLE, true),
            Field("hispeed_big", "Big hispeed_freq", FieldType.FREQ_BIG, true),
            Field("hispeed_load_little", "Little hispeed_load", FieldType.NUMBER, true, 0, 100),
            Field("hispeed_load_big", "Big hispeed_load", FieldType.NUMBER, true, 0, 100),
            Field("rate_limit_little_down", "Little down_rate_limit (µs)", FieldType.NUMBER, true, 0, 100000),
            Field("rate_limit_little_up", "Little up_rate_limit (µs)", FieldType.NUMBER, true, 0, 100000),
            Field("rate_limit_big_down", "Big down_rate_limit (µs)", FieldType.NUMBER, true, 0, 100000),
            Field("rate_limit_big_up", "Big up_rate_limit (µs)", FieldType.NUMBER, true, 0, 100000),
            Field("input_boost_little", "Input boost little", FieldType.FREQ_LITTLE, true),
            Field("input_boost_big", "Input boost big", FieldType.FREQ_BIG, true),
            Field("input_boost_ms", "Input boost duration (ms)", FieldType.NUMBER, true, 0, 5000)
        )
        val scheduler = listOf(
            Field("sched_boost_top_app", "sched_boost_top_app", FieldType.CHOICE, true, options = arrayOf("0", "1")),
            Field("sched_boost", "sched_boost", FieldType.CHOICE, true, options = arrayOf("0", "1", "2")),
            Field("stune_prefer_idle", "top-app prefer_idle", FieldType.CHOICE, true, options = arrayOf("0", "1")),
            Field("stune_boost", "top-app boost", FieldType.NUMBER, true, 0, 100),
            Field("sched_down", "sched_downmigrate", FieldType.NUMBER, true, 0, 1024),
            Field("sched_up", "sched_upmigrate", FieldType.NUMBER, true, 0, 1024),
            Field("sched_group_down", "group_downmigrate", FieldType.NUMBER, true, 0, 1024),
            Field("sched_group_up", "group_upmigrate", FieldType.NUMBER, true, 0, 1024),
            Field("core_ctl_big", "Big core_ctl", FieldType.CHOICE, false, options = arrayOf("on", "off")),
            Field("core_ctl_big_min", "core_ctl min_cpus", FieldType.NUMBER, true, 0, 2),
            Field("core_ctl_big_busy_up", "core_ctl busy_up", FieldType.NUMBER, true, 0, 100),
            Field("core_ctl_big_busy_down", "core_ctl busy_down", FieldType.NUMBER, true, 0, 100),
            Field("cpuset_bg", "background cpus", FieldType.CPUSET, false),
            Field("cpuset_sysbg", "system-background cpus", FieldType.CPUSET, false),
            Field("cpuset_fg", "foreground cpus", FieldType.CPUSET, false),
            Field("cpuset_top", "top-app cpus", FieldType.CPUSET, false)
        )
        val gpu = listOf(
            Field("gpu_governor", "GPU governor", FieldType.CHOICE, false, options = GpuUtils.getGovernors()),
            Field("gpu_max_pl", "GPU max power level", FieldType.GPU_PL, true),
            Field("gpu_min_pl", "GPU min power level", FieldType.GPU_PL, true),
            Field("gpu_bw_floor", "gpubw floor", FieldType.CHOICE, false, options = arrayOf("on", "off"))
        )
        val io = listOf(
            Field("blk_scheduler", "Block scheduler", FieldType.CHOICE, false, options = blockSchedulers()),
            Field("read_ahead_kb", "read_ahead_kb", FieldType.NUMBER, true, 0, 4096),
            Field("nr_requests", "nr_requests", FieldType.NUMBER, true, 0, 1024),
            Field("iostats", "iostats", FieldType.CHOICE, true, options = arrayOf("0", "1")),
            Field("devfreq_bw", "Bus bandwidth", FieldType.CHOICE, false, options = arrayOf("min", "max", "always"))
        )
        return listOf(
            "CPU" to cpu,
            "Scheduler" to scheduler,
            "GPU" to gpu,
            "I/O" to io
        )
    }

    // ---------------- 构建界面 ----------------

    private fun buildFields() {
        val inflater = LayoutInflater.from(this)
        val container = binding.profileFields
        container.removeAllViews()
        valueViews.clear()

        for ((sectionTitle, fields) in sections()) {
            val section = inflater.inflate(R.layout.item_profile_section, container, false)
            val fieldContainer = section.findViewById<LinearLayout>(R.id.section_fields)
            section.findViewById<TextView>(R.id.section_title).text = sectionTitle

            for (field in fields) {
                val row = inflater.inflate(R.layout.item_profile_field, fieldContainer, false)
                row.findViewById<TextView>(R.id.field_label).text = field.label
                row.findViewById<TextView>(R.id.field_key).text = field.key
                val valueView = row.findViewById<TextView>(R.id.field_value)
                valueView.text = display(field)
                row.setOnClickListener { editField(field, valueView) }
                fieldContainer.addView(row)
                valueViews[field.key] = valueView
            }

            container.addView(section)
        }
    }

    private fun loadValues() {
        val json = store.merged(mode)
        for ((_, fields) in sections()) {
            for (field in fields) {
                values[field.key] = if (field.numeric) {
                    json.optInt(field.key, 0)
                } else {
                    json.optString(field.key, "")
                }
            }
        }
    }

    private fun intValue(field: Field): Int {
        return when (val v = values[field.key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun display(field: Field): String {
        return when (field.type) {
            FieldType.FREQ_LITTLE, FieldType.FREQ_BIG -> freqLabel(intValue(field))
            FieldType.GPU_PL -> gpuPlLabel(intValue(field))
            else -> values[field.key]?.toString() ?: ""
        }
    }

    private fun refreshValues() {
        for ((_, fields) in sections()) {
            for (field in fields) {
                valueViews[field.key]?.text = display(field)
            }
        }
    }

    // ---------------- 编辑 ----------------

    private fun editField(field: Field, view: TextView) {
        when (field.type) {
            FieldType.FREQ_LITTLE -> pickFrequency(field, view, littleFreqs)
            FieldType.FREQ_BIG -> pickFrequency(field, view, bigFreqs)
            FieldType.GPU_PL -> pickGpuLevel(field, view)
            FieldType.NUMBER -> pickNumber(field, view)
            FieldType.CHOICE -> pickChoice(field, view)
            FieldType.CPUSET -> pickCpuset(field, view)
        }
    }

    private fun pickFrequency(field: Field, view: TextView, freqs: Array<String>) {
        val items = ArrayList<SelectItem>()
        for (freq in freqs) {
            items.add(SelectItem().apply {
                title = freqLabel(freq.toIntOrNull() ?: 0)
                value = freq
            })
        }
        val current = intValue(field).toString()
        val selected = ArrayList<SelectItem>()
        items.firstOrNull { it.value == current }?.let { selected.add(it) }

        DialogItemChooser2(themeMode.isDarkMode, items, selected, false, object : DialogItemChooser2.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                val value = selected.firstOrNull()?.value?.toIntOrNull() ?: return
                values[field.key] = value
                view.text = freqLabel(value)
            }
        }).setTitle(field.label).show(supportFragmentManager, "profile-editor")
    }

    private fun pickGpuLevel(field: Field, view: TextView) {
        val items = ArrayList<SelectItem>()
        for (level in gpuLevels) {
            items.add(SelectItem().apply {
                title = gpuPlLabel(level.toIntOrNull() ?: 0)
                value = level
            })
        }
        val current = intValue(field).toString()
        val selected = ArrayList<SelectItem>()
        items.firstOrNull { it.value == current }?.let { selected.add(it) }

        DialogItemChooser2(themeMode.isDarkMode, items, selected, false, object : DialogItemChooser2.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                val value = selected.firstOrNull()?.value?.toIntOrNull() ?: return
                values[field.key] = value
                view.text = gpuPlLabel(value)
            }
        }).setTitle(field.label).show(supportFragmentManager, "profile-editor")
    }

    private fun pickNumber(field: Field, view: TextView) {
        DialogNumberInput(this).showDialog(object : DialogNumberInput.DialogNumberInputRequest {
            override var min = field.min
            override var max = field.max
            override var default = intValue(field)

            override fun onApply(value: Int) {
                values[field.key] = value
                view.text = value.toString()
            }
        })
    }

    private fun pickChoice(field: Field, view: TextView) {
        val items = ArrayList<SelectItem>()
        for (option in field.options) {
            items.add(SelectItem().apply {
                title = option
                value = option
            })
        }
        val current = values[field.key]?.toString() ?: ""
        val selected = ArrayList<SelectItem>()
        items.firstOrNull { it.value == current }?.let { selected.add(it) }

        DialogItemChooser2(themeMode.isDarkMode, items, selected, false, object : DialogItemChooser2.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                val value = selected.firstOrNull()?.value ?: return
                values[field.key] = if (field.numeric) (value.toIntOrNull() ?: 0) else value
                view.text = value
            }
        }).setTitle(field.label).show(supportFragmentManager, "profile-editor")
    }

    private fun pickCpuset(field: Field, view: TextView) {
        val items = ArrayList<SelectItem>()
        for (core in 0..7) {
            items.add(SelectItem().apply {
                title = "CPU$core"
                value = "$core"
            })
        }
        val cores = parseCores(values[field.key]?.toString() ?: "")
        val selected = ArrayList<SelectItem>()
        for (core in 0..7) {
            if (cores[core]) {
                selected.add(items[core])
            }
        }

        DialogItemChooser(themeMode.isDarkMode, items, true, object : DialogItemChooser.Callback {
            override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                val mask = buildMask(status)
                if (mask.isNotEmpty()) {
                    values[field.key] = mask
                    view.text = mask
                }
            }
        }, true).setTitle(field.label).show(supportFragmentManager, "profile-editor")
    }

    // ---------------- 保存 / 重置 ----------------

    private fun save() {
        val payload = HashMap<String, Any>()
        for ((_, fields) in sections()) {
            for (field in fields) {
                val value = values[field.key] ?: continue
                payload[field.key] = value
            }
        }

        if (store.save(mode, payload)) {
            Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            updateHint()
            applyIfCurrent()
        } else {
            Toast.makeText(this, R.string.profile_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reset() {
        store.reset(mode)
        loadValues()
        refreshValues()
        Toast.makeText(this, R.string.profile_reset_done, Toast.LENGTH_SHORT).show()
        updateHint()
        applyIfCurrent()
    }

    /** 如果编辑的正是当前生效模式，立即重新应用 */
    private fun applyIfCurrent() {
        if (ModeSwitcher.getCurrentPowerMode() != mode) {
            return
        }
        Thread {
            try {
                ModeSwitcher().executePowercfgMode(mode, Scene.thisPackageName)
            } catch (ex: Exception) {
            }
        }.start()
    }

    private fun updateHint() {
        binding.profileHint.text = if (store.isCustomized(mode)) {
            getString(R.string.profile_customized_hint)
        } else {
            getString(R.string.profile_default_hint)
        }
    }

    // ---------------- 工具 ----------------

    private fun freqLabel(khz: Int): String {
        val mhz = khz / 1000.0
        return if (mhz % 1.0 == 0.0) {
            "${mhz.toInt()} MHz"
        } else {
            String.format(Locale.US, "%.1f MHz", mhz)
        }
    }

    private fun gpuPlLabel(level: Int): String {
        val freq = gpuFreqsMhz.getOrNull(level)
        return if (freq != null) "pl $level · $freq MHz" else "pl $level"
    }

    private fun blockSchedulers(): Array<String> {
        return try {
            val line = File("/sys/block/sda/queue/scheduler").readText()
            line.replace("[", "").replace("]", "").trim().split(Regex("\\s+")).toTypedArray()
        } catch (ex: Exception) {
            arrayOf("noop", "deadline", "cfq")
        }
    }

    private fun parseCores(mask: String): BooleanArray {
        val result = BooleanArray(8)
        for (part in mask.split(",")) {
            val p = part.trim()
            if (p.contains("-")) {
                val bounds = p.split("-")
                val from = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val to = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (from != null && to != null) {
                    for (i in from..to) {
                        if (i in 0..7) result[i] = true
                    }
                }
            } else {
                p.toIntOrNull()?.let {
                    if (it in 0..7) result[it] = true
                }
            }
        }
        return result
    }

    private fun buildMask(cores: BooleanArray): String {
        val builder = StringBuilder()
        var i = 0
        while (i < cores.size) {
            if (!cores[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < cores.size && cores[j + 1]) {
                j++
            }
            if (builder.isNotEmpty()) {
                builder.append(",")
            }
            if (i == j) {
                builder.append(i)
            } else {
                builder.append(i).append("-").append(j)
            }
            i = j + 1
        }
        return builder.toString()
    }
}
