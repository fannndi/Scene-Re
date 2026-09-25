package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.omarea.common.ui.DialogHelper
import com.omarea.scene_mode.KernelCapabilities
import com.omarea.vtools.R

/**
 * Read-only report of the kernel nodes the app probes, with a refresh button.
 * Shares dialog_text_report.xml with the game session history dialog.
 */
class DialogKernelFeatures(private val context: Activity) {
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_text_report, null)
        val dialog = DialogHelper.customDialog(context, view)

        val body = view.findViewById<TextView>(R.id.report_body)
        body.text = KernelCapabilities.report(context)

        view.findViewById<View>(R.id.report_refresh).setOnClickListener {
            KernelCapabilities.refresh(context)
            body.text = KernelCapabilities.report(context)
        }
        view.findViewById<View>(R.id.report_close).setOnClickListener { dialog.dismiss() }
    }
}
