package com.omarea.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile collection for the Scene manager APK.
 *
 * Template adopted from DPIS (GPL-3.0, `docs/ATTRIBUTION.md`). The startup
 * journey launches the app from a clean home state; the captured profile is
 * merged into release builds so Scene's own classes get AOT-compiled on
 * install (the Miuix dependency already ships its own profile).
 *
 * Run on the connected device:
 *   ./gradlew :app:generateReleaseBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SceneBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() {
        val appId = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: "com.omarea.vtools"
        baselineProfileRule.collect(
            packageName = appId,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}
