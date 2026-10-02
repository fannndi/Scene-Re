package com.omarea.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomBootGateTest {

    @Test
    fun `stopped and missing service count as finished`() {
        assertTrue(RomBootGate.isFinished("stopped"))
        assertTrue(RomBootGate.isFinished(""))
        assertTrue(RomBootGate.isFinished(null))
        assertTrue(RomBootGate.isFinished("  "))
    }

    @Test
    fun `running or starting service is not finished`() {
        assertFalse(RomBootGate.isFinished("running"))
        assertFalse(RomBootGate.isFinished("starting"))
    }

    @Test
    fun `drift diff reports only changed values`() {
        val before = mapOf("a" to "1", "b" to "2", "c" to "3")
        val after = mapOf("a" to "1", "b" to "9", "c" to "3")
        assertEquals(listOf("b"), PostApplyDriftGuard.changedNodes(before, after))
    }

    @Test
    fun `drift diff ignores nodes missing from the second read`() {
        val before = mapOf("a" to "1", "b" to "2")
        val after = mapOf("a" to "1")
        assertTrue(PostApplyDriftGuard.changedNodes(before, after).isEmpty())
    }

    @Test
    fun `watch list covers the boot-race families`() {
        val nodes = PostApplyDriftGuard.watchNodes
        assertTrue(nodes.any { it.endsWith("policy0/scaling_max_freq") })
        assertTrue(nodes.any { it.endsWith("input_boost_freq") })
        assertTrue(nodes.any { it.endsWith("core_ctl/enable") })
        assertTrue(nodes.any { it.endsWith("/dev/cpuset/top-app/cpus") })
    }
}
