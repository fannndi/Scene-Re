package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.ui.DialogHelper
import com.omarea.vtools.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root reboot actions. Every entry asks for confirmation first, because two of
 * them (bootloader, power off) are irreversible without physical access.
 */
class DialogRebootOptions(private val context: Activity) {

    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_reboot_options, null)
        val dialog = DialogHelper.customDialog(context, view)

        bind(view.findViewById(R.id.reboot_system), R.string.reboot_system, "reboot")
        bind(view.findViewById(R.id.reboot_recovery), R.string.reboot_recovery, "reboot recovery")
        bind(view.findViewById(R.id.reboot_bootloader), R.string.reboot_bootloader, "reboot bootloader")
        bind(view.findViewById(R.id.reboot_soft), R.string.reboot_soft, "setprop ctl.restart zygote")
        bind(view.findViewById(R.id.reboot_poweroff), R.string.reboot_poweroff, "reboot -p")

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun bind(button: View, labelRes: Int, command: String) {
        button.setOnClickListener {
            DialogHelper.confirm(context, context.getString(labelRes), context.getString(R.string.reboot_confirm), {
                // Keep the dialog open on purpose: the device is about to go down.
                GlobalScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.IO) { KeepShellPublic.doCmdSync(command) }
                }
            })
        }
    }
}
