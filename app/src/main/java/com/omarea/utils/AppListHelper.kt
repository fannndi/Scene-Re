package com.omarea.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.omarea.model.AppInfo
import java.io.File
import java.util.*


/**
 * Created by helloklf on 2017/12/01.
 */
class AppListHelper(private val context: Context, private val getTags: Boolean = true) {
    var packageManager: PackageManager
    private fun getVersionCode(packageInfo: PackageInfo): Int {
        return PackageInfoCompat.getLongVersionCode(packageInfo).toInt()
    }

    private fun exclude(packageName: String): Boolean {
        if (
                packageName.contains(".overlay") ||
                packageName.contains("com.android.theme.color") ||
                packageName.contains("com.android.theme.icon")) {
            return true
        }
        return false
    }

    fun getTags(applicationInfo: ApplicationInfo): String {
        val stateTags = StringBuilder()
        val readDir = CommonCmds.AbsBackUpDir
        try {
            if (!applicationInfo.enabled) {
                stateTags.append("❄Frozen ")
            }
            if ((applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0) {
                stateTags.append("🚫Disabled ")
            }
            if (isSystemApp(applicationInfo) && applicationInfo.sourceDir.startsWith("/data")) {
                stateTags.append("🔒Updated system app ")
            }
            val packageName = applicationInfo.packageName
            val absPath = readDir + packageName + ".apk"
            if (File(absPath).exists()) {
                val backupInfo = packageManager.getPackageArchiveInfo(absPath, PackageManager.GET_ACTIVITIES)!!
                val installInfo = packageManager.getPackageInfo(applicationInfo.packageName, 0)
                if (installInfo == null)
                    return ""
                if (getVersionCode(backupInfo) == getVersionCode(installInfo)) {
                    stateTags.append("⭐Backed up ")
                } else if (getVersionCode(backupInfo) > getVersionCode(installInfo)) {
                    stateTags.append("💔Older than backup ")
                } else {
                    stateTags.append("♻Newer than backup ")
                }
            } else if (File(readDir + packageName + ".tar.gz").exists()) {
                stateTags.append("🔄Backup data available ")
            }
        } catch (ex: Exception) {
        }
        return stateTags.toString().trim()
    }

    /**
     * 检查已安装版本
     */
    fun isSystemApp(applicationInfo: ApplicationInfo): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    fun getAppList(systemApp: Boolean? = null, removeIgnore: Boolean = true): ArrayList<AppInfo> {
        val packageInfoList = packageManager.getInstalledApplications(0)

        val list = ArrayList<AppInfo>()/*在数组中存放数据*/
        for (i in packageInfoList.indices) {
            val applicationInfo = packageInfoList[i]

            val appInfo = getApplicationInfo(applicationInfo, systemApp, removeIgnore)
            if (appInfo != null) {
                list.add(appInfo)
            }
        }
        return (list)
    }

    private fun getApplicationInfo(applicationInfo: ApplicationInfo, systemApp: Boolean? = null, removeIgnore: Boolean = true): AppInfo? {
        val appPath = applicationInfo.sourceDir
        if (appPath == null || (removeIgnore && exclude(applicationInfo.packageName))) {
            return null
        }

        if (
        // appPath.startsWith("/vendor") ||
                (systemApp == false && !(appPath.startsWith("/data") || (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0)) ||
                (systemApp == true && (appPath.startsWith("/data") || (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0))
        ) {
            return null
        }

        // ApplicationInfo.FLAG_SYSTEM

        val file = File(applicationInfo.publicSourceDir)
        if (!file.exists())
            return null

        val item = AppInfo.getItem()
        //val d = packageInfo.loadIcon(packageManager)
        item.appName = "" + applicationInfo.loadLabel(packageManager)
        item.packageName = applicationInfo.packageName
        //item.icon = d
        item.dir = file.parent
        item.enabled = applicationInfo.enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            item.suspended = (applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        }
        if (getTags) {
            item.stateTags = getTags(applicationInfo)
        }
        item.path = appPath
        item.updated = isSystemApp(applicationInfo) && (appPath.startsWith("/data") || (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0)
        item.appType = (if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
            AppInfo.AppType.SYSTEM
        } else if ((appPath.startsWith("/data") || (applicationInfo.flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0)) {
            AppInfo.AppType.USER
        } else {
            AppInfo.AppType.SYSTEM
        })
        item.targetSdkVersion = applicationInfo.targetSdkVersion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            item.minSdkVersion = applicationInfo.minSdkVersion
        }

        try {
            val packageInfo = packageManager.getPackageInfo(applicationInfo.packageName, 0)
            item.versionName = packageInfo.versionName
            item.versionCode = getVersionCode(packageInfo)
        } catch (ex: Exception) {
        }

        return item
    }

    fun getAll(): ArrayList<AppInfo> {
        return getAppList(null, false)
    }

    init {
        packageManager = context.packageManager
    }
}
