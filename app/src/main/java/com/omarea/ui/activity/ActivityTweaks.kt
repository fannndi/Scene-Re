package com.omarea.ui.activity

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.omarea.engine.BusDcvs
import com.omarea.engine.DirectWrite
import com.omarea.engine.KernelCompat
import com.omarea.engine.ModuleHooks
import com.omarea.engine.ResurgenceInstaller
import com.omarea.engine.RootShell
import com.omarea.engine.SepolicyOptimizer
import com.omarea.data.SpfConfig
import com.omarea.engine.TweakCommands
import com.omarea.engine.SepolicyCapability
import com.omarea.runtime.PmStateJournal
import com.omarea.runtime.DisplayRestart
import com.omarea.runtime.IrqAffinity
import com.omarea.runtime.KernelCrashGuard
import com.omarea.runtime.LoggingReduction
import com.omarea.runtime.RootForegroundWatch
import com.omarea.runtime.SfFramePacing
import com.omarea.runtime.TrueOff
import com.omarea.vtools.R
import java.util.concurrent.Executors

/**
 * Kernel/ROM tweaks that replaced the kr-script pages (AOSP settings, UFS
 * health, sensors, Qualcomm bus DCVS, systemless hooks, rescue installer).
 *
 * One background pass loads every value, then rows are built on the main
 * thread; row actions write off-thread and re-read only their own value.
 */
class ActivityTweaks : ActivityBase() {

    private lateinit var container: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var disposed = false

    private data class Loaded(
        val lowPowerOn: Boolean,
        val level: String,
        val levelMax: String,
        val anim: Map<String, String>,
        val gappsSupported: Boolean,
        val gappsOn: Boolean,
        val ufs: Map<String, String>,
        val sensors: List<Triple<String, String, String>>,
        val perfmgrSupported: Boolean,
        val perfmgrOn: Boolean,
        val ddrFixedVisible: Boolean,
        val ddrFixedValue: String,
        val busRows: List<TweakCommands.BusRow>,
        val ddrOptions: List<String>,
        val moduleInstalled: Boolean,
        val hookRows: List<TweakCommands.HookRow>,
        val rescueInstalled: Boolean,
        val directState: String,
        val lockedFeatures: List<Pair<String, String>>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tweaks)
        setBackArrow()
        title = getString(R.string.menu_additional)
        container = findViewById(R.id.tweaks_container)
        runBg {
            val loaded = try {
                load()
            } catch (ex: Exception) {
                // One failed probe must not kill the screen: render the error.
                runOnUiThread { errorRow(ex.message ?: ex.javaClass.simpleName) }
                return@runBg
            }
            runOnUiThread { if (!disposed) render(loaded) }
        }
    }

    override fun onDestroy() {
        disposed = true
        executor.shutdown()
        super.onDestroy()
    }

    /** Runs [block] off-thread unless the activity is being destroyed. */
    private fun runBg(block: () -> Unit) {
        if (disposed || executor.isShutdown) return
        try {
            executor.execute {
                try {
                    block()
                } catch (ex: Exception) {
                    runOnUiThread { if (!disposed) errorRow(ex.message ?: ex.javaClass.simpleName) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Race against onDestroy — ignore.
        }
    }

    // ---------------------------------------------------------------- load
    /** Single root-shell round trip; parsing is pure (unit-tested). */
    private fun load(): Loaded {
        val sections = TweakCommands.parseSections(RootShell.run(TweakCommands.tweaksLoadScript()))
        fun body(key: String) = sections[key].orEmpty()
        val busRows = TweakCommands.parseBusRows(body("bus"))
        val ddrFixed = body("ddr_fixed").lines()
        // Live direct-write capability: write the node's own value back
        // through the direct path (access() lies on this kernel).
        val probeNode = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
        val probeResult = if (SepolicyOptimizer.directWritesEnabled(this)) DirectWrite.probe(probeNode) else null
        val directState = when {
            !SepolicyOptimizer.directWritesEnabled(this) -> "off"
            probeResult.isNullOrEmpty() -> "active (verified)"
            else -> "not writable: $probeResult"
        }
        return Loaded(
            lowPowerOn = body("low_power") == "1",
            level = body("trigger_level").ifEmpty { "null" },
            levelMax = body("trigger_max").ifEmpty { "null" },
            anim = TweakCommands.parseKeyValues(body("anim")),
            gappsSupported = body("gapps_supported") == "1",
            gappsOn = body("gapps_on") == "1",
            ufs = TweakCommands.parseUfs(body("ufs")),
            sensors = TweakCommands.parseSensors(body("sensors")),
            perfmgrSupported = body("perfmgr_supported") == "1",
            perfmgrOn = body("perfmgr_on") == "1",
            ddrFixedVisible = ddrFixed.getOrNull(0) == "1",
            ddrFixedValue = ddrFixed.getOrNull(1)?.substringAfter("|").orEmpty(),
            busRows = busRows,
            ddrOptions = busRows.firstOrNull { it.id == "DDR" }?.options.orEmpty(),
            moduleInstalled = body("module_installed") == "1",
            hookRows = TweakCommands.parseHookRows(body("hooks")),
            rescueInstalled = body("rescue") == "1",
            directState = directState,
            lockedFeatures = KernelCompat.snapshot(this).locked.map { it.id to it.label }
        )
    }

    // -------------------------------------------------------------- render
    private fun render(loaded: Loaded) {
        section("AOSP")
        switchRow(
            "Battery saver", "AOSP low-power mode + app standby restrictions",
            loaded.lowPowerOn,
            { TweakCommands.read("settings get global low_power") == "1" },
            { on ->
                TweakCommands.run(TweakCommands.lowPowerSet(on))
                TweakCommands.lowPowerKeys.forEach { PmStateJournal.record(this, "setting", "global:$it") }
            }
        )
        pickerRow("Battery saver trigger", loaded.level, TweakCommands.lowPowerLevels) { value ->
            TweakCommands.run(TweakCommands.lowPowerLevelSet(value, null))
            PmStateJournal.record(this, "setting", "global:low_power_trigger_level")
            TweakCommands.read("settings get global low_power_trigger_level").ifEmpty { "null" }
        }
        pickerRow("Battery saver warning", loaded.levelMax, TweakCommands.lowPowerLevels) { value ->
            TweakCommands.run(TweakCommands.lowPowerLevelSet(null, value))
            PmStateJournal.record(this, "setting", "global:low_power_trigger_level_max")
            TweakCommands.read("settings get global low_power_trigger_level_max").ifEmpty { "null" }
        }

        animPicker("Window animation", "window", loaded.anim, TweakCommands.animScaleOptions)
        animPicker("Transition animation", "transition", loaded.anim, TweakCommands.animScaleOptions)
        animPicker("Animator duration", "animator", loaded.anim, TweakCommands.animScaleOptions)
        animPicker("MIUI transition ratio", "ratio", loaded.anim, TweakCommands.animRatioOptions)

        if (loaded.gappsSupported) {
            switchRow(
                "Google services", "Enable/disable the Google Play services suite",
                loaded.gappsOn,
                { TweakCommands.read(TweakCommands.gappsGetCommand()) == "1" },
                { on ->
                    TweakCommands.run(TweakCommands.gappsSet(on))
                    for (pkg in TweakCommands.gappsPackages) {
                        if (on) PmStateJournal.clear(this, "disable", pkg)
                        else PmStateJournal.record(this, "disable", pkg)
                    }
                }
            )
        }

        section("Storage")
        val ufsLife = loaded.ufs["bDeviceLifeTimeEstA"].orEmpty().trim()
        infoRow(
            "UFS life (EstA)",
            ufsLife.ifEmpty { "unavailable (kernel health-descriptor disabled)" }
        )
        actionRow("UFS health detail", "Show every health descriptor field") {
            dialog("UFS health descriptor", loaded.ufs.entries.joinToString("\n") { "${it.key} = ${it.value}" }.ifEmpty { "-" })
        }
        actionRow("Fstrim now", "Trim data/cache/system") {
            runBg { TweakCommands.run(TweakCommands.fstrimCommand); toast("Fstrim finished") }
        }

        section("Thermal sensors")
        actionRow("Show sensor values", "All thermal zones (type / temp)") {
            dialog("Thermal sensors", loaded.sensors
                .map { (zone, type, raw) ->
                    val celsius = com.omarea.util.measure.ThermalMath.decodePlausible(raw.toDoubleOrNull())
                    val value = if (celsius != null) {
                        com.omarea.util.measure.ThermalMath.format(celsius)
                    } else {
                        "$raw (level)"
                    }
                    "$zone  $type  $value"
                }
                .joinToString("\n")
                .ifEmpty { "-" })
        }

        section("Qualcomm")
        if (loaded.perfmgrSupported) {
            switchRow(
                "Perfmgr", "MIUI perfmgr module enable", loaded.perfmgrOn,
                { TweakCommands.read("cat ${TweakCommands.PERFMGR_NODE} 2>/dev/null") == "1" },
                { on -> TweakCommands.run(TweakCommands.perfmgrSet(on)) }
            )
        }
        if (loaded.ddrFixedVisible) {
            pickerRow(
                "DDR fixed freq", loaded.ddrFixedValue,
                listOf("0" to "0") + loaded.ddrOptions.map { it to it }
            ) { value -> BusDcvs.ddrFixedSet(value); BusDcvs.ddrFixedRead() }
        }
        for (row in loaded.busRows.filter { it.visible }) {
            val options = row.options.map { it to it }
            val values = listOf(
                BusDcvs.Kind.MAX to row.max,
                BusDcvs.Kind.MIN to row.min,
                BusDcvs.Kind.BOOST to row.boost
            )
            for ((kind, current) in values) {
                val label = when (kind) {
                    BusDcvs.Kind.MAX -> "Max freq"
                    BusDcvs.Kind.MIN -> "Min freq"
                    BusDcvs.Kind.BOOST -> "Boost freq"
                }
                pickerRow("${row.id} $label", current, options) { value ->
                    BusDcvs.set(row.id, kind, value)
                    BusDcvs.read(row.id, kind)
                }
            }
        }
        for (row in loaded.hookRows) {
            val label = ModuleHooks.labels[row.target] ?: row.target
            if (loaded.moduleInstalled && row.supported) {
                switchRow(
                    label, "Disable via systemless module (reboot required)",
                    row.hooked,
                    { ModuleHooks.isHooked(row.target) },
                    { on -> if (!ModuleHooks.setHooked(row.target, on)) toast("Module required") }
                )
            } else {
                infoRow(
                    label,
                    if (!row.supported) "not present in this ROM/firmware"
                    else "unavailable (systemless module not installed)"
                )
            }
        }

        section("Root")
        infoRow("Direct writes", loaded.directState)
        actionRow(
            "Run direct-write self-test",
            "Rewrite each tuning node's current value through the direct (no-root) path; reports OK/DENIED per family"
        ) {
            runBg {
                val results = SepolicyCapability.probeAll(this)
                val report = results.joinToString("\n") {
                    (it.id + "            ").substring(0, 12) + ": " + it.status
                }
                runOnUiThread { if (!disposed) dialog("SELinux direct-write self-test", report) }
            }
        }
        switchRow(
            "Direct sysfs writes", "SELinux scoped: skip the root shell for profile applies",
            SepolicyOptimizer.directWritesEnabled(this),
            { SepolicyOptimizer.directWritesEnabled(this) },
            { on ->
                // TRUE OFF: SELinux/chmod application is a parameter write.
                if (TrueOff.guardOrToast(this)) {
                    prefs().edit().putBoolean(SpfConfig.GLOBAL_SPF_DIRECT_WRITES, on).apply()
                    val status = SepolicyOptimizer.apply(this, on)
                    toast(if (on) "SELinux rules + chmod applied ($status)" else "Direct writes disabled ($status)")
                }
            }
        )

        section("Rescue")
        actionRow(
            if (loaded.rescueInstalled) "Reinstall Scene Rescue module" else "Install Scene Rescue module",
            "Boot-failure rescue: disables other modules, restores display/apps"
        ) {
            // TRUE OFF: no module/parameter changes either.
            if (!TrueOff.guardOrToast(this)) return@actionRow
            AlertDialog.Builder(this)
                .setTitle("Scene Rescue")
                .setMessage("Install the rescue module into /data/adb/modules/scene_resurgence?")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runBg {
                        val out = ResurgenceInstaller.install(this)
                        runOnUiThread { if (!disposed) dialog("Scene Rescue", out.ifEmpty { "done" }) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        section("Kernel")
        if (loaded.lockedFeatures.isEmpty()) {
            infoRow("Compatibility", "all features available on this kernel/ROM")
        } else {
            for ((id, label) in loaded.lockedFeatures) {
                infoRow(label, "locked — kernel/ROM lacks '$id'")
            }
        }

        // Opt-in runtime extras — moved here from Settings so Settings stays
        // a small screen (they all belong to the device's behaviour, not to
        // per-app or profile data).
        section("Performance extras")
        switchRow(
            getString(R.string.settings_display_restart),
            getString(R.string.settings_display_restart_desc),
            DisplayRestart.isEnabled(this),
            { DisplayRestart.isEnabled(this) },
            { checked ->
                getSharedPreferences(SpfConfig.GLOBAL_SPF, MODE_PRIVATE)
                    .edit().putBoolean(SpfConfig.GLOBAL_SPF_DISPLAY_RESTART, checked).apply()
            }
        )
        switchRow(
            getString(R.string.settings_reduce_logging),
            getString(R.string.settings_reduce_logging_desc),
            LoggingReduction.isEnabled(this),
            { LoggingReduction.isEnabled(this) },
            { LoggingReduction.setEnabled(this, it) }
        )
        switchRow(
            getString(R.string.settings_kernel_crash_guard),
            getString(R.string.settings_kernel_crash_guard_desc),
            KernelCrashGuard.isEnabled(this),
            { KernelCrashGuard.isEnabled(this) },
            { KernelCrashGuard.setEnabled(this, it) }
        )
        switchRow(
            getString(R.string.settings_sf_pacing),
            getString(R.string.settings_sf_pacing_desc),
            SfFramePacing.isEnabled(this),
            { SfFramePacing.isEnabled(this) },
            { SfFramePacing.setEnabled(this, it) }
        )
        switchRow(
            getString(R.string.settings_root_watch),
            getString(R.string.settings_root_watch_desc),
            RootForegroundWatch.isEnabled(this),
            { RootForegroundWatch.isEnabled(this) },
            { RootForegroundWatch.setEnabled(this, it) }
        )
        switchRow(
            getString(R.string.settings_irq_affinity),
            getString(R.string.settings_irq_affinity_desc),
            IrqAffinity.isEnabled(this),
            { IrqAffinity.isEnabled(this) },
            { IrqAffinity.setEnabled(this, it) }
        )
    }

    // ----------------------------------------------------------------- rows
    private fun animPicker(
        title: String,
        key: String,
        anim: Map<String, String>,
        options: List<Pair<String, String>>
    ) {
        pickerRow(title, anim[key].orEmpty().takeIf { it != "null" } ?: "", options) { value ->
            TweakCommands.run(TweakCommands.animSet(mapOf(key to value)))
            TweakCommands.animKeys[key]?.let { PmStateJournal.record(this, "setting", "global:$it") }
            TweakCommands.parseKeyValues(TweakCommands.run(TweakCommands.animGetCommand()))[key].orEmpty().takeIf { it != "null" } ?: ""
        }
    }

    private fun section(titleText: String) {
        container.addView(TextView(this).apply {
            text = titleText
            setPadding(dp(16), dp(24), dp(16), dp(8))
            setTextColor(Color.parseColor("#8B5CF6"))
            textSize = 14f
        })
    }

    /** [read] re-reads the live value after a write; [write] runs off-thread. */
    private fun switchRow(
        title: String,
        desc: String,
        initial: Boolean,
        read: () -> Boolean,
        write: (Boolean) -> Unit
    ) {
        val row = row(title, desc)
        val switch = Switch(this).apply {
            isChecked = initial
            contentDescription = "toggle:$title"
        }
        row.addView(switch, LinearLayout.LayoutParams(dp(60), dp(48)))
        row.setOnClickListener { switch.toggle() }
        var syncing = false // true while we push the verified value back
        switch.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            runBg {
                write(checked)
                val fresh = read()
                runOnUiThread {
                    if (disposed) return@runOnUiThread
                    if (switch.isChecked != fresh) {
                        syncing = true
                        switch.isChecked = fresh
                        syncing = false
                    }
                }
            }
        }
        container.addView(row)
    }

    private fun pickerRow(
        title: String,
        initial: String,
        options: List<Pair<String, String>>,
        write: (String) -> String
    ) {
        val row = row(title, null)
        val valueView = TextView(this).apply {
            text = options.firstOrNull { it.first == initial }?.second
                ?: initial.takeIf { it.isNotEmpty() && it != "null" }
                ?: "System default"
            setPadding(0, dp(12), dp(16), dp(12))
            textSize = 14f
            setTextColor(Color.parseColor("#8B5CF6"))
        }
        row.addView(valueView)
        var current = initial
        row.setOnClickListener {
            if (options.isEmpty()) return@setOnClickListener
            val labels = options.map { it.second.ifEmpty { "System default" } }.toTypedArray()
            val currentIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, currentIndex) { d, which ->
                    d.dismiss()
                    val picked = options[which].first
                    valueView.text = options[which].second
                    runBg {
                        val fresh = write(picked)
                        runOnUiThread {
                            if (disposed) return@runOnUiThread
                            current = fresh
                            valueView.text = options.firstOrNull { it.first == fresh }?.second ?: fresh
                        }
                    }
                }
                .show()
        }
        container.addView(row)
    }

    private fun actionRow(title: String, desc: String, action: () -> Unit) {
        val row = row(title, desc)
        row.setOnClickListener { action() }
        container.addView(row)
    }

    private fun infoRow(title: String, value: String) {
        val row = row(title, null)
        row.addView(TextView(this).apply { text = value; setPadding(0, dp(12), dp(16), dp(12)) })
        container.addView(row)
    }

    /** Shown when a probe fails — keeps the screen usable instead of crashing. */
    private fun errorRow(message: String) {
        if (disposed || !::container.isInitialized) return
        if (container.childCount > 0) return // only for the initial load
        section("Error")
        infoRow("Tweaks failed to load", message)
    }

    private fun row(title: String, desc: String?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        textCol.addView(TextView(this).apply { text = title; textSize = 16f })
        if (!desc.isNullOrEmpty()) {
            textCol.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(Color.parseColor("#888888"))
            })
        }
        row.addView(textCol)
        return row
    }

    private fun dialog(title: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun prefs() = getSharedPreferences(SpfConfig.GLOBAL_SPF, MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
