package com.omarea.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structural diff between two tuning-JSON fragments (a user profile vs the
 * shipped preset). Drives the "Modified" badge and the changed-value markers
 * in the profile editor.
 *
 * Responsibility: pure recursive JSON comparison (JVM-testable).
 * Non-goals: reading files, applying tuning, knowing profile ids.
 * Invariants:
 *  - Key order never matters; numbers compare numerically (300000 == 300000.0).
 *  - A key present on only one side is reported as changed.
 */
object ProfileDiff {

    /** Flattened changed key paths, e.g. `cpu.policy0.max`, `gpu.throttling`. */
    fun changedPaths(user: JSONObject?, preset: JSONObject?): List<String> {
        if (user == null || preset == null) return emptyList()
        val out = ArrayList<String>()
        walk("", user, preset, out)
        return out
    }

    /** True when [user] exists and differs from [preset] in any way. */
    fun isModified(user: JSONObject?, preset: JSONObject?): Boolean {
        if (user == null || preset == null) return false
        return changedPaths(user, preset).isNotEmpty()
    }

    private fun walk(prefix: String, a: JSONObject, b: JSONObject, out: MutableList<String>) {
        val keys = LinkedHashSet<String>()
        a.keys().forEach { keys += it }
        b.keys().forEach { keys += it }
        for (key in keys) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val va: Any? = a.opt(key)
            val vb: Any? = b.opt(key)
            when {
                va == null || vb == null -> out += path
                va is JSONObject && vb is JSONObject -> walk(path, va, vb, out)
                va is JSONArray && vb is JSONArray -> if (!arrayEquals(va, vb)) out += path
                !valueEquals(va, vb) -> out += path
            }
        }
    }

    private fun valueEquals(a: Any, b: Any): Boolean {
        if (a == b) return true
        if (a is JSONObject && b is JSONObject) return changedPaths(a, b).isEmpty()
        if (a is JSONArray && b is JSONArray) return arrayEquals(a, b)
        val na = a.toString().toDoubleOrNull()
        val nb = b.toString().toDoubleOrNull()
        if (na != null && nb != null) return na == nb
        return a.toString() == b.toString()
    }

    private fun arrayEquals(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        for (i in 0 until a.length()) {
            val va = a.opt(i) ?: return false
            val vb = b.opt(i) ?: return false
            if (!valueEquals(va, vb)) return false
        }
        return true
    }
}
