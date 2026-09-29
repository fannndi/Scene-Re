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
import com.omarea.engine.ModuleHooks
import com.omarea.engine.ResurgenceInstaller
import com.omarea.engine.SepolicyOptimizer
import com.omarea.data.SpfConfig
import com.omarea.engine.TweakCommands
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
        val busDomains: List<Pair<String, List<Pair<BusDcvs.Kind, String>>>>,
        val moduleInstalled: Boolean,
        val hookSupport: Map<String, Boolean>,
        val hookState: Map<String, Boolean>,
        val rescueInstalled: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tweaks)
        setBackArrow()
        title = getString(R.string.menu_additional)
        container = findViewById(R.id.tweaks_container)
        executor.execute {
            val loaded = load()
            runOnUiThread { render(loaded) }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- load
    private fun load(): Loaded {
        val anim = TweakCommands.parseKeyValues(TweakCommands.run(TweakCommands.animGetCommand()))
        val busDomains = BusDcvs.domains.mapNotNull { domain ->
            if (!BusDcvs.visible(domain.id)) return@mapNotNull null
            val values = listOf(BusDcvs.Kind.MAX, BusDcvs.Kind.MIN, BusDcvs.Kind.BOOST)
                .map { it to BusDcvs.read(domain.id, it) }
            domain.id to values
        }
        val hookTargets = listOf(ModuleHooks.PERFBOOSTS, ModuleHooks.PERFD)
        return Loaded(
            lowPowerOn = TweakCommands.read("settings get global low_power") == "1",
            level = TweakCommands.read("settings get global low_power_trigger_level").ifEmpty { "null" },
            levelMax = TweakCommands.read("settings get global low_power_trigger_level_max").ifEmpty { "null" },
            anim = anim,
            gappsSupported = TweakCommands.read(TweakCommands.gappsSupportedCommand()) == "1",
            gappsOn = TweakCommands.read(TweakCommands.gappsGetCommand()) == "1",
            ufs = TweakCommands.parseUfs(TweakCommands.run(TweakCommands.ufsHealthCommand)),
            sensors = TweakCommands.parseSensors(TweakCommands.run(TweakCommands.sensorsCommand)),
            perfmgrSupported = TweakCommands.read("[ -f ${TweakCommands.PERFMGR_NODE} ] && echo 1 || echo 0") == "1",
            perfmgrOn = TweakCommands.read("cat ${TweakCommands.PERFMGR_NODE} 2>/dev/null") == "1",
            ddrFixedVisible = BusDcvs.ddrFixedVisible(),
            ddrFixedValue = BusDcvs.ddrFixedRead(),
            busDomains = busDomains,
            moduleInstalled = ModuleHooks.moduleInstalled(),
            hookSupport = hookTargets.associateWith { ModuleHooks.targetExists(it) },
            hookState = hookTargets.associateWith { ModuleHooks.isHooked(it) },
            rescueInstalled = ResurgenceInstaller.isModuleInstalled()
        )
    }

    // -------------------------------------------------------------- render
    private fun render(loaded: Loaded) {
        section("AOSP")
        switchRow(
            "Battery saver", "AOSP low-power mode + app standby restrictions",
            loaded.lowPowerOn,
            { TweakCommands.read("settings get global low_power") == "1" },
            { on -> TweakCommands.run(TweakCommands.lowPowerSet(on)) }
        )
        pickerRow("Battery saver trigger", loaded.level, TweakCommands.lowPowerLevels) { value ->
            TweakCommands.run(TweakCommands.lowPowerLevelSet(value, null))
            TweakCommands.read("settings get global low_power_trigger_level").ifEmpty { "null" }
        }
        pickerRow("Battery saver warning", loaded.levelMax, TweakCommands.lowPowerLevels) { value ->
            TweakCommands.run(TweakCommands.lowPowerLevelSet(null, value))
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
                { on -> TweakCommands.run(TweakCommands.gappsSet(on)) }
            )
        }

        section("Storage")
        infoRow("UFS life (EstA)", loaded.ufs["bDeviceLifeTimeEstA"].orEmpty().ifEmpty { "-" })
        actionRow("UFS health detail", "Show every health descriptor field") {
            dialog("UFS health descriptor", loaded.ufs.entries.joinToString("\n") { "${it.key} = ${it.value}" }.ifEmpty { "-" })
        }
        actionRow("Fstrim now", "Trim data/cache/system") {
            executor.execute { TweakCommands.run(TweakCommands.fstrimCommand); toast("Fstrim finished") }
        }

        section("Thermal sensors")
        actionRow("Show sensor values", "All thermal zones (type / temp)") {
            dialog("Thermal sensors", loaded.sensors.joinToString("\n") { "${it.first}  ${it.second}  ${it.third}" }.ifEmpty { "-" })
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
                listOf("0" to "0") + BusDcvs.options("DDR").map { it to it }
            ) { value -> BusDcvs.ddrFixedSet(value); BusDcvs.ddrFixedRead() }
        }
        for ((domainId, values) in loaded.busDomains) {
            val options = BusDcvs.options(domainId).map { it to it }
            for ((kind, current) in values) {
                val label = when (kind) {
                    BusDcvs.Kind.MAX -> "Max freq"
                    BusDcvs.Kind.MIN -> "Min freq"
                    BusDcvs.Kind.BOOST -> "Boost freq"
                }
                pickerRow("$domainId $label", current, options) { value ->
                    BusDcvs.set(domainId, kind, value)
                    BusDcvs.read(domainId, kind)
                }
            }
        }
        for ((target, label) in listOf(
            ModuleHooks.PERFBOOSTS to "Performance boost config",
            ModuleHooks.PERFD to "perfd"
        )) {
            if (loaded.moduleInstalled && loaded.hookSupport[target] == true) {
                switchRow(
                    label, "Disable via systemless module (reboot required)",
                    loaded.hookState[target] == true,
                    { ModuleHooks.isHooked(target) },
                    { on -> if (!ModuleHooks.setHooked(target, on)) toast("Module required") }
                )
            } else {
                infoRow(label, "unavailable (systemless module not installed)")
            }
        }

        section("Root")
        switchRow(
            "Direct sysfs writes", "SELinux scoped: skip the root shell for profile applies",
            SepolicyOptimizer.directWritesEnabled(this),
            { SepolicyOptimizer.directWritesEnabled(this) },
            { on ->
                prefs().edit().putBoolean(SpfConfig.GLOBAL_SPF_DIRECT_WRITES, on).apply()
                SepolicyOptimizer.apply(this, on)
                toast(if (on) "Scoped SELinux rules + node chmod applied" else "Direct writes disabled")
            }
        )

        section("Rescue")
        actionRow(
            if (loaded.rescueInstalled) "Reinstall Scene Rescue module" else "Install Scene Rescue module",
            "Boot-failure rescue: disables other modules, restores display/apps"
        ) {
            AlertDialog.Builder(this)
                .setTitle("Scene Rescue")
                .setMessage("Install the rescue module into /data/adb/modules/scene_resurgence?")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    executor.execute {
                        val out = ResurgenceInstaller.install(this)
                        runOnUiThread { dialog("Scene Rescue", out.ifEmpty { "done" }) }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
            TweakCommands.parseKeyValues(TweakCommands.run(TweakCommands.animGetCommand()))[key].orEmpty().takeIf { it != "null" } ?: ""
        }
    }

    private fun section(titleText: String) {
        container.addView(TextView(this).apply {
            text = titleText
            setPadding(dp(16), dp(20), dp(16), dp(6))
            setTextColor(Color.parseColor("#2196F3"))
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
        switch.setOnCheckedChangeListener { _, checked ->
            executor.execute {
                write(checked)
                val fresh = read()
                runOnUiThread { switch.isChecked = fresh }
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
            text = options.firstOrNull { it.first == initial }?.second ?: initial
            setPadding(0, dp(12), dp(16), dp(12))
        }
        row.addView(valueView)
        row.setOnClickListener {
            if (options.isEmpty()) return@setOnClickListener
            val labels = options.map { it.second.ifEmpty { "System default" } }.toTypedArray()
            val current = options.indexOfFirst { it.first == initial }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, current) { d, which ->
                    d.dismiss()
                    val picked = options[which].first
                    valueView.text = options[which].second
                    executor.execute {
                        val fresh = write(picked)
                        runOnUiThread { valueView.text = options.firstOrNull { it.first == fresh }?.second ?: fresh }
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

    private fun row(title: String, desc: String?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), 0, dp(4))
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
