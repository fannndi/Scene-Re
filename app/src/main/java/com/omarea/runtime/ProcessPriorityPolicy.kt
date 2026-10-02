package com.omarea.runtime

/**
 * Game process-priority decision table (pure, JVM-tested).
 *
 * AZenith-derived (docs/ATTRIBUTION.md): the foreground app that owns a
 * per-app mode gets `renice -20` + realtime I/O priority. The boost is
 * per-process only (dies with the process), so no journal/restore is needed.
 *
 * Responsibility: decide, nothing else.
 * Non-goals: shell work ([ProcessPriority]).
 */
object ProcessPriorityPolicy {

    fun shouldBoost(
        enabled: Boolean,
        appModeActive: Boolean,
        engineOff: Boolean,
        trueOff: Boolean,
        rootAvailable: Boolean
    ): Boolean = enabled && appModeActive && !engineOff && !trueOff && rootAvailable
}
