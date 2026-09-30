package com.omarea.engine

import android.content.Context
import java.io.File

/**
 * Direct sysfs writes from the app process.
 *
 * Only usable when [SepolicyOptimizer] has applied the scoped SELinux rules
 * AND chmod-ed the nodes (0666); otherwise every call returns false and the
 * caller falls back to the root shell.
 *
 * Responsibility: best-effort single-node writes.
 */
object DirectWrite {

    /** Last failure reason (diagnostics/UI); empty after a successful write. */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * Best-effort single-node write: attempts the OPEN itself instead of
     * trusting [File.canWrite]. On this device access(W_OK) keeps returning
     * EACCES under ksu/APatch even when the open would succeed, which made
     * every direct write silently fall back to the root shell.
     */
    fun write(node: String, value: String): Boolean = try {
        val file = File(node)
        if (!file.exists()) {
            lastError = "missing"
            false
        } else {
            file.writeText(value)
            lastError = ""
            true
        }
    } catch (ex: Exception) {
        lastError = ex.javaClass.simpleName + ": " + (ex.message ?: "?")
        false
    }

    /**
     * Safe end-to-end probe: rewrites the node's CURRENT value through
     * [write]. Returns "" when the direct path works, else the reason.
     */
    fun probe(node: String): String = try {
        val current = File(node).readText().trim()
        when {
            current.isEmpty() -> "empty node"
            write(node, current) -> ""
            else -> lastError
        }
    } catch (ex: Exception) {
        ex.javaClass.simpleName + ": " + (ex.message ?: "?")
    }
}
