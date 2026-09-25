package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.data.EventBus
import com.omarea.data.EventType
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.scene_mode.options.AppOptionsStore
import com.omarea.scene_mode.options.ProfileOptions
import com.omarea.scene_mode.game.GameListStore
import com.omarea.scene_mode.game.GameProfileStore
import com.omarea.utils.DisplayModes
import com.omarea.vtools.R

/**
 * Per-app overrides for the profile options layer. Every row starts at
 * "Follow global", so the screen stays meaningful without any configuration.
 */
class DialogAppProfileOptions(private val context: Activity, private val packageName: String) {

    private data class IntRow(
        val labelRes: Int,
        val values: List<Int>,
        val labels: List<String>,
        val spinner: Spinner
    )

    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_app_profile_options, null)
        val dialog = DialogHelper.customDialog(context, view)
        val container = view.findViewById<LinearLayout>(R.id.app_options_container)
        val current = AppOptionsStore.load(context, packageName)

        val triStateValues = listOf(AppOptionsStore.FOLLOW, 1, 0)
        val triStateLabels = labels(R.string.app_options_follow, R.string.app_options_on, R.string.app_options_off)

        // Per-game profile: Automatic follows the light/heavy classification,
        // an explicit choice pins the mode for this game. Only shown for games;
        // the Game switch in the app details screen is the entry point.
        val profileValues = listOf(
            GameProfileStore.AUTO,
            GameProfileStore.PERFORMANCE,
            GameProfileStore.CUSTOM,
            GameProfileStore.LIGHT,
            GameProfileStore.BALANCE,
            GameProfileStore.POWERSAVE,
            GameProfileStore.OFF,
            GameProfileStore.KEEP
        )
        val profileLabels = labels(
            R.string.game_profile_auto,
            R.string.game_profile_performance,
            R.string.game_profile_custom,
            R.string.game_profile_light,
            R.string.game_profile_balance,
            R.string.game_profile_powersave,
            R.string.game_profile_off,
            R.string.game_profile_keep
        )
        val profileSpinner = if (GameListStore.isGame(context, packageName)) {
            addStringRow(
                container,
                R.string.game_profile,
                profileValues,
                profileLabels,
                GameProfileStore.overrideFor(packageName) ?: GameProfileStore.AUTO
            )
        } else {
            null
        }
        val detected = if (profileSpinner != null) GameProfileStore.classOf(packageName) else ""
        if (detected.isNotEmpty()) {
            val info = TextView(context).apply {
                text = context.getString(
                    R.string.game_profile_detected,
                    context.getString(
                        if (detected == GameProfileStore.CLASS_LIGHT) R.string.game_profile_light
                        else R.string.game_profile_heavy
                    )
                )
                setPadding(8, 16, 8, 0)
                setOnClickListener {
                    GameProfileStore.clearClass(context, packageName)
                    Scene.toast(context.getString(R.string.game_profile_reclassified))
                    dialog.dismiss()
                }
            }
            container.addView(info)
        }

        // Per-game refresh rate: the display mode table comes from
        // SurfaceFlinger (the same source the floating selector shows).
        val refreshModes = if (profileSpinner != null) {
            try {
                DisplayModes.list(context)
            } catch (ex: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val refreshRow = if (refreshModes.isNotEmpty()) {
            addRow(
                container,
                R.string.game_refresh,
                listOf(AppOptionsStore.FOLLOW) + refreshModes.map { it.id },
                labels(R.string.game_refresh_follow) + refreshModes.map { it.label },
                current.refresh
            )
        } else {
            null
        }

        val applyRow = addRow(container, R.string.app_options_apply, triStateValues, triStateLabels, current.enabled)
        val liteRow = addRow(container, R.string.profile_options_lite, triStateValues, triStateLabels, current.lite)
        val preloadRow = addRow(container, R.string.profile_options_preload, triStateValues, triStateLabels, current.preload)
        val dndRow = addRow(container, R.string.profile_options_dnd, triStateValues, triStateLabels, current.dnd)
        val bypassRow = addRow(container, R.string.profile_options_bypass, triStateValues, triStateLabels, current.bypass)

        val downscaleValues = listOf(AppOptionsStore.FOLLOW, 0) + (95 downTo 50 step 5).toList()
        val downscaleLabels = labels(R.string.app_options_follow, R.string.app_options_off) +
            (95 downTo 50 step 5).map { "$it%" }
        val downscaleRow =
            addRow(container, R.string.profile_options_game_downscale, downscaleValues, downscaleLabels, current.downscale)

        val fpsValues = listOf(AppOptionsStore.FOLLOW, 0, 30, 45, 60, 90, 120)
        val fpsLabels = labels(R.string.app_options_follow, R.string.app_options_off) + listOf("30", "45", "60", "90", "120")
        val fpsRow = addRow(container, R.string.profile_options_game_fps, fpsValues, fpsLabels, current.fps)

        val rendererValues = listOf(
            AppOptionsStore.RENDERER_FOLLOW,
            AppOptionsStore.RENDERER_OFF,
            "opengl",
            "skiagl",
            "skiavk"
        )
        val rendererLabels = labels(R.string.app_options_follow, R.string.app_options_off) +
            listOf("OpenGL", "SkiaGL", "Skia Vulkan")
        val rendererSpinner = addStringRow(
            container,
            R.string.profile_options_game_renderer,
            rendererValues,
            rendererLabels,
            current.renderer
        )

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            fun value(row: IntRow): Int = row.values[row.spinner.selectedItemPosition.coerceIn(0, row.values.size - 1)]
            val override = AppOptionsStore.Override(
                enabled = value(applyRow),
                lite = value(liteRow),
                preload = value(preloadRow),
                dnd = value(dndRow),
                bypass = value(bypassRow),
                downscale = value(downscaleRow),
                fps = value(fpsRow),
                renderer = rendererValues[rendererSpinner.selectedItemPosition.coerceIn(0, rendererValues.size - 1)],
                refresh = if (refreshRow != null) value(refreshRow) else current.refresh
            )
            AppOptionsStore.save(context, packageName, override)
            if (profileSpinner != null) {
                GameProfileStore.setOverride(
                    context,
                    packageName,
                    profileValues[profileSpinner.selectedItemPosition.coerceIn(0, profileValues.size - 1)]
                )
            }
            dialog.dismiss()
            // A profile change for the game in the foreground takes effect now.
            val mode = if (profileSpinner != null) GameProfileStore.modeFor(context, packageName) else ""
            if (mode.isNotEmpty() && mode != GameProfileStore.KEEP &&
                ProfileOptions.gamePackage == packageName
            ) {
                ModeSwitcher().executePowercfgMode(mode, packageName)
            } else {
                ProfileOptions.reapply(context)
            }
            EventBus.publish(EventType.SERVICE_UPDATE)
            Scene.toast(context.getString(R.string.profile_options_saved))
        }
    }

    private fun addRow(
        container: LinearLayout,
        labelRes: Int,
        values: List<Int>,
        labels: List<String>,
        current: Int
    ): IntRow {
        val row = context.layoutInflater.inflate(R.layout.item_option_spinner, container, false)
        row.findViewById<TextView>(R.id.option_label).setText(labelRes)
        val spinner = row.findViewById<Spinner>(R.id.option_value)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
        container.addView(row)
        return IntRow(labelRes, values, labels, spinner)
    }

    private fun addStringRow(
        container: LinearLayout,
        labelRes: Int,
        values: List<String>,
        labels: List<String>,
        current: String
    ): Spinner {
        val row = context.layoutInflater.inflate(R.layout.item_option_spinner, container, false)
        row.findViewById<TextView>(R.id.option_label).setText(labelRes)
        val spinner = row.findViewById<Spinner>(R.id.option_value)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
        container.addView(row)
        return spinner
    }

    private fun labels(vararg res: Int): List<String> = res.map { context.getString(it) }
}
