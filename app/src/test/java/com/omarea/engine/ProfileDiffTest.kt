package com.omarea.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDiffTest {

    @Test
    fun `identical objects have no changes`() {
        val a = JSONObject("""{"cpu":{"policy0":{"max":1324800}},"ufs":"save"}""")
        val b = JSONObject("""{"ufs":"save","cpu":{"policy0":{"max":1324800}}}""")
        assertEquals(emptyList<String>(), ProfileDiff.changedPaths(a, b))
    }

    @Test
    fun `numbers compare numerically`() {
        val a = JSONObject("""{"ms":30}""")
        val b = JSONObject("""{"ms":30.0}""")
        assertEquals(emptyList<String>(), ProfileDiff.changedPaths(a, b))
    }

    @Test
    fun `changed leaf reports its path`() {
        val a = JSONObject("""{"cpu":{"policy0":{"max":1804800}}}""")
        val b = JSONObject("""{"cpu":{"policy0":{"max":1324800}}}""")
        assertEquals(listOf("cpu.policy0.max"), ProfileDiff.changedPaths(a, b))
    }

    @Test
    fun `added and removed keys are both reported`() {
        val a = JSONObject("""{"a":1,"b":2}""")
        val b = JSONObject("""{"a":1,"c":3}""")
        assertEquals(listOf("b", "c"), ProfileDiff.changedPaths(a, b))
    }

    @Test
    fun `missing side means not modified`() {
        val preset = JSONObject("""{"a":1}""")
        assertFalse(ProfileDiff.isModified(null, preset))
        assertFalse(ProfileDiff.isModified(preset, null))
        assertTrue(ProfileDiff.isModified(JSONObject("""{"a":2}"""), preset))
    }
}
