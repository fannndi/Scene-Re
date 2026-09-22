package com.omarea.vtools

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import com.omarea.Scene
import com.omarea.scene_mode.SceneMode
import com.omarea.store.SpfConfig
import com.omarea.utils.ShellSafety

class SceneFreezeProvider : ContentProvider() {
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    private val whiteList = arrayOf(
            "android",
            "com.android.quicksearchbox", // Search
            "com.android.settings", // Settings
            // nova
            "com.teslacoilsw.launcher",
            // poco
            "com.mi.android.globallauncher",
            // miui
            "com.miui.home",
            // Lawnchair beta
            "ch.deletescape.lawnchair.ci",
            // OnePlus launcher
            "net.oneplus.launcher",
            // OnePlus Hydrogen launcher
            "net.oneplus.h2launcher",
            // OnePlus Hydrogen launcher
            "com.oneplus.hydrogen.launcher",
            // Microsoft launcher
            "com.microsoft.launcher",
            // LineageOS launcher
            "org.lineageos.trebuchet",
            // MoKee launcher
            "org.mokee.lawnchair",
            // Pixel launcher
            "com.google.android.apps.nexuslauncher")

    override fun getType(uri: Uri): String {
        return "application/json"
    }

    // The source declared by the caller must be a package name actually owned by the caller's UID, to prevent whitelist spoofing
    private fun isCallerSource(source: String): Boolean {
        val currentContext = context ?: Scene.context
        return try {
            val callingPackages = currentContext.packageManager.getPackagesForUid(Binder.getCallingUid())
            callingPackages?.contains(source) == true
        } catch (ex: Exception) {
            false
        }
    }

    // Unfreeze
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values != null && values.containsKey("packageName") && values.containsKey("source")) {
            val packageName = values.getAsString("packageName")
            val source = values.getAsString("source")
            val currentContext = context ?: Scene.context

            // Only allow operations on apps that are really installed with a valid package name; the caller identity must match source
            if (ShellSafety.isValidPackageName(packageName) &&
                    ShellSafety.isInstalledPackage(currentContext, packageName) &&
                    source != null && isCallerSource(source) &&
                    (whiteList.contains(source))) {
                SceneMode.unfreezeApp(packageName!!)
            }
            return uri
        }
        return null
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int {
        return 0
    }
}
