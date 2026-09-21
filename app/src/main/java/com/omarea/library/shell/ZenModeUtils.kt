package com.omarea.library.shell

import android.content.Context
import android.provider.Settings
import com.omarea.common.shell.KeepShellPublic

// DND mode
class ZenModeUtils(private val context: Context) {
    /*
        // silent mode (mostly replaced by DND mode)
        val audioManager = this.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
    */

    // copied from Settings.Global
    val ZEN_MODE_OFF = 0

    // val ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1
    val ZEN_MODE_NO_INTERRUPTIONS = 2
    // val ZEN_MODE_ALARMS = 3

    fun on() {
        val contentResolver = context.contentResolver
        if (Settings.Global.putInt(contentResolver, "zen_mode", ZEN_MODE_NO_INTERRUPTIONS)) {
            return
        } else {
            KeepShellPublic.doCmdSync("settings put global zen_mode $ZEN_MODE_NO_INTERRUPTIONS")
        }
    }

    fun off() {
        val contentResolver = context.contentResolver
        if (Settings.Global.putInt(contentResolver, "zen_mode", ZEN_MODE_OFF)) {
            return
        } else {
            KeepShellPublic.doCmdSync("settings put global zen_mode $ZEN_MODE_OFF")
        }
    }
}