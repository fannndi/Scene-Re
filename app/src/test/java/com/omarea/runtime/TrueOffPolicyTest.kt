package com.omarea.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrueOffPolicyTest {

    @Test
    fun `writes are allowed while TRUE OFF is inactive`() {
        assertTrue(TrueOff.allowsWrite(off = false, force = false))
        assertTrue(TrueOff.allowsWrite(off = false, force = true))
    }

    @Test
    fun `writes are blocked while TRUE OFF is active`() {
        assertFalse(TrueOff.allowsWrite(off = true, force = false))
    }

    @Test
    fun `force bypass exists only for enter-exit transitions`() {
        assertTrue(TrueOff.allowsWrite(off = true, force = true))
    }
}
