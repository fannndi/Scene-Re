package com.omarea.vtools.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.permissions.NotificationListener
import com.omarea.library.shell.CpuFrequencyUtils
import com.omarea.library.shell.GpuUtils
import com.omarea.library.shell.PlatformUtils
import com.omarea.permissions.CheckRootStatus
import com.omarea.scene_mode.ModeSwitcher
import com.omarea.utils.AccessibleServiceHelper
import com.omarea.vtools.BuildConfig
import com.omarea.vtools.privilege.PrivilegeManager
import org.json.JSONObject
import java.io.File

/**
 * Debug-only diagnostics for AI agents and developers.
 *
 * This object is compiled into the debug build only (`app/src/debug`).
 * It never modifies system state; it only reads capabilities and dumps logs.
 *
 * Typical agent workflow over USB:
 *   adb shell am start -n com.omarea.vtools/.debug.AgentDebugActivity
 *   adb shell cat /sdcard/Android/data/com.omarea.vtools/files/agent-debug-snapshot.json
 *   adb logcat -s SceneAgent
 */
object AgentDebug {
    const val LOG_TAG = "SceneAgent"
    const val SNAPSHOT_FILE_NAME = "agent-debug-snapshot.json"

    /** Structured log line that agents can filter with `adb logcat -s SceneAgent`. */
    fun log(event: String, detail: String = "") {
        if (detail.isEmpty()) {
            Log.i(LOG_TAG, event)
        } else {
            Log.i(LOG_TAG, "$event | $detail")
        }
    }

    /** Builds a JSON capability snapshot of the current device/app state. May perform shell reads. */
    fun buildSnapshot(context: Context): String {
        val json = JSONObject()
        try {
            val app = JSONObject()
            app.put("packageName", context.packageName)
            app.put("versionCode", BuildConfig.VERSION_CODE)
            app.put("versionName", BuildConfig.VERSION_NAME)
            app.put("buildType", BuildConfig.BUILD_TYPE)
            app.put("debuggable", BuildConfig.DEBUG)
            json.put("app", app)

            val device = JSONObject()
            device.put("manufacturer", Build.MANUFACTURER)
            device.put("model", Build.MODEL)
            device.put("device", Build.DEVICE)
            device.put("androidRelease", Build.VERSION.RELEASE)
            device.put("sdkInt", Build.VERSION.SDK_INT)
            device.put("soc", PlatformUtils().getCPUName())
            json.put("device", device)

            val capabilities = JSONObject()
            capabilities.put("root", CheckRootStatus.lastCheckResult)
            capabilities.put("accessibilityService", AccessibleServiceHelper().serviceRunning(context))
            capabilities.put("notificationListener", NotificationListener().getPermission(context))
            capabilities.put("adrenoGpu", GpuUtils.isAdrenoGPU())
            capabilities.put("gpuSupported", GpuUtils.supported())
            capabilities.put("cpuCores", CpuFrequencyUtils().coreCount)
            capabilities.put("cpuClusters", CpuFrequencyUtils().clusterInfo.size)
            json.put("capabilities", capabilities)

            val scene = JSONObject()
            scene.put("configSource", ModeSwitcher.getCurrentSource())
            scene.put("configSourceName", ModeSwitcher.getCurrentSourceName())
            scene.put("powerMode", ModeSwitcher.getCurrentPowerMode())
            json.put("scene", scene)

            val privilege = JSONObject()
            privilege.put("tier", PrivilegeManager.tier.storageValue)
            privilege.put("effectiveTier", PrivilegeManager.effectiveTier.storageValue)
            privilege.put("rootAvailable", PrivilegeManager.rootAvailable)
            privilege.put("isPrivileged", PrivilegeManager.isPrivileged)
            privilege.put("hasRootAccess", PrivilegeManager.hasRootAccess)
            privilege.put("shizukuAvailable", PrivilegeManager.shizukuAvailable)
            privilege.put("shizukuGranted", PrivilegeManager.shizukuPermissionGranted)
            privilege.put("shizukuVersion", PrivilegeManager.shizukuVersion)
            privilege.put("shizukuIsRoot", PrivilegeManager.shizukuIsRoot)
            privilege.put("suiActive", PrivilegeManager.suiActive)
            json.put("privilege", privilege)

            val memory = JSONObject()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            memory.put("totalBytes", info.totalMem)
            memory.put("availableBytes", info.availMem)
            memory.put("lowMemory", info.lowMemory)
            json.put("memory", memory)
        } catch (ex: Exception) {
            json.put("error", ex.message ?: ex.javaClass.simpleName)
        }
        return json.toString(2)
    }

    /** Writes the snapshot to the app external files dir and returns the absolute path. */
    fun writeSnapshot(context: Context, content: String): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, SNAPSHOT_FILE_NAME)
        file.writeText(content)
        return file.absolutePath
    }

    /** Dumps the current logcat buffer using the persistent root shell. */
    fun dumpLogcat(lines: Int = 1200): String {
        return try {
            KeepShellPublic.doCmdSync("logcat -d -t $lines -v threadtime 2>/dev/null")
        } catch (ex: Exception) {
            "logcat dump failed: ${ex.message}"
        }
    }
}
