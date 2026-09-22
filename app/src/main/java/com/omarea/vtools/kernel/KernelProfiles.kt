package com.omarea.vtools.kernel

import android.content.Context
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.KeepShellPublic
import com.omarea.utils.ShellSafety

/**
 * The three scheduling profiles shipped in `assets/kernel-profiles`.
 *
 * The script is copied into the app's private files directory (with DOS line endings normalised)
 * and executed through the active shell backend, so applying a profile works with root and fails
 * cleanly everywhere else. The selection is persisted in SharedPreferences, which is enough to show
 * which profile the user last applied.
 */
enum class KernelProfile(val assetName: String, val storedValue: Int) {
    POWERSAVE("kernel-profiles/powersave.sh", 0),
    BALANCE("kernel-profiles/balance.sh", 1),
    PERFORMANCE("kernel-profiles/performance.sh", 2);

    companion object {
        fun fromStored(value: Int?): KernelProfile {
            return values().firstOrNull { it.storedValue == value } ?: BALANCE
        }
    }
}

object KernelProfiles {
    private const val PREFERENCES = "kernel_manager"
    private const val KEY_CURRENT_PROFILE = "current_profile"

    fun current(context: Context): KernelProfile {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return KernelProfile.fromStored(preferences.getInt(KEY_CURRENT_PROFILE, KernelProfile.BALANCE.storedValue))
    }

    /**
     * Copies the asset script to private storage and runs it.
     *
     * The bundled scripts log to `/sdcard/RvKernel-Manager/kernel-profile`, so that directory is
     * created first; otherwise `tee` fails and the script exits non-zero even when every kernel
     * write succeeded.
     *
     * Returns true only when the shell reports exit code 0; a profile whose writes are all denied
     * still exits 0, but every control that reaches the kernel is root-gated in the UI anyway.
     */
    fun apply(context: Context, profile: KernelProfile): Boolean {
        val outName = "kernel-profiles/" + profile.assetName.substringAfterLast('/')
        val scriptPath = FileWrite.writePrivateShellFile(profile.assetName, outName, context)
            ?: return false
        val output = try {
            KeepShellPublic.doCmdSync(
                "mkdir -p /sdcard/RvKernel-Manager/kernel-profile 2>/dev/null; " +
                        "sh ${ShellSafety.quote(scriptPath)}; echo \"KP_EXIT=\$?\""
            )
        } catch (ex: Exception) {
            return false
        }
        if (output == "error") {
            return false
        }
        val exitCode = output.lineSequence()
            .firstOrNull { it.startsWith("KP_EXIT=") }
            ?.removePrefix("KP_EXIT=")
            ?.trim()
        if (exitCode != "0") {
            return false
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CURRENT_PROFILE, profile.storedValue)
            .apply()
        return true
    }
}
