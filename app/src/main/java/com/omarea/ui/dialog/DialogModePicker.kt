package com.omarea.ui.dialog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.omarea.engine.ProfileKey
import com.omarea.runtime.ModeSwitcher
import com.omarea.runtime.TrueOff
import com.omarea.util.CheckRootStatus
import com.omarea.vtools.R

/**
 * In-app fallback for the quick-switch entry points (QS tile, notification,
 * Home mode row) when the overlay permission is missing.
 *
 * The overlay picker stays the enhancement; without permission this dialog
 * still applies a mode right away and offers the *real* permission screen —
 * the old path stopped at a toast (the intent was built and dropped).
 *
 * Responsibility: pick + apply one mode, or route to the overlay grant.
 * Non-goals: the overlay picker itself (FloatPowercfgSelector).
 */
class DialogModePicker(private val context: Activity, private val onDismiss: () -> Unit = {}) {

    private val modes = ProfileKey.ALL.toList()

    fun show() {
        val labels = modes.map { labelOf(it) }.toTypedArray()
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.mode_picker_title)
            .setItems(labels) { _, which -> apply(modes[which]) }
            .setNeutralButton(R.string.mode_picker_overlay) { _, _ -> openOverlaySettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { onDismiss() }
        builder.show()
    }

    private fun apply(mode: String) {
        if (!CheckRootStatus.isAvailable()) {
            Toast.makeText(context, R.string.settings_tools_no_root, Toast.LENGTH_LONG).show()
            return
        }
        if (!TrueOff.guardOrToast(context)) return
        Thread {
            ModeSwitcher().executePowercfgMode(mode, context.packageName)
            context.runOnUiThread {
                Toast.makeText(
                    context,
                    context.getString(R.string.mode_picker_applied, labelOf(mode)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    /** The *correct* screen (the old code built an intent and never fired it). */
    private fun openOverlaySettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    private fun labelOf(mode: String): String = when (mode) {
        ProfileKey.POWERSAVE -> context.getString(R.string.powersave)
        ProfileKey.BALANCE -> context.getString(R.string.balance)
        ProfileKey.PERFORMANCE -> context.getString(R.string.performance)
        else -> context.getString(R.string.fast)
    }
}
