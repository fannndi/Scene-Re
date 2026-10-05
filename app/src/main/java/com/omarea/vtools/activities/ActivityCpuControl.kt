package com.omarea.vtools.activities

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omarea.Scene
import com.omarea.library.shell.CpuFrequencyUtils
import com.omarea.library.shell.GpuUtils
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.store.ProfileStore
import com.omarea.store.SpfConfig
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityCpuControlBinding
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Device Profile —— 单屏模式参数编辑器（Compose + Miuix）
 *
 * 一个屏幕内完成：选择模式 -> 调整参数（全部内联控件，无二级菜单）-> 保存并应用。
 * 参数与 profiles/<mode>.json 一一对应，Basic 层级会忽略的项标注 "Pro"。
 */
class ActivityCpuControl : ActivityBase() {
    companion object {
        private val NUMERIC_KEYS = arrayOf(
            "little_min", "little_max", "big_min", "big_max",
            "hispeed_little", "hispeed_big",
            "hispeed_load_little", "hispeed_load_big",
            "rate_limit_little_down", "rate_limit_little_up",
            "rate_limit_big_down", "rate_limit_big_up",
            "input_boost_little", "input_boost_big", "input_boost_ms",
            "sched_boost_top_app", "sched_boost",
            "stune_prefer_idle", "stune_boost",
            "sched_down", "sched_up", "sched_group_down", "sched_group_up",
            "core_ctl_big_min", "core_ctl_big_busy_up", "core_ctl_big_busy_down",
            "gpu_max_pl", "gpu_min_pl",
            "read_ahead_kb", "nr_requests", "iostats"
        )
        private val STRING_KEYS = arrayOf(
            "core_ctl_big",
            "cpuset_bg", "cpuset_sysbg", "cpuset_fg", "cpuset_top",
            "gpu_governor", "gpu_bw_floor",
            "blk_scheduler", "devfreq_bw"
        )
    }

    private lateinit var binding: ActivityCpuControlBinding
    private val store by lazy { ProfileStore(this) }

    private val selectedMode = mutableStateOf(ModeSwitcher.BALANCE)
    private val activeMode = mutableStateOf("")
    private val tier = mutableStateOf(SpfConfig.PROFILE_TIER_BASIC)
    private val values = mutableStateMapOf<String, Any>()

    private var littleFreqs: List<Int> = emptyList()
    private var bigFreqs: List<Int> = emptyList()
    private var gpuLevels: List<Int> = emptyList()
    private var gpuFreqMhz: List<String> = emptyList()
    private var gpuGovernors: List<String> = emptyList()
    private var schedulers: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCpuControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()

        littleFreqs = CpuFrequencyUtils().getAvailableFrequencies(0)
            .mapNotNull { it.toIntOrNull() }.sorted()
        bigFreqs = CpuFrequencyUtils().getAvailableFrequencies(1)
            .mapNotNull { it.toIntOrNull() }.sorted()
        if (GpuUtils.supported() && GpuUtils.isAdrenoGPU()) {
            gpuFreqMhz = GpuUtils.getFreqTableMhz().toList()
            gpuLevels = GpuUtils.getAdrenoGPUPowerLevels().mapNotNull { it.toIntOrNull() }.sorted()
            gpuGovernors = GpuUtils.getGovernors().toList()
        }
        schedulers = readSchedulers()

        val current = ModeSwitcher.getCurrentPowerMode()
        selectedMode.value = if (current.isNotEmpty() && current != ProfileStore.SCREEN_OFF) current else ModeSwitcher.BALANCE
        loadValues(selectedMode.value)

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            val controller = ThemeController(
                if (themeMode.isDarkMode) ColorSchemeMode.Dark else ColorSchemeMode.Light
            )
            MiuixTheme(controller = controller) {
                DeviceProfileScreen(
                    selectedMode = selectedMode.value,
                    activeMode = activeMode.value,
                    tier = tier.value,
                    values = values,
                    littleFreqs = littleFreqs,
                    bigFreqs = bigFreqs,
                    gpuLevels = gpuLevels,
                    gpuFreqMhz = gpuFreqMhz,
                    gpuGovernors = gpuGovernors,
                    schedulers = schedulers,
                    onSelectMode = {
                        selectedMode.value = it
                        loadValues(it)
                    },
                    onSave = { saveAndApply() },
                    onReset = { resetProfile() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        title = getString(R.string.menu_core_control)
        activeMode.value = ModeSwitcher.getCurrentPowerMode()
        tier.value = Scene.globalConfig.getString(
            SpfConfig.GLOBAL_SPF_PROFILE_TIER, SpfConfig.GLOBAL_SPF_PROFILE_TIER_DEFAULT
        ) ?: SpfConfig.PROFILE_TIER_BASIC
    }

    // ---------------- 数据 ----------------

    private fun loadValues(mode: String) {
        val json = store.merged(mode)
        values.clear()
        for (key in NUMERIC_KEYS) {
            values[key] = json.optInt(key, 0)
        }
        for (key in STRING_KEYS) {
            values[key] = json.optString(key, "")
        }
    }

    private fun saveAndApply() {
        val payload = HashMap<String, Any>()
        for (key in NUMERIC_KEYS) {
            payload[key] = intOf(key)
        }
        for (key in STRING_KEYS) {
            payload[key] = strOf(key)
        }
        if (!store.save(selectedMode.value, payload)) {
            Scene.toast(getString(R.string.profile_save_failed))
            return
        }
        Scene.toast(getString(R.string.profile_saved))

        val mode = selectedMode.value
        val enabled = Scene.getBoolean(
            SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL, SpfConfig.GLOBAL_SPF_DYNAMIC_CONTROL_DEFAULT
        )
        if (enabled && ModeSwitcher.getCurrentPowerMode() == mode) {
            Thread {
                try {
                    ModeSwitcher().executePowercfgMode(mode, Scene.thisPackageName)
                } catch (ex: Exception) {
                }
            }.start()
        }
    }

    private fun resetProfile() {
        store.reset(selectedMode.value)
        loadValues(selectedMode.value)
        Scene.toast(getString(R.string.profile_reset_done))
    }

    private fun intOf(key: String): Int {
        return when (val v = values[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun strOf(key: String): String = values[key]?.toString() ?: ""

    private fun readSchedulers(): List<String> {
        return try {
            File("/sys/block/sda/queue/scheduler").readText()
                .replace("[", "").replace("]", "").trim()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
        } catch (ex: Exception) {
            listOf("noop", "deadline", "cfq")
        }
    }
}

// ---------------- Compose UI ----------------

@Composable
private fun DeviceProfileScreen(
    selectedMode: String,
    activeMode: String,
    tier: String,
    values: MutableMap<String, Any>,
    littleFreqs: List<Int>,
    bigFreqs: List<Int>,
    gpuLevels: List<Int>,
    gpuFreqMhz: List<String>,
    gpuGovernors: List<String>,
    schedulers: List<String>,
    onSelectMode: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    val basicTier = tier != SpfConfig.PROFILE_TIER_PRO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard {
            SectionTitle("Profile")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (mode in ProfileStore.EDITABLE_MODES) {
                    ModeChip(
                        label = ModeSwitcher.getModName(mode),
                        selected = mode == selectedMode,
                        active = mode == activeMode,
                        onClick = { onSelectMode(mode) }
                    )
                }
            }
            if (basicTier) {
                HintText("Basic tier: frequency caps, core_ctl and cpuset are ignored. Switch to Pro in Profile Service to apply them.")
            } else {
                HintText("Pro tier: all parameters below are applied.")
            }
            Spacer(Modifier.height(8.dp))
        }

        // -------- CPU --------
        SectionCard {
            SectionTitle("CPU")
            FreqSliderRow("Little min", "little_min", values, littleFreqs, proOnly = basicTier)
            FreqSliderRow("Little max", "little_max", values, littleFreqs, proOnly = basicTier)
            FreqSliderRow("Big min", "big_min", values, bigFreqs, proOnly = basicTier)
            FreqSliderRow("Big max", "big_max", values, bigFreqs, proOnly = basicTier)
            FreqSliderRow("Little hispeed", "hispeed_little", values, littleFreqs)
            FreqSliderRow("Big hispeed", "hispeed_big", values, bigFreqs)
            IntSliderRow("Little hispeed_load", "hispeed_load_little", values, 0..100)
            IntSliderRow("Big hispeed_load", "hispeed_load_big", values, 0..100)
            IntSliderRow("Little down_rate_limit (µs)", "rate_limit_little_down", values, 0..100000, step = 1000)
            IntSliderRow("Little up_rate_limit (µs)", "rate_limit_little_up", values, 0..100000, step = 1000)
            IntSliderRow("Big down_rate_limit (µs)", "rate_limit_big_down", values, 0..100000, step = 1000)
            IntSliderRow("Big up_rate_limit (µs)", "rate_limit_big_up", values, 0..100000, step = 1000)
            FreqSliderRow("Input boost little", "input_boost_little", values, littleFreqs)
            FreqSliderRow("Input boost big", "input_boost_big", values, bigFreqs)
            IntSliderRow("Input boost duration (ms)", "input_boost_ms", values, 0..2000, step = 20)
        }

        // -------- Scheduler --------
        SectionCard {
            SectionTitle("Scheduler")
            SwitchRow("sched_boost_top_app", "sched_boost_top_app", values)
            ChipsRow("sched_boost", "sched_boost", values, listOf("0", "1", "2"))
            SwitchRow("top-app prefer_idle", "stune_prefer_idle", values)
            IntSliderRow("top-app boost", "stune_boost", values, 0..100)
            IntSliderRow("sched_downmigrate", "sched_down", values, 0..1024, step = 8)
            IntSliderRow("sched_upmigrate", "sched_up", values, 0..1024, step = 8)
            IntSliderRow("group_downmigrate", "sched_group_down", values, 0..1024, step = 8)
            IntSliderRow("group_upmigrate", "sched_group_up", values, 0..1024, step = 8)
            SwitchRow("Big core_ctl", "core_ctl_big", values, onValue = "on", offValue = "off", proOnly = basicTier)
            IntSliderRow("core_ctl min_cpus", "core_ctl_big_min", values, 0..2, proOnly = basicTier)
            IntSliderRow("core_ctl busy_up", "core_ctl_big_busy_up", values, 0..100, proOnly = basicTier)
            IntSliderRow("core_ctl busy_down", "core_ctl_big_busy_down", values, 0..100, proOnly = basicTier)
            CoreChipsRow("background cpus", "cpuset_bg", values, proOnly = basicTier)
            CoreChipsRow("system-background cpus", "cpuset_sysbg", values, proOnly = basicTier)
            CoreChipsRow("foreground cpus", "cpuset_fg", values, proOnly = basicTier)
            CoreChipsRow("top-app cpus", "cpuset_top", values, proOnly = basicTier)
        }

        // -------- GPU --------
        SectionCard {
            SectionTitle("GPU")
            if (gpuGovernors.isNotEmpty()) {
                ChipsRow("GPU governor", "gpu_governor", values, gpuGovernors)
            }
            if (gpuLevels.isNotEmpty()) {
                LevelSliderRow("GPU max power level", "gpu_max_pl", values, gpuLevels, gpuFreqMhz)
                LevelSliderRow("GPU min power level", "gpu_min_pl", values, gpuLevels, gpuFreqMhz)
            }
            SwitchRow("gpubw floor", "gpu_bw_floor", values, onValue = "on", offValue = "off")
        }

        // -------- I/O --------
        SectionCard {
            SectionTitle("I/O")
            ChipsRow("Block scheduler", "blk_scheduler", values, schedulers)
            IntSliderRow("read_ahead_kb", "read_ahead_kb", values, 0..4096, step = 64)
            IntSliderRow("nr_requests", "nr_requests", values, 0..1024, step = 32)
            SwitchRow("iostats", "iostats", values, onValue = "1", offValue = "0")
            ChipsRow("Bus bandwidth", "devfreq_bw", values, listOf("min", "max", "always"))
        }

        // -------- Actions --------
        SectionCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & apply")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "Reset to default",
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(vertical = 8.dp),
        colors = CardDefaults.defaultColors()
    ) {
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
    )
}

@Composable
private fun ProTag() {
    Text(
        text = "Pro",
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    val background = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
    val foreground = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = foreground, style = MiuixTheme.textStyles.body2)
        if (active) {
            Text(
                text = "Active",
                color = if (selected) foreground else MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote2
            )
        }
    }
}

@Composable
private fun RowHeader(title: String, valueText: String, proOnly: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f)
        )
        if (proOnly) {
            Spacer(Modifier.width(6.dp))
            ProTag()
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = valueText,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FreqSliderRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    freqs: List<Int>,
    proOnly: Boolean = false
) {
    if (freqs.isEmpty()) {
        return
    }
    val current = ((values[key] as? Number)?.toInt() ?: 0)
    val index = freqs.indexOf(current).let { if (it >= 0) it else freqs.size - 1 }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        RowHeader(title, freqLabel(freqs[index]), proOnly)
        Slider(
            value = index.toFloat(),
            onValueChange = {
                val i = it.roundToInt().coerceIn(0, freqs.size - 1)
                values[key] = freqs[i]
            },
            valueRange = 0f..(freqs.size - 1).toFloat(),
            steps = (freqs.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LevelSliderRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    levels: List<Int>,
    freqMhz: List<String>
) {
    if (levels.isEmpty()) {
        return
    }
    val current = ((values[key] as? Number)?.toInt() ?: levels.first())
    val index = levels.indexOf(current).let { if (it >= 0) it else 0 }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        RowHeader(title, levelLabel(levels[index], freqMhz), false)
        Slider(
            value = index.toFloat(),
            onValueChange = {
                val i = it.roundToInt().coerceIn(0, levels.size - 1)
                values[key] = levels[i]
            },
            valueRange = 0f..(levels.size - 1).toFloat(),
            steps = (levels.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun IntSliderRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    range: IntRange,
    step: Int = 1,
    proOnly: Boolean = false
) {
    val current = ((values[key] as? Number)?.toInt() ?: range.first)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        RowHeader(title, current.toString(), proOnly)
        Slider(
            value = current.toFloat(),
            onValueChange = {
                val rounded = (it / step).roundToInt() * step
                values[key] = rounded.coerceIn(range.first, range.last)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    onValue: String = "1",
    offValue: String = "0",
    proOnly: Boolean = false
) {
    val checked = (values[key]?.toString() ?: offValue) == onValue
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f)
        )
        if (proOnly) {
            Spacer(Modifier.width(6.dp))
            ProTag()
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = { values[key] = if (it) onValue else offValue }
        )
    }
}

@Composable
private fun ChipsRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    options: List<String>
) {
    if (options.isEmpty()) {
        return
    }
    val current = values[key]?.toString() ?: options.first()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text = title, style = MiuixTheme.textStyles.body2)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (option in options) {
                val selected = option == current
                val background = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
                val foreground = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground
                Text(
                    text = option,
                    color = foreground,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(background)
                        .clickable { values[key] = option }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CoreChipsRow(
    title: String,
    key: String,
    values: MutableMap<String, Any>,
    proOnly: Boolean = false
) {
    val mask = values[key]?.toString() ?: "0-7"
    val cores = parseCoreMask(mask)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            if (proOnly) {
                Spacer(Modifier.width(6.dp))
                ProTag()
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = mask,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (core in 0..7) {
                val selected = cores[core]
                Text(
                    text = "$core",
                    color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable {
                            val next = cores.copyOf()
                            next[core] = !next[core]
                            if (next.any { it }) {
                                values[key] = buildCoreMask(next)
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ---------------- helpers ----------------

private fun freqLabel(khz: Int): String {
    val mhz = khz / 1000.0
    return if (mhz % 1.0 == 0.0) {
        "${mhz.toInt()} MHz"
    } else {
        String.format(Locale.US, "%.1f MHz", mhz)
    }
}

private fun levelLabel(level: Int, freqMhz: List<String>): String {
    val freq = freqMhz.getOrNull(level)
    return if (freq != null) "pl $level · $freq MHz" else "pl $level"
}

private fun parseCoreMask(mask: String): BooleanArray {
    val result = BooleanArray(8)
    for (part in mask.split(",")) {
        val p = part.trim()
        if (p.contains("-")) {
            val bounds = p.split("-")
            val from = bounds.getOrNull(0)?.trim()?.toIntOrNull()
            val to = bounds.getOrNull(1)?.trim()?.toIntOrNull()
            if (from != null && to != null) {
                for (i in from..to) {
                    if (i in 0..7) {
                        result[i] = true
                    }
                }
            }
        } else {
            p.toIntOrNull()?.let {
                if (it in 0..7) {
                    result[it] = true
                }
            }
        }
    }
    return result
}

private fun buildCoreMask(cores: BooleanArray): String {
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
