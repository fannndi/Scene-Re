package com.omarea.util.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignCapacityTest {

    @Test
    fun `microamp-hours are converted to mAh`() {
        assertEquals(5160, DesignCapacity.parse("5160000"))
        assertEquals(4030, DesignCapacity.parse("  4030000\n"))
    }

    @Test
    fun `plain mAh passes through`() {
        assertEquals(5160, DesignCapacity.parse("5160"))
        assertEquals(3300, DesignCapacity.parse("3300"))
    }

    @Test
    fun `implausible values are rejected`() {
        assertNull(DesignCapacity.parse(null))          // missing node
        assertNull(DesignCapacity.parse(""))            // empty
        assertNull(DesignCapacity.parse("error"))       // shell error text
        assertNull(DesignCapacity.parse("0"))           // zero
        assertNull(DesignCapacity.parse("-5160000"))    // negative
        assertNull(DesignCapacity.parse("516"))         // < 2000 mAh
        assertNull(DesignCapacity.parse("51600000"))    // > 20000 mAh after /1000
        assertNull(DesignCapacity.parse("abc"))
    }
}
