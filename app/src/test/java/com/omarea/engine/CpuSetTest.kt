package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuSetTest {

    @Test
    fun `parses ranges and singles`() {
        assertEquals(setOf(0, 1, 2, 3, 5), CpuSet.parse("0-3,5"))
    }

    @Test
    fun `parses a single cpu`() {
        assertEquals(setOf(7), CpuSet.parse("7"))
    }

    @Test
    fun `empty spec yields an empty set`() {
        assertEquals(emptySet<Int>(), CpuSet.parse(""))
    }

    @Test
    fun `ignores invalid tokens`() {
        assertEquals(setOf(1), CpuSet.parse("nope,1,bad-2,4-x"))
    }

    @Test
    fun `online check`() {
        assertEquals(true, CpuSet.isOnline("0-7", 6))
        assertEquals(false, CpuSet.isOnline("0-5", 6))
    }
}
