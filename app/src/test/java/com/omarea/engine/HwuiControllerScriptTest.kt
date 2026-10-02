package com.omarea.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure script builder for the HWUI props (SkiaShift companion props).
 *
 * The script is a resetprop command line — no shell is executed here.
 */
class HwuiControllerScriptTest {

    @Test
    fun `vulkan renderer sets the companion props`() {
        val script = HwuiController.applyScript("skiavk", null)
        assertTrue(script.contains("debug.hwui.renderer 'skiavk'"))
        assertTrue(script.contains("--delete ro.hwui.use_vulkan"))
        assertTrue(script.contains("debug.hwui.use_buffer_age 'true'"))
        assertTrue(script.contains("renderthread.skia.reduceopstasksplitting 'true'"))
    }

    @Test
    fun `vulkan flag alone also sets the companion props`() {
        val script = HwuiController.applyScript(null, "true")
        assertTrue(script.contains("--delete debug.hwui.renderer"))
        assertTrue(script.contains("ro.hwui.use_vulkan 'true'"))
        assertTrue(script.contains("debug.hwui.use_buffer_age 'true'"))
        assertTrue(script.contains("renderthread.skia.reduceopstasksplitting 'true'"))
    }

    @Test
    fun `an explicit gl renderer removes the companion props`() {
        val script = HwuiController.applyScript("skiagl", "true")
        assertTrue(script.contains("debug.hwui.renderer 'skiagl'"))
        assertTrue(script.contains("--delete debug.hwui.use_buffer_age"))
        assertTrue(script.contains("--delete renderthread.skia.reduceopstasksplitting"))
    }

    @Test
    fun `system default removes every prop`() {
        val script = HwuiController.applyScript(null, null)
        assertTrue(script.contains("--delete debug.hwui.renderer"))
        assertTrue(script.contains("--delete ro.hwui.use_vulkan"))
        assertTrue(script.contains("--delete debug.hwui.use_buffer_age"))
        assertTrue(script.contains("--delete renderthread.skia.reduceopstasksplitting"))
        assertFalse(script.contains("'true'"))
    }
}
