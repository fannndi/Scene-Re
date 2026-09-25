package com.omarea.utils

import android.app.AlarmManager
import android.content.Context
import android.os.Build

/**
 * The Android version support hierarchy.
 *
 * Scene installs from Android 10 (API 29) to Android 13 (API 33, the target).
 * On the surya family the official Xiaomi builds (MIUI 12 / 12.5 / 13 / 14)
 * cover Android 10, 11 and 12 — **Android 12 (MIUI 14) is the primary target** —
 * while Android 13 exists only as AOSP-based community ROMs (there is no MIUI
 * 13/14 build on Android 13 for these devices). Everything in the kernel/root
 * layer — the powercfg profiles, the options layer, the per-game profiles and
 * the app_process monitor — works on every supported version. Only the platform
 * APIs below are version-gated, and each one degrades gracefully instead of
 * failing:
 *
 *   API 29  Android 10   install floor (MIUI 12)
 *   API 30  Android 11   package visibility filtering (QUERY_ALL_PACKAGES),
 *                        scoped storage (root / MANAGE_EXTERNAL_STORAGE)
 *   API 31  Android 12   Game Mode API, PendingIntent immutability, exact
 *                        alarms become permission-gated (MIUI 13/14, primary)
 *   API 32  Android 12L  -
 *   API 33  Android 13   Game Mode overlay controls (downscale / FPS),
 *                        notification runtime permission (AOSP ROMs only)
 *
 * The report is rendered in the Kernel features dialog and in Diagnostics.
 */
object PlatformCapabilities {
    const val MIN_API = 29
    const val TARGET_API = 33

    val api: Int get() = Build.VERSION.SDK_INT

    /** `cmd game mode` (Android 12+). */
    val gameMode: Boolean get() = api >= Build.VERSION_CODES.S

    /** `cmd game set --downscale/--fps` (Android 13+). */
    val gameModeOverlay: Boolean get() = api >= Build.VERSION_CODES.TIRAMISU

    /** Notification runtime permission (Android 13+). */
    val notificationPermission: Boolean get() = api >= Build.VERSION_CODES.TIRAMISU

    /** Android 11+ filters package visibility unless QUERY_ALL_PACKAGES is held. */
    val packageVisibilityFiltering: Boolean get() = api >= Build.VERSION_CODES.R

    /** Android 11+ scoped storage; Scene uses MANAGE_EXTERNAL_STORAGE or root. */
    val scopedStorage: Boolean get() = api >= Build.VERSION_CODES.R

    /**
     * Exact alarms are permission-gated on Android 12+: without
     * SCHEDULE_EXACT_ALARM (or the user's "Alarms & reminders" grant) the
     * platform throws, so callers fall back to an inexact alarm.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (api < Build.VERSION_CODES.S) {
            return true
        }
        return try {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.canScheduleExactAlarms()
        } catch (ex: Exception) {
            false
        }
    }

    fun versionName(): String = when {
        api >= Build.VERSION_CODES.TIRAMISU -> "Android 13+"
        api == Build.VERSION_CODES.S_V2 -> "Android 12L"
        api == Build.VERSION_CODES.S -> "Android 12"
        api == Build.VERSION_CODES.R -> "Android 11"
        api == Build.VERSION_CODES.Q -> "Android 10"
        else -> "Android < 10"
    }

    /** The hierarchy as a text report for the Kernel features dialog / Diagnostics. */
    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.append("Android version support\n")
        sb.append("  running: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(api).append(", ").append(versionName()).append(")\n")
        sb.append("  supported: Android 10..13 (API ").append(MIN_API)
            .append("..").append(TARGET_API).append("+, target ").append(TARGET_API).append(")\n")
        sb.append("  official MIUI builds end at Android 12 (MIUI 14); Android 13 is AOSP community only\n")
        sb.append("\n")
        sb.append("Always available (kernel/root layer)\n")
        sb.append("  powercfg profiles, options layer, per-game profiles, fallback monitor\n")
        sb.append("\n")
        sb.append("Version-gated platform APIs\n")
        sb.append("  ").append(mark(gameMode))
            .append(" game mode API (cmd game mode) - Android 12+ (MIUI 13/14 included)\n")
        sb.append("  ").append(mark(gameModeOverlay))
            .append(" game overlay controls (downscale/FPS) - Android 13+ AOSP only\n")
        sb.append("  ").append(mark(canScheduleExactAlarms(context)))
            .append(" exact alarms - Android 12+ permission, falls back to inexact\n")
        sb.append("  ").append(mark(notificationPermission))
            .append(" notification runtime permission - Android 13+, requested on first start\n")
        sb.append("  ").append(mark(packageVisibilityFiltering))
            .append(" package visibility filtering - Android 11+, QUERY_ALL_PACKAGES declared\n")
        sb.append("  ").append(mark(scopedStorage))
            .append(" scoped storage - Android 11+, root / MANAGE_EXTERNAL_STORAGE paths\n")
        return sb.toString()
    }

    private fun mark(enabled: Boolean) = if (enabled) "[on] " else "[off]"
}
