package com.omarea.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * Input validation and shell escaping utilities.
 * All data from external components (Intent, ContentProvider, etc.) must pass through here before reaching the shell.
 */
object ShellSafety {
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
    private val TASK_ID_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isValidPackageName(packageName: String?): Boolean {
        return packageName != null && packageName.length <= 200 && PACKAGE_NAME_REGEX.matches(packageName)
    }

    fun isInstalledPackage(context: Context, packageName: String?): Boolean {
        if (!isValidPackageName(packageName)) {
            return false
        }
        return try {
            context.packageManager.getApplicationInfo(packageName!!, 0)
            true
        } catch (ex: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isValidTaskId(taskId: String?): Boolean {
        return taskId != null && TASK_ID_REGEX.matches(taskId)
    }

    fun isValidMac(mac: String?): Boolean {
        return mac != null && MAC_REGEX.matches(mac)
    }

    /**
     * Safely wrap a string as a shell single-quoted argument to prevent command injection ($(), backticks, ;, newlines, etc.).
     */
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
