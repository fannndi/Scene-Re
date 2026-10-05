package com.omarea.store

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.library.shell.PlatformUtils
import com.omarea.scene_mode.ModeSwitcher
import org.json.JSONObject
import java.io.File

/**
 * 调频模式配置（profiles/<mode>.json）
 *
 * - 内置默认来自 assets，安装后写入 files/profiles/default/（随应用更新）
 * - 用户编辑写入 files/profiles/<mode>.json（脚本优先读取，应用更新不覆盖）
 */
class ProfileStore(private val context: Context) {
    companion object {
        const val SCREEN_OFF = "screen_off"

        val EDITABLE_MODES = arrayOf(
            ModeSwitcher.POWERSAVE,
            ModeSwitcher.BALANCE,
            ModeSwitcher.PERFORMANCE,
            ModeSwitcher.FAST
        )
    }

    private fun assetPath(mode: String): String {
        return "powercfg/" + PlatformUtils().getCPUName() + "/profiles/$mode.json"
    }

    fun userPath(mode: String): String {
        return FileWrite.getPrivateFilePath(context, "profiles/$mode.json")
    }

    fun defaultPath(mode: String): String {
        return FileWrite.getPrivateFilePath(context, "profiles/default/$mode.json")
    }

    /** 是否已被用户编辑 */
    fun isCustomized(mode: String): Boolean {
        return File(userPath(mode)).exists()
    }

    private fun readJson(file: File): JSONObject? {
        return try {
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (ex: Exception) {
            null
        }
    }

    private fun readAsset(mode: String): JSONObject? {
        return try {
            context.assets.open(assetPath(mode)).bufferedReader().use { JSONObject(it.readText()) }
        } catch (ex: Exception) {
            null
        }
    }

    /** 内置默认值 */
    fun defaults(mode: String): JSONObject {
        return readJson(File(defaultPath(mode))) ?: readAsset(mode) ?: JSONObject()
    }

    /** 当前生效值（用户编辑覆盖内置默认） */
    fun merged(mode: String): JSONObject {
        val result = JSONObject(defaults(mode).toString())
        val user = readJson(File(userPath(mode))) ?: return result
        val keys = user.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, user.get(key))
        }
        return result
    }

    /** 保存用户编辑 */
    fun save(mode: String, values: Map<String, Any>): Boolean {
        return try {
            val merged = merged(mode)
            for ((key, value) in values) {
                merged.put(key, value)
            }
            val file = File(userPath(mode))
            file.parentFile?.mkdirs()
            file.writeText(merged.toString(2), Charsets.UTF_8)
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** 恢复内置默认（删除用户编辑文件） */
    fun reset(mode: String): Boolean {
        return try {
            val file = File(userPath(mode))
            if (file.exists()) {
                file.delete()
            }
            true
        } catch (ex: Exception) {
            false
        }
    }
}
