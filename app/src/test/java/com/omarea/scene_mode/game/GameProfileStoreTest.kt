package com.omarea.scene_mode.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameProfileStoreTest {
    @Test
    fun parseReadsKeyValueLines() {
        val parsed = GameProfileStore.parseProfiles(
            """
            # comment
            com.example.game=custom

            com.example.other=light
            broken-line
            =missing
            """.trimIndent()
        )
        assertEquals("custom", parsed["com.example.game"])
        assertEquals("light", parsed["com.example.other"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun renderSortsAndSkipsEmptyValues() {
        val rendered = GameProfileStore.renderProfiles(
            mapOf("b.game" to "performance", "a.game" to "light", "empty.game" to "")
        )
        assertEquals("a.game=light\nb.game=performance", rendered)
    }

    @Test
    fun autoResolvesUnknownToPerformance() {
        assertEquals(
            GameProfileStore.PERFORMANCE,
            GameProfileStore.resolve(GameProfileStore.AUTO, "", false)
        )
    }

    @Test
    fun autoResolvesLightToLightWithoutCustomConfig() {
        assertEquals(
            GameProfileStore.LIGHT,
            GameProfileStore.resolve(GameProfileStore.AUTO, GameProfileStore.CLASS_LIGHT, false)
        )
    }

    @Test
    fun autoResolvesLightToCustomWhenOneIsSaved() {
        assertEquals(
            GameProfileStore.CUSTOM,
            GameProfileStore.resolve(GameProfileStore.AUTO, GameProfileStore.CLASS_LIGHT, true)
        )
    }

    @Test
    fun explicitOverrideAlwaysWins() {
        assertEquals(
            GameProfileStore.PERFORMANCE,
            GameProfileStore.resolve(GameProfileStore.PERFORMANCE, GameProfileStore.CLASS_LIGHT, true)
        )
        assertEquals(
            GameProfileStore.KEEP,
            GameProfileStore.resolve(GameProfileStore.KEEP, GameProfileStore.CLASS_HEAVY, false)
        )
        assertEquals(
            GameProfileStore.OFF,
            GameProfileStore.resolve(GameProfileStore.OFF, "", false)
        )
    }
}
