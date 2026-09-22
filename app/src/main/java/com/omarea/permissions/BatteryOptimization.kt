package com.omarea.permissions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.omarea.common.shell.KeepShellPublic

/**
 * Battery-optimisation exemption, i.e. "allow this app to run in the background".
 *
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **not** a runtime permission. It can never be granted
 * through `ActivityCompat.requestPermissions`, which is where the splash screen asked for it, so
 * the request silently did nothing. The only other path was `dumpsys deviceidle whitelist +pkg`,
 * which needs a privileged shell - so on a device without root or a live Shizuku server the app was
 * never exempt at all.
 *
 * The consequence is the accessibility service dying: MIUI aggressively kills the background
 * process of a non-exempt app, and the accessibility service goes down with it. The user sees the
 * service turn itself off for no apparent reason.
 *
 * This mirrors [WriteSettings]: try the shell backend first, and fall back to the system screen
 * that actually carries the switch.
 */
class BatteryOptimization {
    companion object {
        private const val TAG = "SceneBatteryOpt"
    }

    /** Whether the system already lets this app run unrestricted in the background. */
    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (ex: Exception) {
            Log.w(TAG, "isIgnoringBatteryOptimizations failed: ${ex.message}")
            false
        }
    }

    /**
     * Grants the exemption through the shell backend (root or Shizuku).
     *
     * @return true only when the exemption is confirmed to have taken effect.
     */
    fun grantByShell(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        KeepShellPublic.doCmdSync("dumpsys deviceidle whitelist +${context.packageName}")
        val granted = isExempt(context)
        if (!granted) {
            Log.w(TAG, "deviceidle whitelist +${context.packageName} did not take effect")
        }
        return granted
    }

    /**
     * Opens the system dialog that asks the user to let the app run in the background. This is the
     * only way to grant the exemption without a privileged shell.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.fromParts("package", context.packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (ex: Exception) {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS unavailable: ${ex.message}")
            // Older ROMs and some OEM builds do not carry the per-package dialog; the list screen
            // is the next best thing.
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
                true
            } catch (ex2: Exception) {
                Log.w(TAG, "battery optimization settings unavailable: ${ex2.message}")
                false
            }
        }
    }
}
