package com.omarea.ui.activity

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.omarea.runtime.ModeSwitcher
import com.omarea.ui.dialog.DialogModePicker
import com.omarea.ui.popup.FloatPowercfgSelector
import com.omarea.vtools.R

/**
 * Trampoline behind the QS tiles, the status notification and the Home mode
 * row: opens the overlay picker when the permission is there, otherwise an
 * in-app mode picker plus the real permission screen — never just a toast
 * (the old path built the permission intent and dropped it).
 */
class ActivityPowerModeTile : ActivityBase() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ModeSwitcher().modeConfigCompleted()) {
            Toast.makeText(this, R.string.mode_picker_no_config, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            FloatPowercfgSelector(applicationContext).open(packageName)
            finish()
        } else {
            // Keep the activity alive for the dialog; it finishes on dismiss.
            DialogModePicker(this, onDismiss = { finish() }).show()
        }
    }
}
