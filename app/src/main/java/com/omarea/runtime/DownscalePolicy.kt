package com.omarea.runtime

/**
 * Resolution-downscale decision table (pure, JVM-tested).
 *
 * AZenith-derived (docs/ATTRIBUTION.md): per-app resolution downscale through
 * the platform Game Mode API (`cmd game downscale`). The setting is persisted
 * per app; it is applied on app switch only while the engine could act, and
 * reset on engine OFF / TRUE OFF / uninstall (it is a persistent system
 * setting, so leaving it behind would violate the hygiene rules).
 */
object DownscalePolicy {

    fun shouldApply(
        hasRatio: Boolean,
        engineOff: Boolean,
        trueOff: Boolean,
        rootAvailable: Boolean
    ): Boolean = hasRatio && !engineOff && !trueOff && rootAvailable
}
