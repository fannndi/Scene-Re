package com.omarea.utils

import android.content.Context
import com.omarea.library.basic.AccessibleServiceState
import com.omarea.library.shell.AccessibilityServiceUtils
import com.omarea.vtools.AccessibilityScenceMode

/**
 * Created by Hello on 2018/06/03.
 */

class AccessibleServiceHelper {
    // Whether the Scene mode service is running
    fun serviceRunning(context: Context): Boolean {
        return AccessibleServiceState().serviceRunning(context, "AccessibilityScenceMode")
    }

    // Stop the Scene mode service
    fun stopSceneModeService(context: Context): Boolean {
        return AccessibilityServiceUtils().stopService("${context.packageName}/${AccessibilityScenceMode::class.java.name}")
    }

    // Start the Scene mode service
    fun startSceneModeService(context: Context): Boolean {
        return AccessibilityServiceUtils().startService("${context.packageName}/${AccessibilityScenceMode::class.java.name}")
    }

    fun serviceRunning(context: Context, serviceName: String): Boolean {
        return AccessibleServiceState().serviceRunning(context, serviceName)
    }
}
