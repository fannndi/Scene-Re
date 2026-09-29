package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HwuiResolutionTest {

    @Test
    fun `per-app override wins`() {
        assertEquals("skiagl", HwuiResolution.resolve(false, "skiagl", "opengl"))
    }

    @Test
    fun `profile value is the fallback`() {
        assertEquals("opengl", HwuiResolution.resolve(false, "", "opengl"))
    }

    @Test
    fun `default when nothing is configured`() {
        assertNull(HwuiResolution.resolve(false, "", ""))
        assertNull(HwuiResolution.resolve(false, null, null))
    }

    @Test
    fun `engine off always resolves to default`() {
        assertNull(HwuiResolution.resolve(true, "skiagl", "opengl"))
    }
}
