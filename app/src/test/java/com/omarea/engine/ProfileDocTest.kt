package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDocTest {

    private val preset = """
        {
          "profiles": {
            "powersave": { "cpu": { "policy0": { "max": 1324800 } }, "ufs": "save" },
            "balance":   { "cpu": { "policy0": { "max": 1497600 } }, "ufs": "save" },
            "fast":      { "cpu": { "policy0": { "max": 1804800 } }, "ufs": "perf" }
          }
        }
    """.trimIndent()

    @Test
    fun `effective profile prefers the user copy`() {
        val user = """{"profiles":{"balance":{"cpu":{"policy0":{"max":1200000}}}}}"""
        val doc = ProfileDoc.parse(user, preset)
        assertEquals(1200000L, doc.effectiveProfile("balance").getJSONObject("cpu").getJSONObject("policy0").getLong("max"))
    }

    @Test
    fun `effective profile falls back to the preset per profile`() {
        val doc = ProfileDoc.parse("""{"profiles":{"balance":{"cpu":{"policy0":{"max":1200000}}}}}""", preset)
        assertEquals(1324800L, doc.effectiveProfile("powersave").getJSONObject("cpu").getJSONObject("policy0").getLong("max"))
    }

    @Test
    fun `legacy fast id resolves and is rewritten as custom`() {
        val doc = ProfileDoc.parse(null, preset)
        assertEquals(1804800L, doc.effectiveProfile("fast").getJSONObject("cpu").getJSONObject("policy0").getLong("max"))
        doc.withProfile("fast", doc.effectiveProfile("custom").put("ufs", "save"))
        val profiles = org.json.JSONObject(doc.userText()).getJSONObject("profiles")
        assertTrue(profiles.has("custom"))
        assertFalse(profiles.has("fast"))
        assertEquals("save", profiles.getJSONObject("custom").getString("ufs"))
    }

    @Test
    fun `modified is false for an untouched profile and true after an edit`() {
        val doc = ProfileDoc.parse(null, preset)
        assertFalse(doc.isModified("balance"))
        doc.withProfile("balance", doc.effectiveProfile("balance").apply {
            getJSONObject("cpu").getJSONObject("policy0").put("max", 1200000)
        })
        assertTrue(doc.isModified("balance"))
        assertEquals(listOf("cpu.policy0.max"), doc.modifiedPaths("balance"))
    }

    @Test
    fun `reset restores the shipped preset`() {
        val user = """{"profiles":{"balance":{"cpu":{"policy0":{"max":1200000}}}}}"""
        val doc = ProfileDoc.parse(user, preset)
        doc.resetToPreset("balance")
        assertFalse(doc.isModified("balance"))
        assertEquals(1497600L, doc.effectiveProfile("balance").getJSONObject("cpu").getJSONObject("policy0").getLong("max"))
    }

    @Test
    fun `editing one profile keeps the others untouched`() {
        val user = """{"profiles":{"powersave":{"cpu":{"policy0":{"max":999}}}}}"""
        val doc = ProfileDoc.parse(user, preset)
        doc.withProfile("balance", doc.effectiveProfile("balance").apply { put("ufs", "perf") })
        val profiles = org.json.JSONObject(doc.userText()).getJSONObject("profiles")
        assertEquals(999L, profiles.getJSONObject("powersave").getJSONObject("cpu").getJSONObject("policy0").getLong("max"))
    }
}
