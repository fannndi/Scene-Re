package com.omarea.vtools.privilege

import android.content.Context
import com.omarea.vtools.R

/** Result states of the Shizuku health check. */
enum class ShizukuHealthState {
    OK,
    NOT_INSTALLED,
    SERVICE_OFFLINE,
    PERMISSION_DENIED,
    PERMISSION_DENIED_PERMANENTLY,
    VERSION_TOO_OLD,
    SHELL_UNAVAILABLE
}

data class ShizukuHealth(
    val state: ShizukuHealthState,
    val message: String
) {
    val healthy: Boolean
        get() = state == ShizukuHealthState.OK
}

/**
 * Checks whether the Shizuku tier can actually run commands right now.
 *
 * Called when the app is opened (and from the setup screen) so the user gets a precise reason and a
 * matching action instead of silently failing shell calls. Read-only: it never starts or stops
 * anything.
 */
object ShizukuHealthCheck {
    /** Shizuku API 10 is required for user services (our shell host). */
    const val MIN_API_VERSION = 10

    fun check(context: Context): ShizukuHealth {
        PrivilegeManager.refreshShizuku()

        if (!PrivilegeManager.shizukuAvailable && !PrivilegeManager.isShizukuInstalled(context) && !PrivilegeManager.suiActive) {
            return ShizukuHealth(ShizukuHealthState.NOT_INSTALLED, context.getString(R.string.shizuku_health_not_installed))
        }
        if (!PrivilegeManager.shizukuAvailable) {
            return ShizukuHealth(ShizukuHealthState.SERVICE_OFFLINE, context.getString(R.string.shizuku_health_offline))
        }
        val version = PrivilegeManager.shizukuVersion
        if (version in 0 until MIN_API_VERSION) {
            return ShizukuHealth(
                ShizukuHealthState.VERSION_TOO_OLD,
                context.getString(R.string.shizuku_health_version_old, version, MIN_API_VERSION)
            )
        }
        if (!PrivilegeManager.shizukuPermissionGranted) {
            return if (PrivilegeManager.shizukuPermissionPermanentlyDenied) {
                ShizukuHealth(ShizukuHealthState.PERMISSION_DENIED_PERMANENTLY, context.getString(R.string.shizuku_health_denied_permanently))
            } else {
                ShizukuHealth(ShizukuHealthState.PERMISSION_DENIED, context.getString(R.string.shizuku_health_denied))
            }
        }
        if (!PrivilegeManager.shellServiceConnected) {
            return ShizukuHealth(ShizukuHealthState.SHELL_UNAVAILABLE, context.getString(R.string.shizuku_health_shell_unavailable))
        }
        return ShizukuHealth(ShizukuHealthState.OK, context.getString(R.string.shizuku_health_ok))
    }
}
