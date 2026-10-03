package com.omarea.ui.dialog

import android.app.Activity
import android.view.View
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.util.CheckRootStatus
import com.omarea.vtools.R

/**
 * Power menu (shutdown/reboot/hot restart/recovery/fastboot/9008 EDL).
 *
 * Two steps for every entry: the menu picks, a confirmation shows the exact
 * root command before anything runs — and the mode-changing entries use the
 * warning dialog, because a bad reboot target is not one tap away anymore.
 *
 * Responsibility: ask + run the command.
 * Non-goals: power state (the ROM owns that).
 */
class DialogPower(var context: Activity) {
    fun showPowerMenu() {
        // Reboot/shutdown are privileged: no root, no menu (hard rule 16).
        if (!CheckRootStatus.isAvailable()) {
            android.widget.Toast.makeText(
                context, R.string.settings_tools_no_root, android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val view = context.layoutInflater.inflate(R.layout.dialog_power_operation, null)
        val dialog = DialogHelper.customDialog(context, view)

        fun item(id: Int, labelRes: Int, cmdRes: Int, danger: Boolean = false) {
            view.findViewById<View>(id).setOnClickListener {
                dialog.dismiss()
                confirmAndRun(
                    context.getString(labelRes),
                    context.getString(cmdRes),
                    danger
                )
            }
        }

        item(R.id.power_shutdown, R.string.power_shutdown, R.string.power_shutdown_cmd)
        item(R.id.power_reboot, R.string.power_reboot, R.string.power_reboot_cmd)
        item(R.id.power_hot_reboot, R.string.power_hot_reboot, R.string.power_hot_reboot_cmd, danger = true)
        item(R.id.power_recovery, R.string.power_recovery, R.string.power_recovery_cmd, danger = true)
        item(R.id.power_fastboot, R.string.power_fastboot, R.string.power_fastboot_cmd, danger = true)
        item(R.id.power_emergency, R.string.power_emergency, R.string.power_emergency_cmd, danger = true)
    }

    private fun confirmAndRun(label: String, cmd: String, danger: Boolean) {
        if (danger) {
            DialogHelper.warning(
                context,
                context.getString(R.string.power_danger_title, label),
                context.getString(R.string.power_danger_msg, label, cmd),
                Runnable { run(cmd) },
                null
            )
        } else {
            DialogHelper.confirm(
                context,
                context.getString(R.string.power_confirm_title, label),
                context.getString(R.string.power_confirm_msg, cmd),
                Runnable { run(cmd) },
                null
            )
        }
    }

    private fun run(cmd: String) {
        Thread { KeepShellPublic.doCmdSync(cmd) }.start()
    }
}
