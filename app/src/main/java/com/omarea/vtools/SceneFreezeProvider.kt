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

    private var config: SharedPreferences? = null
    private fun allowXposedOpen(): Boolean {
        if (config == null) {
            config = Scene.context.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        }
        return config!!.getBoolean(SpfConfig.GLOBAL_SPF_FREEZE_XPOSED_OPEN, false)
    }

    private val whiteList = arrayOf(
            "android",
            "com.android.quicksearchbox", // 搜索
            "com.android.settings", // 设置
            // nova
            "com.teslacoilsw.launcher",
            // poco
            "com.mi.android.globallauncher",
            // miui
            "com.miui.home",
            // lawnchair 测试版
            "ch.deletescape.lawnchair.ci",
            // 一加桌面
            "net.oneplus.launcher",
            // 一加氢桌面
            "net.oneplus.h2launcher",
            // 一加hydrogen桌面
            "com.oneplus.hydrogen.launcher",
            // 微软桌面
            "com.microsoft.launcher",
            // LineageOS桌面
            "org.lineageos.trebuchet",
            // 魔趣桌面
            "org.mokee.lawnchair",
            // Pixel 启动器
            "com.google.android.apps.nexuslauncher")

    override fun getType(uri: Uri): String {
        return "application/json"
    }

    // 调用方声明的 source 必须是调用方 UID 真实拥有的包名，防止伪造白名单
    private fun isCallerSource(source: String): Boolean {
        val currentContext = context ?: Scene.context
        return try {
            val callingPackages = currentContext.packageManager.getPackagesForUid(Binder.getCallingUid())
            callingPackages?.contains(source) == true
        } catch (ex: Exception) {
            false
        }
    }

    // 解冻
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values != null && values.containsKey("packageName") && values.containsKey("source")) {
            val packageName = values.getAsString("packageName")
            val source = values.getAsString("source")
            val currentContext = context ?: Scene.context

            // 只允许操作真实安装且包名合法的应用，且调用方身份必须与 source 匹配
            if (ShellSafety.isValidPackageName(packageName) &&
                    ShellSafety.isInstalledPackage(currentContext, packageName) &&
                    source != null && isCallerSource(source) &&
                    (whiteList.contains(source) || allowXposedOpen())) {
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
