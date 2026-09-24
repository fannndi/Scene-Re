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
import com.omarea.scene_mode.ProfileOptions
import com.omarea.store.SpfConfig
import com.omarea.vtools.R

/**
 * UI for the profile options layer (frequency limiter, lite mode, game
 * priority, DND, preload, bypass charging and the extra tweak toggles).
 */
class DialogProfileOptions(private val context: Activity) {
    private val governorValues = listOf("", "schedutil", "walt", "performance", "powersave")
    private val ioSchedValues = listOf("", "none", "mq-deadline", "kyber", "bfq")
    private val gameFpsValues = listOf(0, 30, 45, 60, 90, 120)

    fun show() {
        val spf = context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        val view = context.layoutInflater.inflate(R.layout.dialog_profile_options, null)
        val dialog = DialogHelper.customDialog(context, view)

        val enabled = view.findViewById<Switch>(R.id.profile_options_enabled)
        val limit = view.findViewById<SeekBar>(R.id.profile_options_limit)
        val limitValue = view.findViewById<TextView>(R.id.profile_options_limit_value)
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
        val governor = view.findViewById<Spinner>(R.id.profile_options_governor)
        val ioSched = view.findViewById<Spinner>(R.id.profile_options_iosched)

        enabled.isChecked = spf.getBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, true)
        limit.progress = (spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, 0) / 5).coerceIn(0, 20)
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
        val storedDownscale = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE, 0)
        gameDownscale.progress = if (storedDownscale in 50..95) (100 - storedDownscale) / 5 else 0
        val storedFps = spf.getInt(SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS, 0)
        gameFps.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fpsValuesForDisplay())
        gameFps.setSelection(gameFpsValues.indexOf(storedFps).coerceAtLeast(0))

        bindSeekBar(limit, limitValue) { progress -> limitText(progress * 5) }
        bindSeekBar(budget, budgetValue) { progress -> "${(progress + 1) * 100} MB" }
        bindSeekBar(bypassThreshold, bypassThresholdValue) { progress -> "${(progress + 4) * 5}%" }
        bindSeekBar(gameDownscale, gameDownscaleValue) { progress -> downscaleText(progress) }

        governor.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, governorValuesForDisplay())
        ioSched.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ioSchedValuesForDisplay())
        governor.setSelection(governorValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, "") ?: "").coerceAtLeast(0))
        ioSched.setSelection(ioSchedValues.indexOf(spf.getString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, "") ?: "").coerceAtLeast(0))

        // Material spinner text color follows the dialog theme; force a readable tone.
        governor.onItemSelectedListener = SimpleSelect()
        ioSched.onItemSelectedListener = SimpleSelect()

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            spf.edit()
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_OPTIONS, enabled.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_LIMIT_PERCENT, limit.progress * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_LITE, lite.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PID_PRIORITY, pid.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_DND_GAME, dnd.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD, preload.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_PROFILE_PRELOAD_BUDGET, (budget.progress + 1) * 100)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_BYPASS_GAME, bypass.isChecked)
                .putInt(SpfConfig.GLOBAL_SPF_BYPASS_THRESHOLD, (bypassThreshold.progress + 4) * 5)
                .putBoolean(SpfConfig.GLOBAL_SPF_PROFILE_EXTRA_TWEAKS, extra.isChecked)
                .putBoolean(SpfConfig.GLOBAL_SPF_THERMAL_PID, thermal.isChecked)
                .putInt(
                    SpfConfig.GLOBAL_SPF_PROFILE_GAME_DOWNSCALE,
                    if (gameDownscale.progress == 0) 0 else 100 - gameDownscale.progress * 5
                )
                .putInt(
                    SpfConfig.GLOBAL_SPF_PROFILE_GAME_FPS,
                    gameFpsValues[gameFps.selectedItemPosition.coerceIn(0, gameFpsValues.size - 1)]
                )
                .putString(SpfConfig.GLOBAL_SPF_PROFILE_GOVERNOR, governorValues[governor.selectedItemPosition.coerceIn(0, governorValues.size - 1)])
                .putString(SpfConfig.GLOBAL_SPF_PROFILE_IOSCHED, ioSchedValues[ioSched.selectedItemPosition.coerceIn(0, ioSchedValues.size - 1)])
                .apply()
            dialog.dismiss()

            // A disabled limiter or a disabled master switch must undo the previous caps.
            if (!enabled.isChecked || limit.progress == 0) {
                ProfileOptions.reset(context)
            }
            ProfileOptions.reapply(context)
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
