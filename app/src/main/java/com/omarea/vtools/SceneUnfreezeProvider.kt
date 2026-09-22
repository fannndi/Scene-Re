package com.omarea.vtools

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.omarea.Scene
import com.omarea.scene_mode.SceneMode
import com.omarea.store.SceneConfigStore
import com.omarea.store.SpfConfig

class SceneUnfreezeProvider : ContentProvider() {
    // Freeze test: adb shell content delete --uri content://com.omarea.vtools.SceneUnfreezeProvider --where "id in ('com.estrongs.android.pop')"
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        // Log.d("SceneUnfreezeProvider", "" + selection)
        // Log.d("SceneUnfreezeProvider", "" + selectionArgs?.joinToString { "," })
        if (selection != null) {
            val store = SceneConfigStore(context)
            val apps = store.queryAppConfig(selection, selectionArgs)
            var count = 0
            for (app in apps) {
                if (app.freeze) {
                    SceneMode.freezeApp(app.packageName)
                    count ++
                }
            }
            store.close()
            // Log.d("SceneFreezeProvider", ">" + count)
            return count
        }
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

    // Unfreeze
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values != null && values.containsKey("packageName") && values.containsKey("source")) {
            val packageName = values.get("packageName").toString()
            val source = values.get("source").toString()
            if (whiteList.contains(source)) {
                SceneMode.unfreezeApp(packageName)
            }
            return uri;
        }
        return null
    }

    override fun onCreate(): Boolean {
        return true;
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
