package com.omarea.library.shell

import android.os.Build
import com.omarea.common.shell.KeepShellPublic

/**
 * Location toggle
 */
class LocationHelper {
    /**
     * Enable GPS
     */
    fun enableGPS() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
        // 1:GPS      2:GPRS WIFI      3: ALL
            KeepShellPublic.doCmdSync("settings put secure location_mode 3")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            KeepShellPublic.doCmdSync("settings put secure location_providers_allowed +gps")
        else
            KeepShellPublic.doCmdSync("settings put secure location_providers_allowed gps,network")
    }

    /**
     * Disable GPS
     */
    fun disableGPS() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
            KeepShellPublic.doCmdSync("settings put secure location_mode 0")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeepShellPublic.doCmdSync("settings put secure location_providers_allowed -gps")
        } else {
            KeepShellPublic.doCmdSync("settings put secure location_providers_allowed network")
        }
    }

    /**
     * Disable location
     */
    fun disableLocation() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
            KeepShellPublic.doCmdSync("settings put secure location_mode 0")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            KeepShellPublic.doCmdSync("settings put secure location_providers_allowed -gps,-network")
        } else {
            KeepShellPublic.doCmdSync("settings delete secure location_providers_allowed")
        }
    }
}
