package com.omarea.core.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileKeyTest {

    @Test
    fun `legacy fast maps to custom`() {
        assertEquals(ProfileKey.CUSTOM, ProfileKey.canonical(ProfileKey.LEGACY_FAST))
    }

    @Test
    fun `other ids are unchanged`() {
        assertEquals("powersave", ProfileKey.canonical("powersave"))
        assertEquals("balance", ProfileKey.canonical("balance"))
        assertEquals("performance", ProfileKey.canonical("performance"))
        assertEquals("release", ProfileKey.canonical("release"))
    }

    @Test
    fun `lookup accepts the canonical key`() {
        val profiles = JSONObject("""{"custom":{"a":1}}""")
        assertEquals(1, ProfileKey.profile(profiles, "custom")!!.getInt("a"))
    }

    @Test
    fun `lookup accepts the legacy key via the runtime mode id`() {
        val profiles = JSONObject("""{"custom":{"a":1}}""")
        assertEquals(1, ProfileKey.profile(profiles, "fast")!!.getInt("a"))
    }

    @Test
    fun `lookup falls back to a legacy key in the JSON`() {
        val legacy = JSONObject("""{"fast":{"b":2}}""")
        assertSame(legacy.getJSONObject("fast"), ProfileKey.profile(legacy, "custom"))
    }

    @Test
    fun `missing profiles resolve to null`() {
        assertNull(ProfileKey.profile(JSONObject("{}"), "balance"))
        assertNull(ProfileKey.profile(null, "balance"))
    }

    @Test
    fun `all ids include the release profile`() {
        assertEquals(
            listOf("powersave", "balance", "performance", "custom", "release"),
            ProfileKey.ALL_WITH_RELEASE
        )
    }
}
