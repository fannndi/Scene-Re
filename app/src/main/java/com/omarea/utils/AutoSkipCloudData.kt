package com.omarea.utils

import android.content.Context
import com.omarea.Scene

/**
 * Cloud auto-skip configs were removed in Scene-Re (offline build).
 * Kept as a no-op so existing call sites keep working.
 */
class AutoSkipCloudData {
    fun updateConfig(context: Context, showMsg: Boolean) {
        if (showMsg) {
            Scene.toast("Cloud configs are disabled in this build")
        }
    }
}
