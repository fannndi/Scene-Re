package com.omarea.engine

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Direct sysfs writes from the app process.
 *
 * Only usable when [SepolicyOptimizer] has applied the scoped SELinux rules
 * AND chmod-ed the nodes (0666); otherwise every call fails and the caller
 * falls back to the root shell.
 *
 * Device-verified quirk: with rules in the policy blob and 0666 perms, the
 * plain truncating open can still fail EACCES **without any avc audit line**
 * (not DAC, not SELinux) — an un-audited kernel-side block (APatch hook) or
 * a flag-specific restriction. So the write tries several open modes:
 *   1. truncating write (echo-style),
 *   2. append write (O_APPEND),
 *   3. read-write handle (O_RDWR) + seek/truncate.
 * [lastMode] reports which mode worked, which the capability probe surfaces.
 *
 * Responsibility: best-effort single-node writes.
 */
object DirectWrite {

    /** Last failure reason (diagnostics/UI); empty after a successful write. */
    @Volatile
    var lastError: String = ""
        private set

    /** Open mode that succeeded last ("truncate" / "append" / "rw"). */
    @Volatile
    var lastMode: String = ""
        private set

    /**
     * Best-effort single-node write: attempts the OPEN itself instead of
     * trusting [File.canWrite]. On this device access(W_OK) keeps returning
     * EACCES under ksu/APatch even when the open would succeed, which made
     * every direct write silently fall back to the root shell.
     */
    fun write(node: String, value: String): Boolean {
        val bytes = value.toByteArray()
        if (!File(node).exists()) {
            lastError = "missing"
            return false
        }
        var failure = "EACCES"
        // Mode 1: truncating write (what `echo > file` does).
        try {
            FileOutputStream(node).use { it.write(bytes) }
            lastError = ""
            lastMode = "truncate"
            return true
        } catch (ex: Exception) {
            failure = describe(ex)
        }
        // Mode 2: append (some hooks only intercept O_TRUNC paths).
        try {
            FileOutputStream(node, true).use { it.write(bytes) }
            lastError = ""
            lastMode = "append"
            return true
        } catch (ex: Exception) {
            failure = describe(ex)
        }
        // Mode 3: read-write handle, position 0 (O_RDWR, no O_TRUNC).
        try {
            RandomAccessFile(node, "rw").use {
                it.seek(0)
                it.write(bytes)
            }
            lastError = ""
            lastMode = "rw"
            return true
        } catch (ex: Exception) {
            failure = describe(ex)
        }
        lastError = failure
        return false
    }

    private fun describe(ex: Exception): String =
        ex.javaClass.simpleName + ": " + (ex.message ?: "?")

    /**
     * Safe end-to-end probe: rewrites the node's CURRENT value through
     * [write]. Returns "" when the direct path works, else the reason
     * (with the failure detail when every mode fails).
     */
    fun probe(node: String): String = try {
        val current = File(node).readText().trim()
        when {
            current.isEmpty() -> "empty node"
            write(node, current) -> ""
            else -> lastError
        }
    } catch (ex: Exception) {
        describe(ex)
    }
}
