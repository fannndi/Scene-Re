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

    fun write(node: String, value: String): Boolean = try {
        val file = File(node)
        if (!file.exists() || !file.canWrite()) return false
        file.writeText(value)
        true
    } catch (ex: Exception) {
        false
    }
}
