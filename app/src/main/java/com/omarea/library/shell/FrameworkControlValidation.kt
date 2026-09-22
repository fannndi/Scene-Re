package com.omarea.library.shell

/**
 * Input validation for framework control commands.
 *
 * This app runs shell commands as root or as shell uid, so every value that reaches a command is
 * an injection risk. The checks live here, separate from [FrameworkAppControl], so they can be
 * unit tested exhaustively without a device - a validator that is only exercised on a phone is a
 * validator nobody has actually tested with hostile input.
 *
 * Package names still go through [com.omarea.utils.ShellSafety.quote] before use; these methods
 * only decide whether a value is acceptable at all.
 */
object FrameworkControlValidation {

    private val PACKAGE_SEGMENT_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    private val OP_NAME_REGEX = Regex("^[A-Z][A-Z0-9_]{2,63}$")
    private val PERMISSION_REGEX = Regex("^[A-Za-z0-9_.]{3,120}$")

    private val VALID_OP_MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    /**
     * AppOps operation names are upper snake case, e.g. `RUN_IN_BACKGROUND`.
     * Anything containing shell metacharacters, spaces or lowercase is rejected.
     */
    fun isValidOpName(operation: String?): Boolean {
        return operation != null && OP_NAME_REGEX.matches(operation)
    }

    /** Only the modes the framework actually accepts for an appops entry. */
    fun isValidOpMode(mode: String?): Boolean {
        return mode != null && mode in VALID_OP_MODES
    }

    /**
     * Permission names must be in the `android.permission.` namespace and contain only
     * identifier characters. This prevents passing e.g. `--user` or `; rm -rf` as a "permission".
     *
     * The bare prefix with no permission after it is rejected: `pm grant <pkg> android.permission.`
     * is not a permission, and accepting it would push garbage into the command.
     */
    fun isValidPermissionName(permission: String?): Boolean {
        val prefix = "android.permission."
        if (permission == null || !permission.startsWith(prefix)) return false
        val suffix = permission.substring(prefix.length)
        if (suffix.length < 2 || suffix.length > 100) return false
        return PERMISSION_REGEX.matches(permission)
    }

    /**
     * Package names are checked segment by segment. Android requires at least two segments and
     * each one must start with a letter, so this rejects the shapes that could be mistaken for a
     * command option or a path (`--user`, `../../data`, `a b`) as well as anything with a shell
     * metacharacter in it.
     */
    fun isValidPackageName(packageName: String?): Boolean {
        if (packageName == null || packageName.length > 200) return false
        val segments = packageName.split('.')
        if (segments.size < 2) return false
        return segments.all { it.isNotEmpty() && PACKAGE_SEGMENT_REGEX.matches(it) }
    }

    /** A uid must be a non-negative integer that fits in an Int. */
    fun isValidUid(uid: Int): Boolean {
        return uid >= 0
    }

    /**
     * Validates a value used in a `--user` argument. Only the current user or numeric ids are
     * accepted; anything else could be an option injection.
     */
    fun isValidUserId(userId: String?): Boolean {
        return userId != null && userId.matches(Regex("^(current|all|[0-9]{1,4})$"))
    }
}
