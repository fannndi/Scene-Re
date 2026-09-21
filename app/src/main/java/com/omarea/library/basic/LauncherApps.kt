package com.omarea.library.basic

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import java.util.*

class LauncherApps(private val context: Context) {
    // MIUI settings is counted as a launcher, weird
    // launcher apps (home screen)
    val launcherApps: ArrayList<String>
        get() {
            val resolveIntent = Intent(Intent.ACTION_MAIN, null)
            resolveIntent.addCategory(Intent.CATEGORY_HOME)
            val resolveinfoList: List<ResolveInfo> = context.packageManager.queryIntentActivities(resolveIntent, 0)
            val launcherApps = ArrayList<String>()
            for (resolveInfo in resolveinfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                if ("com.android.settings" != packageName) { // MIUI settings counts as a launcher, weird
                    launcherApps.add(packageName)
                }
            }
            return launcherApps
        }
}