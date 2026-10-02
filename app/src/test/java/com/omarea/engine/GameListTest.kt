package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data integrity for the bundled known-games list (Encore Tweaks, Apache-2.0).
 */
class GameListTest {

    private val entries: List<String> =
        File("src/main/assets/games/encore_gamelist.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `list is non-trivial`() {
        assertTrue("expected > 100 games, got ${entries.size}", entries.size > 100)
    }

    @Test
    fun `every entry is a valid package name`() {
        for (entry in entries) {
            assertTrue("invalid package name: $entry", GameList.PACKAGE_REGEX.matches(entry))
        }
    }

    @Test
    fun `entries are unique`() {
        assertEquals(entries.size, entries.toSet().size)
    }
}
