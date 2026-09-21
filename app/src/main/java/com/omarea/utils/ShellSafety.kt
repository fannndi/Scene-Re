package com.omarea.utils

import android.content.Context
import android.content.pm.PackageManager

/**
 * 输入校验与 shell 转义工具。
 * 所有来自外部组件（Intent、ContentProvider 等）的数据在进入 shell 之前都必须经过这里。
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
     * 将字符串安全地包裹为 shell 单引号参数，防止命令注入（$()、反引号、;、换行等）。
     */
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
