package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.utils.KernelCapabilities
import com.omarea.utils.PlatformCapabilities
import com.omarea.vtools.R

/**
 * Read-only report of the kernel nodes the app probes, with a refresh button.
 * The probe is a shell round trip, so it runs off the main thread and only
 * the result is posted back. Shares dialog_text_report.xml with the game
 * session history dialog.
 */
class DialogKernelFeatures(private val context: Activity) {
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_text_report, null)
        val dialog = DialogHelper.customDialog(context, view)

        val body = view.findViewById<TextView>(R.id.report_body)
        val refresh = view.findViewById<View>(R.id.report_refresh)
        body.text = "\u2026"
        view.findViewById<View>(R.id.report_close).setOnClickListener { dialog.dismiss() }

        fun load(force: Boolean) {
            refresh.isEnabled = false
            body.text = "\u2026"
            Thread {
                val report = PlatformCapabilities.report(context) + "\n\n" +
                    KernelCapabilities.report(context, force)
                Scene.post {
                    body.text = report
                    refresh.isEnabled = true
                }
            }.start()
        }

        load(force = false)
        refresh.setOnClickListener { load(force = true) }
    }
}
