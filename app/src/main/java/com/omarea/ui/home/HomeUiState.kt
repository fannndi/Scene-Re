package com.omarea.ui.home

/**
 * Immutable snapshot of everything the Home screen renders.
 *
 * Contract between the data collector (FragmentHome) and the Compose UI
 * (HomeScreen). Keep it flat and display-ready: no Android types.
 */
data class HomeUiState(
    val ramInfoText: String = "--",
    val zramInfoText: String = "--",
    val swapCached: String = "--",
    val dirty: String = "--",
    val runningTime: String = "--",
    val batteryNow: String = "--",
    val batteryCapacity: String = "--",
    val batteryTemperature: String = "--",
    val gpuFreq: String = "--",
    val gpuLoadText: String = "--",
    val gpuGovernorText: String = "",
    val gpuFreqRangeText: String = "",
    val gpuInfoText: String = "",
    val cpuPlatform: String = "",
    val cpuTemperatureText: String = "--",
    val cpuTotalLoad: String = "--",
    val deviceName: String = "",
    val modeName: String = "--",
    val coresOnline: String = "--",
    val gpuFreqShort: String = "--",
    val thermalText: String = "--",
    val cluster0Text: String = "--",
    val cluster6Text: String = "--",
    val gpuDetailText: String = "--",
    val gpuLoadPercent: Int = 0,
    val cpuLoadPercent: Int = 0,
    val ramUsedPercent: Int = 0,
    val socText: String = "",
    val cpuArchText: String = "",
    val trueOff: Boolean = false,
    /** No root (MISSING/DENIED) — Monitor mode banner. */
    val rootMissing: Boolean = false,
    /** Root returned after a no-root boot; offer one-tap engine restore. */
    val engineRestorePending: Boolean = false,
    /** First-run checklist (a11y service currently connected). */
    val setupA11yDone: Boolean = false,
    /** First-run checklist (engine on and not TRUE OFF). */
    val setupEngineOn: Boolean = false,
    /** Active `msm_performance` boosts/caps ("Boost 1804 MHz · Cap 1497 MHz"). */
    val perfBoostText: String = "",
    /** Engine ON but this boot got no apply (MIUI autostart blocked it). */
    val bootApplyWarning: Boolean = false,
)
