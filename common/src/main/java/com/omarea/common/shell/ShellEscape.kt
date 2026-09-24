package com.omarea.common.shell

/**
 * Escaping helpers for building shell command strings.
 *
 * The project builds most of its shell commands by string interpolation, which is
 * fragile: any value containing a quote, a space or a newline can terminate the
 * intended argument and start a new command. Paths and property values routinely come
 * from config files, from the kr-script engine, or from user input, so this is not
 * merely a theoretical concern.
 *
 * Use these instead of interpolating values directly into a command.
 */
object ShellEscape {

    /**
     * Wraps [value] in single quotes, which is the only quoting form in POSIX shells
     * that treats *every* character literally (no escape sequences are interpreted).
     * An embedded single quote is emitted as `'\''` to close the quote, emit a literal
     * quote, and reopen it.
     *
     * `it's here` becomes `'it'\''s here'`.
     */
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Quotes [value] for interpolation as a shell *argument*, i.e. no unquoted
     * expansion is performed on the result.
     *
     * Returns `''` for an empty string rather than a bare empty token, so the argument
     * is still present and does not shift the positions of the arguments after it.
     */
    fun arg(value: String): String = quote(value)

    /**
     * Rejects values that must never reach a shell command as a path.
     *
     * A path containing a newline can inject a second command even when quoted via
     * [quote], because the quoting only protects against the *shell* misreading the
     * value — it does not stop the value from being a different path than intended.
     * Paths in this project are always kernel nodes under /sys, /proc or a config file
     * written by the app, so anything outside that shape is a bug or an attack.
     *
     * @return true when the value is safe to use as a shell path
     */
    fun isSafePath(value: String): Boolean {
        if (value.isEmpty() || value.length > 4096) return false
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        // Kernel property / sysfs node: /sys/..., /proc/... or persist.* / ro.* style keys
        if (!value.startsWith("/")) {
            return value.matches(Regex("^[A-Za-z0-9_.\\-]+\$"))
        }
        return value.matches(Regex("^[A-Za-z0-9_./\\-]+\$"))
    }

    /**
     * Strips characters that cannot legitimately appear in a numeric property value
     * (frequency, governor switch, etc). Returns null when nothing usable remains.
     */
    fun sanitizeNumeric(value: String): String? {
        val cleaned = value.filter { it.isDigit() || it == '-' || it == '.' }
        return cleaned.ifEmpty { null }
    }

    /**
     * Builds a command from a program name and literal arguments.
     *
     * Every argument after the first is passed through [quote], so package names,
     * paths and arbitrary user strings cannot terminate an argument or spawn a second
     * command. The program name itself is NOT quoted — it is expected to be a literal
     * from the source, never a runtime value.
     *
     *     ShellEscape.cmd("cat", "/sys/kernel/fpsgo/fstb/fpsgo_status")
     *     // -> cat '/sys/kernel/fpsgo/fstb/fpsgo_status'
     *
     * **Limitation:** quoting protects against the *shell* splitting an argument, but a
     * single-quoted string may still legally contain a newline. A value with an embedded
     * `\n` therefore survives as one argument, and whether that is safe depends on the
     * command: an argument position (as used here) is fine, but a position that the
     * program itself interprets can still be abused. Where a value must be a single
     * line — identifiers, package names, property keys — use [cmdLine] instead.
     */
    fun cmd(program: String, vararg args: String): String {
        if (args.isEmpty()) return program
        return args.joinToString(" ", prefix = "$program ") { quote(it) }
    }

    /**
     * Like [cmd], but each argument must additionally be free of control characters.
     *
     * Use this for values that are meant to be a single identifier — package names,
     * property keys, file names. A newline in such a value is never legitimate, so
     * rather than trying to escape it the argument is rejected and substituted with an
     * inert placeholder, which makes the failure visible in the command instead of
     * silently becoming a second line.
     *
     * This is the right choice for anything that reaches `pm` / `am` / `getprop`, where
     * the argument is re-parsed by the platform rather than treated as an opaque string.
     *
     *     ShellEscape.cmdLine("pm", "suspend", "com.foo.bar")
     *     // -> pm 'suspend' 'com.foo.bar'
     */
    fun cmdLine(program: String, vararg args: String): String {
        if (args.isEmpty()) return program
        return args.joinToString(" ", prefix = "$program ") { value ->
            if (isSingleLine(value)) quote(value) else "''"
        }
    }

    /**
     * True when [value] contains no character that would terminate the line.
     *
     * Deliberately excludes the whole C0 control range plus DEL: these have no
     * legitimate use in an identifier, path or property value, and several of them
     * (`\n`, `\r`, `\u0000`) are interpreted by shells and by Android's own command
     * parsers as separators.
     */
    fun isSingleLine(value: String): Boolean {
        return value.none { it.code < 0x20 || it.code == 0x7F }
    }
}
