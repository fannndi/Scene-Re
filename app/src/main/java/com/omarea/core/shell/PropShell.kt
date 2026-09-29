package com.omarea.core.shell

/**
 * System property writes through resetprop.
 *
 * Read-only ("ro.") properties can only be changed via resetprop, and APatch
 * ships it at /data/adb/ap/bin/resetprop without putting it on PATH, so every
 * command resolves the binary at runtime.
 *
 * Responsibility: build resetprop command lines.
 * Non-goals: deciding *when* to set a property.
 */
object PropShell {
    /** Resolved at runtime: PATH first, then the APatch location. */
    private const val RESETPROP =
        "\$(command -v resetprop 2>/dev/null || echo /data/adb/ap/bin/resetprop)"

    fun set(name: String, value: String): String = "$RESETPROP $name '$value'"

    fun delete(name: String): String = "$RESETPROP --delete $name"
}
