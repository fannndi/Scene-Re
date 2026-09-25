package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.omarea.common.ui.DialogHelper
import com.omarea.scene_mode.BatteryHealth
import com.omarea.vtools.R

/**
 * Read-only battery health report (cycles, capacity, temperature, health).
 * Shares dialog_text_report.xml with the other report dialogs.
 */
class DialogBatteryHealth(private val context: Activity) {
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_text_report, null)
        val dialog = DialogHelper.customDialog(context, view)

        view.findViewById<TextView>(R.id.report_title).text = context.getString(R.string.battery_health_title)
        val body = view.findViewById<TextView>(R.id.report_body)
        body.text = BatteryHealth.report()

        view.findViewById<View>(R.id.report_refresh).setOnClickListener {
            body.text = BatteryHealth.report()
        }
        view.findViewById<View>(R.id.report_close).setOnClickListener { dialog.dismiss() }
    }
}
