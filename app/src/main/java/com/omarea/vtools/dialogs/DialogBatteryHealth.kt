package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.scene_mode.power.BatteryHealth
import com.omarea.vtools.R

/**
 * Read-only battery health report (cycles, capacity, temperature, health).
 * The values come from one shell round trip, loaded off the main thread.
 * Shares dialog_text_report.xml with the other report dialogs.
 */
class DialogBatteryHealth(private val context: Activity) {
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_text_report, null)
        val dialog = DialogHelper.customDialog(context, view)

        view.findViewById<TextView>(R.id.report_title).text = context.getString(R.string.battery_health_title)
        val body = view.findViewById<TextView>(R.id.report_body)
        val refresh = view.findViewById<View>(R.id.report_refresh)
        body.text = "\u2026"
        view.findViewById<View>(R.id.report_close).setOnClickListener { dialog.dismiss() }

        fun load() {
            refresh.isEnabled = false
            body.text = "\u2026"
            Thread {
                val report = BatteryHealth.report()
                Scene.post {
                    body.text = report
                    refresh.isEnabled = true
                }
            }.start()
        }

        load()
        refresh.setOnClickListener { load() }
    }
}
