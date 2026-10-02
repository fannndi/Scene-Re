package com.omarea.runtime

/**
 * Game library preload decision table (pure, JVM-tested).
 *
 * AZenith-derived (docs/ATTRIBUTION.md): when a game comes to the foreground,
 * read its native libraries once so the page cache is warm before the engine
 * starts loading them. Transient by nature — nothing to restore.
 */
object GamePreloadPolicy {

    fun shouldPreload(
        enabled: Boolean,
        appModeActive: Boolean,
        engineOff: Boolean,
        trueOff: Boolean,
        rootAvailable: Boolean
    ): Boolean = enabled && appModeActive && !engineOff && !trueOff && rootAvailable
}
