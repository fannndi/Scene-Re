package com.omarea.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.basic.AccessibleServiceState
import com.omarea.vtools.AccessibilityScenceMode

/**
 * 辅助服务状态检查
 *
 * 「已启用」和「已连接」是两回事：MIUI 上常见 Settings 里已勾选，但服务从未
 * onServiceConnected（被系统回收 / 被自启动管理杀掉），此时
 * AccessibleServiceState.serviceRunning() 依然返回 true，动态响应却完全不生效。
 *
 * 因此分三层：
 *   enabled —— Settings 里是否勾选（无需 root）
 *   bound   —— 系统是否真的绑定了服务（dumpsys，需要 root）
 *   root    —— 修复动作依赖 shell
 */
enum class AccessibilityStatus {
    /** 一切正常 */
    OK,
    /** 拿不到 root，只能确认 enabled */
    OK_UNKNOWN,
    /** Settings 里没勾选 */
    NOT_ENABLED,
    /** 勾选了但系统没绑定（僵尸状态，需要修复） */
    NOT_BOUND
}

object AccessibilityChecker {
    private const val SERVICE_TAG = "AccessibilityScenceMode"

    /** Settings 里是否已勾选 */
    fun isServiceEnabled(context: Context): Boolean {
        return AccessibleServiceState().serviceRunning(context, SERVICE_TAG)
    }

    /** 服务的 label，dumpsys 的 Bound services 里打印的是它而不是 component */
    private fun serviceLabel(context: Context): String? {
        return try {
            val info = context.packageManager.getServiceInfo(
                ComponentName(context, AccessibilityScenceMode::class.java), 0
            )
            val label = info.loadLabel(context.packageManager)
            label?.toString()?.takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            null
        }
    }

    private fun shell(cmd: String): String? {
        return try {
            val out = KeepShellPublic.doCmdSync(cmd)
            if (out.isBlank() || out.contains("Can't find service")) null else out
        } catch (ex: Exception) {
            null
        }
    }

    /**
     * 系统是否真的绑定了服务。
     * 返回 null 表示无法判定（拿不到 root 或 dump 解析失败）。
     */
    fun isServiceBound(context: Context): Boolean? {
        val pkg = context.packageName

        // 主：activity services 里能看到 component，说明服务对象确实被创建并绑定了
        shell("dumpsys activity services $pkg")?.let { dump ->
            if (dump.contains(SERVICE_TAG)) return true
        }

        // 辅：accessibility 的 Bound services 打印的是 label
        val label = serviceLabel(context)
        shell("dumpsys accessibility")?.let { dump ->
            return isBoundInAccessibilityDump(dump, label)
        }

        return null
    }

    /**
     * 解析 dumpsys accessibility 的 Bound services 行。
     * 返回 null 表示 dump 不可用/格式不认识，调用方按「无法判定」处理。
     *
     * @param label 服务 label；为空时只按「有无绑定」判断
     */
    internal fun isBoundInAccessibilityDump(dump: String, label: String?): Boolean? {
        if (dump.isBlank() || dump.contains("Can't find service")) {
            return null
        }
        // 只看 Bound services 所在那一行，避免被后续段落误命中
        val line = dump.lineSequence().firstOrNull { it.contains("Bound services:") } ?: return null
        val bound = line.substringAfter("Bound services:")
            .trim()
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
        // 空 / {} = 一个都没绑
        if (bound.isEmpty()) return false
        // label 拿不到时退化为「有绑定即视为已连接」
        return if (label.isNullOrEmpty()) true else bound.contains(label)
    }

    /** 综合判定 */
    fun check(context: Context, rootAvailable: Boolean): AccessibilityStatus {
        if (!isServiceEnabled(context)) {
            return AccessibilityStatus.NOT_ENABLED
        }
        if (!rootAvailable) {
            return AccessibilityStatus.OK_UNKNOWN
        }
        return when (isServiceBound(context)) {
            true -> AccessibilityStatus.OK
            false -> AccessibilityStatus.NOT_BOUND
            null -> AccessibilityStatus.OK_UNKNOWN
        }
    }

    /**
     * 修复：强制系统重新绑定。
     * 未启用 -> 直接启用；已启用但没连上 -> 摘掉再装回，逼系统重新 bind。
     * 返回 false 表示没拿到 root，动作没有发起。
     */
    fun repair(context: Context): Boolean {
        val component = ComponentName(context, AccessibilityScenceMode::class.java).flattenToString()
        val existing = shell("settings get secure enabled_accessibility_services")?.trim()

        val cmds = if (existing.isNullOrEmpty() || existing == "null") {
            "settings put secure enabled_accessibility_services $component\n" +
                    "settings put secure accessibility_enabled 1"
        } else {
            val list = existing.split(":").map { it.trim() }.filter { it.isNotEmpty() }
            if (!list.contains(component)) {
                "settings put secure enabled_accessibility_services ${list.joinToString(":")}:$component\n" +
                        "settings put secure accessibility_enabled 1"
            } else {
                val others = list.filter { it != component }
                if (others.isEmpty()) {
                    "settings put secure accessibility_enabled 0\n" +
                            "settings put secure enabled_accessibility_services $component\n" +
                            "settings put secure accessibility_enabled 1"
                } else {
                    "settings put secure enabled_accessibility_services ${others.joinToString(":")}\n" +
                            "sleep 1\n" +
                            "settings put secure enabled_accessibility_services ${others.joinToString(":")}:$component\n" +
                            "settings put secure accessibility_enabled 1"
                }
            }
        }

        return try {
            KeepShellPublic.doCmdSync(cmds)
            true
        } catch (ex: Exception) {
            false
        }
    }
}
