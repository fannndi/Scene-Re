package com.omarea.runtime

import com.omarea.util.CheckRootStatus
import com.omarea.util.RootState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the no-root (Monitor mode) and uninstall safety net.
 *
 * Pure logic only: state classification, engine auto-off policy, the journal
 * restore script and the guard module's boot script.
 */
class NoRootSafetyTest {

    @Test
    fun `root state distinguishes missing su from a denial`() {
        assertEquals(RootState.AVAILABLE, CheckRootStatus.classify(ok = true, suMissing = false))
        assertEquals(RootState.MISSING, CheckRootStatus.classify(ok = false, suMissing = true))
        assertEquals(RootState.DENIED, CheckRootStatus.classify(ok = false, suMissing = false))
        // su works again -> the stale missing flag never wins
        assertEquals(RootState.AVAILABLE, CheckRootStatus.classify(ok = true, suMissing = true))
    }

    @Test
    fun `engine auto-off respects TRUE OFF ownership`() {
        assertTrue(NoRootMode.shouldAutoDisableEngine(engineOn = true, trueOff = false))
        assertFalse(NoRootMode.shouldAutoDisableEngine(engineOn = false, trueOff = false))
        assertFalse(NoRootMode.shouldAutoDisableEngine(engineOn = true, trueOff = true))
    }

    @Test
    fun `journal restore script undoes every kind`() {
        val script = PmStateJournal.restoreScript(
            listOf(
                "suspend|com.example.game",
                "disable|com.example.bloat",
                "hide|com.example.hidden",
                "setting|global:window_animation_scale"
            )
        )
        assertTrue(script.contains("pm unsuspend \"com.example.game\""))
        assertTrue(script.contains("pm enable \"com.example.bloat\""))
        assertTrue(script.contains("pm unhide \"com.example.hidden\""))
        assertTrue(script.contains("settings delete \"global\" \"window_animation_scale\""))
    }

    @Test
    fun `journal restore script ignores malformed entries`() {
        val script = PmStateJournal.restoreScript(listOf("broken", "|no-kind", "setting|nocolon"))
        assertFalse(script.contains("suspend"))
        assertFalse(script.contains("delete"))
    }

    @Test
    fun `guard script only fires when the app is gone and cleans everything`() {
        assertTrue(SceneGuard.SCRIPT.contains("/data/data/com.omarea.vtools"))
        assertTrue(SceneGuard.SCRIPT.contains("pm unsuspend"))
        assertTrue(SceneGuard.SCRIPT.contains("pm enable"))
        assertTrue(SceneGuard.SCRIPT.contains("pm unhide"))
        assertTrue(SceneGuard.SCRIPT.contains("settings delete"))
        assertTrue(SceneGuard.SCRIPT.contains("/data/adb/modules/scene_sepolicy"))
        assertTrue(SceneGuard.SCRIPT.contains("/data/adb/modules/scene_systemless"))
        assertTrue(SceneGuard.SCRIPT.contains("/data/adb/modules/scene_resurgence"))
        assertTrue(SceneGuard.SCRIPT.contains("/data/local/tmp/scene_thermald.sh"))
        assertTrue(SceneGuard.SCRIPT.contains("persist.vtools.suspend"))
        assertTrue(SceneGuard.SCRIPT.contains("MODDIR"))
        assertTrue(SceneGuard.SCRIPT.contains("rm -rf"))
    }
}
