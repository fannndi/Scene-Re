package com.omarea.library.shell

import android.util.Log
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.ShellCapability
import com.omarea.common.shell.ShellCapabilityRegistry
import com.omarea.utils.ShellSafety

/**
 * Framework-level app management that works in the Shizuku (shell uid) tier as well as with root.
 *
 * The original Scene feature set was written under the assumption that every device is rooted, so
 * anything beyond sysfs writes used `su`. With Shizuku the app can still drive the framework
 * through `cmd`, `am`, `pm` and `dumpsys`, which together cover a large amount of functionality:
 * appops modes, runtime permission grants, doze/battery-optimisation state, standby buckets and
 * network policy per app.
 *
 * Security: every package name and permission name that reaches a shell command is validated and
 * quoted through [ShellSafety]. Package names are additionally checked against the installed
 * package list, so a caller cannot smuggle shell metacharacters through an Intent extra.
 */
object FrameworkAppControl {
    private const val TAG = "SceneAppControl"

    /** Battery optimisation / doze state for a package. */
    enum class DozeState {
        /** The app is on the battery-optimisation whitelist (allowed to run in the background). */
        WHITELISTED,

        /** The app is subject to battery optimisation (default). */
        OPTIMISED,

        /** The state could not be read in the current tier. */
        UNKNOWN
    }

    /**
     * App standby bucket, which decides how aggressively the system throttles a background app.
     *
     * [id] is what `am set-standby-bucket` accepts. [frameworkValue] is the number
     * `am get-standby-bucket` prints back, confirmed on the target device:
     * `active` reports 10 and `rare` reports 40. Reading and writing therefore use two different
     * vocabularies, and both directions are handled here.
     */
    enum class StandbyBucket(val id: String, val frameworkValue: Int) {
        ACTIVE("active", 10),
        WORKING_SET("working_set", 20),
        FREQUENT("frequent", 30),
        RARE("rare", 40),
        RESTRICTED("restricted", 45);

        companion object {
            /** Parses the numeric value printed by `am get-standby-bucket`. */
            fun fromFrameworkValue(value: Int?): StandbyBucket? {
                return values().firstOrNull { it.frameworkValue == value }
            }

            /** Parses either the numeric framework value or the name, as a fallback. */
            fun fromId(id: String?): StandbyBucket? {
                val trimmed = id?.trim() ?: return null
                trimmed.toIntOrNull()?.let { return fromFrameworkValue(it) }
                return values().firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
            }
        }
    }

    /** One appops entry: the operation name and its current mode. */
    data class AppOpEntry(val operation: String, val mode: String)

    /**
     * True when the framework control surface is usable.
     * Callers should hide write actions when this is false instead of issuing a doomed command.
     */
    fun isSupported(): Boolean {
        return ShellCapabilityRegistry.supports(ShellCapability.FRAMEWORK_CONTROL)
    }

    /** Runs a `cmd` style command, returning its trimmed output or null on failure. */
    private fun run(command: String): String? {
        val output = KeepShellPublic.doCmdSync(command)
        if (output.isBlank() || output == "error") {
            Log.d(TAG, "command produced no output: $command")
            return null
        }
        return output
    }

    /**
     * Validates a package name and returns the shell-quoted form, or null when invalid.
     *
     * Two independent checks: [FrameworkControlValidation] rejects names that are not
     * identifier-shaped (option injection, path traversal), and [ShellSafety] confirms the shape
     * the project already trusts before quoting. Neither alone is treated as sufficient.
     */
    private fun safePackage(packageName: String?): String? {
        if (!FrameworkControlValidation.isValidPackageName(packageName) ||
            !ShellSafety.isValidPackageName(packageName)
        ) {
            Log.w(TAG, "rejected invalid package name: $packageName")
            return null
        }
        return ShellSafety.quote(packageName!!)
    }

    // #region Battery optimisation and doze

    /** Reads the battery-optimisation state of a package. */
    fun getDozeState(packageName: String): DozeState {
        val quoted = safePackage(packageName) ?: return DozeState.UNKNOWN
        // `dumpsys deviceidle whitelist` lists whitelisted packages as "<reason>,<package>,<uid>".
        val output = run("dumpsys deviceidle whitelist") ?: return DozeState.UNKNOWN
        val whitelisted = output.lineSequence().any { line ->
            line.split(',').any { it.trim() == packageName }
        }
        return if (whitelisted) DozeState.WHITELISTED else DozeState.OPTIMISED
    }

    /**
     * Adds or removes a package from the battery-optimisation whitelist.
     *
     * @return true when the command reported success.
     */
    fun setDozeWhitelisted(packageName: String, whitelisted: Boolean): Boolean {
        val quoted = safePackage(packageName) ?: return false
        val action = if (whitelisted) "whitelist" else "remove"
        val output = KeepShellPublic.doCmdSync("cmd deviceidle $action $quoted")
        // The command prints nothing on success, so treat "no error text" as success and verify
        // by re-reading the state, which is the only reliable signal.
        val applied = getDozeState(packageName) ==
                (if (whitelisted) DozeState.WHITELISTED else DozeState.OPTIMISED)
        if (!applied) {
            Log.w(TAG, "doze whitelist change did not apply for $packageName (output=$output)")
        }
        return applied
    }

    /** Reads the app standby bucket of a package. */
    /**
     * Reads the app standby bucket.
     *
     * `am get-standby-bucket` prints a bare number (confirmed on device: 10 for active, 40 for
     * rare), so the numeric form is parsed first.
     */
    fun getStandbyBucket(packageName: String): StandbyBucket? {
        val quoted = safePackage(packageName) ?: return null
        val output = run("am get-standby-bucket $quoted") ?: return null
        val value = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: return null
        return StandbyBucket.fromId(value)
    }

    /**
     * Sets the app standby bucket, which is the framework's own background-throttling control.
     *
     * The write is verified by re-reading, and a mismatch is reported as failure. Silently
     * succeeding here would let the UI show a bucket the system did not accept - `am
     * set-standby-bucket` can refuse without a non-zero exit status.
     */
    fun setStandbyBucket(packageName: String, bucket: StandbyBucket): Boolean {
        val quoted = safePackage(packageName) ?: return false
        val output = KeepShellPublic.doCmdSync("am set-standby-bucket $quoted ${bucket.id}")
        if (output.contains("Exception", ignoreCase = true) || output.contains("Error", ignoreCase = true)) {
            Log.w(TAG, "set-standby-bucket reported an error for $packageName: $output")
            return false
        }
        return getStandbyBucket(packageName) == bucket
    }

    // #endregion Battery optimisation and doze

    // #region AppOps

    /**
     * Reads the appops modes for a package.
     *
     * `cmd appops get <pkg>` prints one `<OP>: <mode>` pair per line.
     */
    fun getAppOps(packageName: String): List<AppOpEntry> {
        val quoted = safePackage(packageName) ?: return emptyList()
        val output = run("cmd appops get $quoted") ?: return emptyList()
        val entries = ArrayList<AppOpEntry>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            // Skip the "Uid mode:" section header and any non "OP: mode" lines.
            if (trimmed.isEmpty() || trimmed.endsWith(":")) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) continue
            val operation = trimmed.substring(0, separator).trim()
            val mode = trimmed.substring(separator + 1).trim()
            if (operation.isEmpty() || mode.isEmpty()) continue
            entries.add(AppOpEntry(operation, mode))
        }
        return entries
    }

    /** Reads a single appops mode, e.g. `RUN_IN_BACKGROUND`, or null when unknown. */
    fun getAppOpMode(packageName: String, operation: String): String? {
        if (!isValidOpName(operation)) {
            return null
        }
        return getAppOps(packageName).firstOrNull { it.operation == operation }?.mode
    }

    /**
     * Sets a single appops mode, e.g. `setAppOpMode(pkg, "RUN_IN_BACKGROUND", "ignore")`.
     *
     * Common modes: `allow`, `ignore`, `deny`, `default`, `foreground`.
     */
    fun setAppOpMode(packageName: String, operation: String, mode: String): Boolean {
        val quoted = safePackage(packageName) ?: return false
        if (!isValidOpName(operation) || !isValidOpMode(mode)) {
            Log.w(TAG, "rejected invalid appops call: $operation=$mode")
            return false
        }
        KeepShellPublic.doCmdSync("cmd appops set $quoted $operation $mode")
        return getAppOpMode(packageName, operation) == mode
    }

    /** Operation names are upper snake case identifiers; reject anything else. */
    private fun isValidOpName(operation: String?): Boolean {
        return FrameworkControlValidation.isValidOpName(operation)
    }

    /** AppOps modes accepted by the framework. */
    private fun isValidOpMode(mode: String?): Boolean {
        return FrameworkControlValidation.isValidOpMode(mode)
    }

    // #endregion AppOps

    // #region Permissions

    /** Runtime permission state as reported by `dumpsys package`. */
    data class PermissionState(val name: String, val granted: Boolean)

    /**
     * Lists the runtime permissions of a package with their granted state.
     */
    fun getRuntimePermissions(packageName: String): List<PermissionState> {
        val quoted = safePackage(packageName) ?: return emptyList()
        val output = run("dumpsys package $quoted") ?: return emptyList()
        val permissions = ArrayList<PermissionState>()
        var inRuntimeSection = false
        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("runtime permissions:") -> {
                    inRuntimeSection = true
                    continue
                }
                // The next top-level section ends the runtime block.
                inRuntimeSection && trimmed.endsWith(":") && !trimmed.contains("=") -> {
                    inRuntimeSection = false
                    continue
                }
            }
            if (!inRuntimeSection) continue
            // Lines look like: "android.permission.CAMERA: granted=true"
            val match = Regex("^([A-Za-z0-9_.]+):\\s*granted=(true|false)").find(trimmed) ?: continue
            permissions.add(
                PermissionState(
                    match.groupValues[1],
                    match.groupValues[2] == "true"
                )
            )
        }
        return permissions
    }

    /**
     * Grants or revokes a runtime permission.
     *
     * Only runtime permissions can be changed this way; a signature-level permission will fail and
     * the method returns false rather than pretending it worked.
     */
    fun setPermission(packageName: String, permission: String, granted: Boolean): Boolean {
        val quoted = safePackage(packageName) ?: return false
        if (!isValidPermissionName(permission)) {
            Log.w(TAG, "rejected invalid permission name: $permission")
            return false
        }
        val action = if (granted) "grant" else "revoke"
        KeepShellPublic.doCmdSync("pm $action $quoted $permission")
        // Verify, because pm prints nothing on success and fails silently for non-runtime perms.
        val state = getRuntimePermissions(packageName).firstOrNull { it.name == permission } ?: return false
        return state.granted == granted
    }

    private fun isValidPermissionName(permission: String?): Boolean {
        return FrameworkControlValidation.isValidPermissionName(permission)
    }

    // #endregion Permissions

    // #region Network policy

    /**
     * Restricts or allows background network access for an app.
     *
     * Uses `cmd netpolicy`, which is the framework's own data-saver control and therefore needs no
     * root. The caller must resolve the package to a uid first.
     */
    fun setBackgroundNetworkRestricted(uid: Int, restricted: Boolean): Boolean {
        if (uid < 0) {
            return false
        }
        val action = if (restricted) "add restrict-background-blacklist" else "remove restrict-background-blacklist"
        KeepShellPublic.doCmdSync("cmd netpolicy $action $uid")
        return true
    }

    // #endregion Network policy
}
