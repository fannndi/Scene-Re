package com.omarea.vtools.dialogs

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.scene_mode.options.BatterySaverFollow
import com.omarea.scene_mode.monitor.MonitorManager
import com.omarea.scene_mode.options.ProfileOptions
import com.omarea.store.SpfConfig
import com.omarea.utils.MiuThermal
import com.omarea.utils.MiuiBoosterHints
import com.omarea.utils.PlatformCapabilities
import com.omarea.utils.QtiPerfHints
import com.omarea.vtools.R

/**
 * UI for the profile options layer (frequency limiter, lite mode, game
 * priority, DND, preload, bypass charging and the extra tweak toggles).
 */
class DialogProfileOptions(private val context: Activity) {
    // Candidate list mirrors Encore Tweaks' preferred governors (Apache-2.0);
    // apply_governor only writes the ones the kernel actually advertises.
    private val governorValues = listOf(
        "", "scx", "schedhorizon", "walt", "sched_pixel", "sugov_ext", "uag",
        "schedplus", "energy_step", "schedutil", "interactive", "conservative",
        "performance", "powersave"
    )
    private val ioSchedValues = listOf("", "none", "mq-deadline", "kyber", "bfq")
    private val gameFpsValues = listOf(0, 30, 45, 60, 90, 120)
    private val gameRendererValues = listOf("", "opengl", "skiagl", "skiavk")
    private val miuiThermalModeValues = MiuThermal.CHOICES
    private val miuiThermalModeLabels = listOf(
        R.string.profile_options_miui_thermal_default,
        R.string.profile_options_miui_thermal_tgame,
        R.string.profile_options_miui_thermal_nolimits,
        R.string.profile_options_miui_thermal_phone
    )

    fun show() {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val view = context.layoutInflater.inflate(R.layout.dialog_profile_options, null)
        val dialog = DialogHelper.customDialog(context, view)

        val enabled = view.findViewById<Switch>(R.id.profile_options_enabled)
        val limit = view.findViewById<SeekBar>(R.id.profile_options_limit)
        val limitValue = view.findViewById<TextView>(R.id.profile_options_limit_value)
        val gpuLimit = view.findViewById<SeekBar>(R.id.profile_options_gpu_limit)
        val gpuLimitValue = view.findViewById<TextView>(R.id.profile_options_gpu_limit_value)
        val lightDetect = view.findViewById<Switch>(R.id.profile_options_light_detect)
        val lightCpu = view.findViewById<SeekBar>(R.id.profile_options_light_cpu)
        val lightCpuValue = view.findViewById<TextView>(R.id.profile_options_light_cpu_value)
        val lightGpu = view.findViewById<SeekBar>(R.id.profile_options_light_gpu)
        val lightGpuValue = view.findViewById<TextView>(R.id.profile_options_light_gpu_value)
        val gameDdr = view.findViewById<Switch>(R.id.profile_options_game_ddr)
        val qtiHints = view.findViewById<Switch>(R.id.profile_options_qti_hints)
        val cpuBoost = view.findViewById<Switch>(R.id.profile_options_cpu_boost)
        val miuiRefresh = view.findViewById<Switch>(R.id.profile_options_miui_refresh)
        val miuiThermal = view.findViewById<Spinner>(R.id.profile_options_miui_thermal)
        val guard = view.findViewById<Switch>(R.id.profile_options_guard)
        val guardTemp = view.findViewById<SeekBar>(R.id.profile_options_guard_temp)
        val guardTempValue = view.findViewById<TextView>(R.id.profile_options_guard_temp_value)
        val guardPercent = view.findViewById<SeekBar>(R.id.profile_options_guard_percent)
        val guardPercentValue = view.findViewById<TextView>(R.id.profile_options_guard_percent_value)
        val sessions = view.findViewById<Switch>(R.id.profile_options_sessions)
        val lite = view.findViewById<Switch>(R.id.profile_options_lite)
        val pid = view.findViewById<Switch>(R.id.profile_options_pid)
        val dnd = view.findViewById<Switch>(R.id.profile_options_dnd)
        val preload = view.findViewById<Switch>(R.id.profile_options_preload)
        val budget = view.findViewById<SeekBar>(R.id.profile_options_preload_budget)
        val budgetValue = view.findViewById<TextView>(R.id.profile_options_preload_budget_value)
        val bypass = view.findViewById<Switch>(R.id.profile_options_bypass)
        val bypassThreshold = view.findViewById<SeekBar>(R.id.profile_options_bypass_threshold)
        val bypassThresholdValue = view.findViewById<TextView>(R.id.profile_options_bypass_threshold_value)
        val extra = view.findViewById<Switch>(R.id.profile_options_extra)
        val thermal = view.findViewById<Switch>(R.id.profile_options_thermal)
        val gameDownscale = view.findViewById<SeekBar>(R.id.profile_options_game_downscale)
        val gameDownscaleValue = view.findViewById<TextView>(R.id.profile_options_game_downscale_value)
        val gameFps = view.findViewById<Spinner>(R.id.profile_options_game_fps)
        val gameRenderer = view.findViewById<Spinner>(R.id.profile_options_game_renderer)
        // Downscale and target FPS are Game Mode overlay controls: Android 13+
        // (AOSP community ROMs) only. The plain game mode works from Android 12.
        if (!PlatformCapabilities.gameModeOverlay) {
            view.findViewById<TextView>(R.id.profile_options_game_downscale_label)
                .setText(R.string.profile_options_game_downscale_android13)
            view.findViewById<TextView>(R.id.profile_options_game_fps_label)
                .setText(R.string.profile_options_game_fps_android13)
        }
        val dropCaches = view.findViewById<Switch>(R.id.profile_options_drop_caches)
        val monitor = view.findViewById<Switch>(R.id.profile_options_monitor)
        val governor = view.findViewById<Spinner>(R.id.profile_options_governor)
        val ioSched = view.findViewById<Spinner>(R.id.profile_options_iosched)
        val followSaver = view.findViewById<Switch>(R.id.profile_options_follow_saver)
        val qcomBus = view.findViewById<Switch>(R.id.profile_options_qcom_bus)
        val qcomGpu = view.findViewById<Switch>(R.id.profile_options_qcom_gpu)
        val qcomGpuPs = view.findViewById<Switch>(R.id.profile_options_qcom_gpu_ps)
        val govTunes = view.findViewById<Switch>(R.id.profile_options_gov_tunes)
        val stopTrace = view.findViewById<Switch>(R.id.profile_options_stop_trace)
        val stopLoggers = view.findViewById<Switch>(R.id.profile_options_stop_loggers)
        val globalRenderer = view.findViewById<Spinner>(R.id.profile_options_global_renderer)
        val sptmGover = view.findViewById<Switch>(R.id.profile_options_sptm_gover)
        val colocBoost = view.findViewById<Switch>(R.id.profile_options_coloc)
        val ufsIdle = view.findViewById<Switch>(R.id.profile_options_ufs_idle)
        val miuiBooster = view.findViewById<Switch>(R.id.profile_options_miui_booster)
        val qtiDrag = view.findViewById<Switch>(R.id.profile_options_qti_drag)

        enabled.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, true)
        limit.progress = (spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, 0) / 5).coerceIn(0, 20)
        gpuLimit.progress = (spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GPU_LIMIT, 0) / 5).coerceIn(0, 20)
        lightDetect.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_DETECT, true)
        lightCpu.progress = (spf.getInt(
            SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT,
            SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT_DEFAULT
        ) / 5).coerceIn(0, 20)
        lightGpu.progress = (spf.getInt(
            SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT,
            SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT_DEFAULT
        ) / 5).coerceIn(0, 20)
        gameDdr.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DDR_FLOOR, true)
        qtiHints.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_HINTS, false)
        cpuBoost.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_CPU_BOOST, false)
        miuiRefresh.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_REFRESH, true)
        guard.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_THERMAL_GUARD, false)
        guardTemp.progress = (spf.getInt(
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_TEMP,
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_TEMP_DEFAULT
        ) - 38).coerceIn(0, 12)
        guardPercent.progress = (spf.getInt(
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_PERCENT,
            SpfConfig.GLOBAL_SPF_THERMAL_GUARD_PERCENT_DEFAULT
        ) / 5 - 8).coerceIn(0, 10)
        guardTemp.isEnabled = guard.isChecked
        guardPercent.isEnabled = guard.isChecked
        sessions.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_GAME_SESSIONS, true)
        guard.setOnCheckedChangeListener { _, checked ->
            guardTemp.isEnabled = checked
            guardPercent.isEnabled = checked
        }
        lite.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LITE, false)
        pid.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PID_PRIORITY, true)
        dnd.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DND_GAME, false)
        preload.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD, false)
        budget.progress = (spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD_BUDGET, 500) / 100 - 1).coerceIn(0, 19)
        bypass.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BYPASS_GAME, false)
        bypassThreshold.progress =
            (spf.getInt(SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD, SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD_DEFAULT) / 5 - 4).coerceIn(0, 14)
        extra.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, false)
        thermal.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_THERMAL_PID, false)
        monitor.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_MONITOR_FALLBACK, true)
        followSaver.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_FOLLOW_SAVER, false)
        qcomBus.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_BUS, false)
        qcomGpu.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU, false)
        qcomGpuPs.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU_PS, false)
        qcomGpuPs.isEnabled = qcomGpu.isChecked
        qcomGpu.setOnCheckedChangeListener { _, checked -> qcomGpuPs.isEnabled = checked }
        dropCaches.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DROP_CACHES, false)
        govTunes.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GOV_TUNES, false)
        stopTrace.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_TRACE, false)
        stopLoggers.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_LOGGERS, false)
        sptmGover.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_SPTM_GOVER, true)
        colocBoost.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_COLOC_BOOST, false)
        ufsIdle.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_UFS_IDLE_BOOST, true)
        miuiBooster.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_BOOSTER, false)
        qtiDrag.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_DRAG, false)
        // Both of these reach outside the app: the booster adds this UID to a
        // system allow-list, and the drag hint has no timeout so it survives
        // until it is explicitly released. Offer them only where the platform
        // can actually serve them.
        if (!MiuiBoosterHints.isAvailable(context)) {
            miuiBooster.isEnabled = false
            miuiBooster.isChecked = false
            view.findViewById<TextView>(R.id.profile_options_miui_booster_desc)
                .setText(R.string.profile_options_miui_booster_unavailable)
        }
        if (!QtiPerfHints.isAvailable()) {
            qtiDrag.isEnabled = false
            qtiDrag.isChecked = false
        }
        globalRenderer.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, rendererValuesForDisplay())
        globalRenderer.setSelection(
            gameRendererValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GLOBAL_RENDERER, "") ?: "").coerceAtLeast(0)
        )
        val storedDownscale = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE, 0)
        gameDownscale.progress = if (storedDownscale in 50..95) (100 - storedDownscale) / 5 else 0
        val storedFps = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS, 0)
        gameFps.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fpsValuesForDisplay())
        gameFps.setSelection(gameFpsValues.indexOf(storedFps).coerceAtLeast(0))
        gameRenderer.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, rendererValuesForDisplay())
        gameRenderer.setSelection(
            gameRendererValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GAME_RENDERER, "") ?: "").coerceAtLeast(0)
        )

        bindSeekBar(limit, limitValue) { progress -> limitText(progress * 5) }
        bindSeekBar(gpuLimit, gpuLimitValue) { progress -> limitText(progress * 5) }
        bindSeekBar(lightCpu, lightCpuValue) { progress -> limitText(progress * 5) }
        bindSeekBar(lightGpu, lightGpuValue) { progress -> limitText(progress * 5) }
        bindSeekBar(guardTemp, guardTempValue) { progress -> "${38 + progress} \u00B0C" }
        bindSeekBar(guardPercent, guardPercentValue) { progress -> "${(progress + 8) * 5}%" }
        bindSeekBar(budget, budgetValue) { progress -> "${(progress + 1) * 100} MB" }
        bindSeekBar(bypassThreshold, bypassThresholdValue) { progress -> "${(progress + 4) * 5}%" }
        bindSeekBar(gameDownscale, gameDownscaleValue) { progress -> downscaleText(progress) }

        governor.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, governorValuesForDisplay())
        ioSched.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ioSchedValuesForDisplay())
        governor.setSelection(governorValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, "") ?: "").coerceAtLeast(0))
        ioSched.setSelection(ioSchedValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, "") ?: "").coerceAtLeast(0))
        miuiThermal.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            miuiThermalModeLabels.map { context.getString(it) }
        )
        miuiThermal.setSelection(
            miuiThermalModeValues
                .indexOf(spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_THERMAL, 0)).coerceAtLeast(0)
        )

        // Material spinner text color follows the dialog theme; force a readable tone.
        governor.onItemSelectedListener = SimpleSelect()
        ioSched.onItemSelectedListener = SimpleSelect()
        miuiThermal.onItemSelectedListener = SimpleSelect()

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            spf.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, enabled.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, limit.progress * 5)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_GPU_LIMIT, gpuLimit.progress * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_DETECT, lightDetect.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT, lightCpu.progress * 5)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT, lightGpu.progress * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DDR_FLOOR, gameDdr.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_HINTS, qtiHints.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_CPU_BOOST, cpuBoost.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_REFRESH, miuiRefresh.isChecked)
                .putInt(
                    SpfConfig.GLOBAL_SPF_PROFILE_MIUI_THERMAL,
                    miuiThermalModeValues[
                        miuiThermal.selectedItemPosition.coerceIn(0, miuiThermalModeValues.size - 1)
                    ]
                )
                .putBoolean(SpfConfig.GLOBAL_SPF_THERMAL_GUARD, guard.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_THERMAL_GUARD_TEMP, 38 + guardTemp.progress)
                .putInt(SpfConfig.GLOBAL_SPF_THERMAL_GUARD_PERCENT, (guardPercent.progress + 8) * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_GAME_SESSIONS, sessions.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LITE, lite.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PID_PRIORITY, pid.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DND_GAME, dnd.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD, preload.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD_BUDGET, (budget.progress + 1) * 100)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BYPASS_GAME, bypass.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD, (bypassThreshold.progress + 4) * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, extra.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_THERMAL_PID, thermal.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_MONITOR_FALLBACK, monitor.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_FOLLOW_SAVER, followSaver.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_BUS, qcomBus.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU, qcomGpu.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QCOM_GPU_PS, qcomGpuPs.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DROP_CACHES, dropCaches.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_GOV_TUNES, govTunes.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_TRACE, stopTrace.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_STOP_LOGGERS, stopLoggers.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_SPTM_GOVER, sptmGover.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_COLOC_BOOST, colocBoost.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_UFS_IDLE_BOOST, ufsIdle.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_MIUI_BOOSTER, miuiBooster.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_QTI_DRAG, qtiDrag.isChecked)
                .putString(
                    SpfConfig.GLOBAL_SPF_PROFILE_GLOBAL_RENDERER,
                    gameRendererValues[globalRenderer.selectedItemPosition.coerceIn(0, gameRendererValues.size - 1)]
                )
                .putInt(
                    SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE,
                    if (gameDownscale.progress == 0) 0 else 100 - gameDownscale.progress * 5
                )
                .putInt(
                    SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS,
                    gameFpsValues[gameFps.selectedItemPosition.coerceIn(0, gameFpsValues.size - 1)]
                )
                .putString(
                    SpfConfig.GLOBAL_SPF_PROFILE_GAME_RENDERER,
                    gameRendererValues[gameRenderer.selectedItemPosition.coerceIn(0, gameRendererValues.size - 1)]
                )
                .putString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, governorValues[governor.selectedItemPosition.coerceIn(0, governorValues.size - 1)])
                .putString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, ioSchedValues[ioSched.selectedItemPosition.coerceIn(0, ioSchedValues.size - 1)])
                .apply()
            dialog.dismiss()

            // A disabled limiter or a disabled master switch must undo the previous caps.
            if (!enabled.isChecked || limit.progress == 0) {
                ProfileOptions.reset(context)
            }
            // Resync the whole stack: the platform profile owns the per-mode
            // caps, so re-run powercfg + options + boost instead of the options
            // alone. Without this, turning a limiter off would leave the
            // hardware max in place until the next mode switch.
            val mode = ModeSwitcher.getCurrentPowerMode()
            if (mode.isNotEmpty()) {
                ModeSwitcher().executePowercfgMode(mode, ModeSwitcher.getCurrentPowermodeApp())
            } else {
                ProfileOptions.reapply(context)
            }
            val guardCap = (guardPercent.progress + 8) * 5
            if (!guard.isChecked) {
                ProfileOptions.setThermalGuard(context, false, guardCap)
            } else if (ProfileOptions.thermalGuardActive) {
                ProfileOptions.setThermalGuard(context, true, guardCap)
            }
            BatterySaverFollow.onOptionChanged(context)
            MonitorManager.setEnabled(context, monitor.isChecked)
            EventBus.publish(EventType.SERVICE_UPDATE)
            Scene.toast(context.getString(R.string.profile_options_saved))
        }
    }

    private fun bindSeekBar(seekBar: SeekBar, label: TextView, formatter: (Int) -> String) {
        label.text = formatter(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = formatter(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun limitText(percent: Int): String =
        if (percent <= 0) context.getString(R.string.profile_options_off) else "$percent%"

    private fun downscaleText(progress: Int): String =
        if (progress <= 0) context.getString(R.string.profile_options_off) else "${100 - progress * 5}%"

    private fun fpsValuesForDisplay(): List<String> =
        listOf(context.getString(R.string.profile_options_off)) + gameFpsValues.drop(1).map { "$it" }

    private fun rendererValuesForDisplay(): List<String> =
        listOf(
            context.getString(R.string.profile_options_default),
            "OpenGL",
            "SkiaGL",
            "Skia Vulkan"
        )

    private fun governorValuesForDisplay(): List<String> =
        listOf(context.getString(R.string.profile_options_default)) + governorValues.drop(1)

    private fun ioSchedValuesForDisplay(): List<String> =
        listOf(context.getString(R.string.profile_options_default)) + ioSchedValues.drop(1)

    private class SimpleSelect : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }
}
