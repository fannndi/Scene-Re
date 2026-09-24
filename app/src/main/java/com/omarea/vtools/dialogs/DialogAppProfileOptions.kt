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
import com.omarea.scene_mode.AppOptionsStore
import com.omarea.scene_mode.ProfileOptions
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
        val rows = ArrayList<IntRow>()

        val triStateValues = listOf(AppOptionsStore.FOLLOW, 1, 0)
        val triStateLabels = labels(R.string.app_options_follow, R.string.app_options_on, R.string.app_options_off)

        rows.add(addRow(container, R.string.profile_options_lite, triStateValues, triStateLabels, current.lite))
        rows.add(addRow(container, R.string.profile_options_preload, triStateValues, triStateLabels, current.preload))
        rows.add(addRow(container, R.string.profile_options_dnd, triStateValues, triStateLabels, current.dnd))
        rows.add(addRow(container, R.string.profile_options_bypass, triStateValues, triStateLabels, current.bypass))

        val downscaleValues = listOf(AppOptionsStore.FOLLOW, 0) + (95 downTo 50 step 5).toList()
        val downscaleLabels = labels(R.string.app_options_follow, R.string.app_options_off) +
            (95 downTo 50 step 5).map { "$it%" }
        rows.add(addRow(container, R.string.profile_options_game_downscale, downscaleValues, downscaleLabels, current.downscale))

        val fpsValues = listOf(AppOptionsStore.FOLLOW, 0, 30, 45, 60, 90, 120)
        val fpsLabels = labels(R.string.app_options_follow, R.string.app_options_off) + listOf("30", "45", "60", "90", "120")
        rows.add(addRow(container, R.string.profile_options_game_fps, fpsValues, fpsLabels, current.fps))

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
                lite = value(rows[0]),
                preload = value(rows[1]),
                dnd = value(rows[2]),
                bypass = value(rows[3]),
                downscale = value(rows[4]),
                fps = value(rows[5]),
                renderer = rendererValues[rendererSpinner.selectedItemPosition.coerceIn(0, rendererValues.size - 1)]
            )
            AppOptionsStore.save(context, packageName, override)
            dialog.dismiss()
            ProfileOptions.reapply(context)
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
