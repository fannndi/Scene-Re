package com.omarea.library.shell

import android.util.Log
import com.omarea.common.shell.KeepShellPublic

/**
 * Created by Hello on 2018/06/03.
 *
 * Reads and writes `enabled_accessibility_services` through the active shell backend.
 *
 * Both settings are in the `secure` namespace, so writing them needs
 * `WRITE_SECURE_SETTINGS`. Root and Shizuku have it; the app's own uid does not, and the write
 * fails silently - which is why every mutation is read back and logged instead of assumed.
 */

class AccessibilityServiceUtils {
    /*
    # Start the service via shell

    settings put secure accessibility_enabled 0
    services=`settings get secure enabled_accessibility_services`
    service='com.omarea.vtools/com.omarea.vtools.AccessibilityScenceMode'
    include=`echo "$services" | grep "$service"`
    if [ ! -n "$services" ]; then
      settings put secure enabled_accessibility_services "$service"
    elif [ ! -n "$include" ]; then
      settings put secure enabled_accessibility_services "$services:$service"
    fi
    settings put secure accessibility_enabled 1
    */

    companion object {
        private const val TAG = "SceneAccessibility"
    }

    /** The raw `enabled_accessibility_services` value, or an empty list when unset. */
    private fun enabledServices(): List<String> {
        return KeepShellPublic.doCmdSync("settings get secure enabled_accessibility_services")
            .trim()
            .split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
    }

    /** Re-reads the setting so a refused write is reported rather than silently swallowed. */
    private fun verify(logMessage: String, expected: List<String>): Boolean {
        val actual = enabledServices()
        val ok = actual.toSet() == expected.toSet()
        if (ok) {
            Log.i(TAG, "$logMessage ok enabled=$actual")
        } else {
            Log.w(
                TAG,
                "$logMessage FAILED expected=$expected actual=$actual " +
                        "- writing secure settings needs WRITE_SECURE_SETTINGS (root or Shizuku)"
            )
        }
        return ok
    }

    fun stopService(serviceName: String): Boolean {
        val servicesStr = KeepShellPublic.doCmdSync("settings get secure enabled_accessibility_services").trim()
        if (!servicesStr.contains(serviceName)) {
            return true
        }
        val remaining = servicesStr
            .split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" && it != serviceName }

        if (remaining.isEmpty()) {
            // Write the literal "null" so the setting reads as unset rather than as an empty
            // string, and only then clear accessibility_enabled.
            KeepShellPublic.doCmdSync(
                "settings put secure enabled_accessibility_services null\n" +
                        "settings put secure accessibility_enabled 0"
            )
        } else {
            // Other services are still enabled, so accessibility_enabled must stay 1 or we would
            // take them down with us.
            KeepShellPublic.doCmdSync(
                "settings put secure enabled_accessibility_services ${remaining.joinToString(":")}\n" +
                        "settings put secure accessibility_enabled 1"
            )
        }
        return verify("stopService($serviceName)", remaining)
    }

    fun startService(serviceName: String): Boolean {
        val existing = enabledServices()
        val updated = if (existing.contains(serviceName)) {
            existing
        } else {
            existing + serviceName
        }
        KeepShellPublic.doCmdSync(
            "settings put secure enabled_accessibility_services ${updated.joinToString(":")}\n" +
                    "settings put secure accessibility_enabled 1"
        )
        return verify("startService($serviceName)", updated)
    }
}
