package com.omarea.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.omarea.common.shell.KeepShellPublic
import com.omarea.vtools.R

/**
 * "Modify system settings" (WRITE_SETTINGS).
 *
 * This is an AppOp, not a runtime permission, so it cannot be granted by `pm grant` the way a
 * normal permission can. The only supported paths are:
 *
 *  - the user flipping the switch on Settings.ACTION_MANAGE_WRITE_SETTINGS, or
 *  - a shell with `appops set <pkg> WRITE_SETTINGS allow`, which root and Shizuku both have.
 *
 * The previous implementation sent the user to the generic application-details screen, where no
 * such switch exists, so the permission could never actually be granted from inside the app.
 */
class WriteSettings {
    companion object {
        private const val TAG = "SceneWriteSettings"
    }

    fun checkPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.System.canWrite(context)
            } else {
                true
            }
        } catch (ex: Exception) {
            false
        }
    }

    /** Grants WRITE_SETTINGS through the shell backend (root or Shizuku). */
    fun setPermissionByRoot(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Before M this is a normal install-time permission.
            KeepShellPublic.doCmdSync("pm grant ${context.packageName} android.permission.WRITE_SETTINGS")
            return true
        }

        KeepShellPublic.doCmdSync("appops set ${context.packageName} WRITE_SETTINGS allow")
        val state = KeepShellPublic.doCmdSync("appops get ${context.packageName} WRITE_SETTINGS")
        val granted = state.contains("allow", ignoreCase = true)
        if (!granted) {
            Log.w(TAG, "appops set WRITE_SETTINGS did not take effect, output=$state")
        }
        return granted
    }

    /**
     * Opens the screen that actually contains the "Modify system settings" switch.
     *
     * ACTION_MANAGE_WRITE_SETTINGS is the only intent whose target shows that toggle. It is not
     * present on every OEM ROM, so fall back to the app details screen and, failing that, tell the
     * user where to look instead of opening nothing.
     */
    fun requestPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            KeepShellPublic.doCmdSync("pm grant ${context.packageName} android.permission.WRITE_SETTINGS")
            return true
        }

        Toast.makeText(
            context,
            context.getString(R.string.write_settings_request_hint),
            Toast.LENGTH_LONG
        ).show()

        return try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.fromParts("package", context.packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.w(TAG, "ACTION_MANAGE_WRITE_SETTINGS unavailable: ${ex.message}")
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                fallback.data = Uri.fromParts("package", context.packageName, null)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                true
            } catch (ex2: Exception) {
                Log.w(TAG, "app details screen unavailable: ${ex2.message}")
                false
            }
        }
    }
}
