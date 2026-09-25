package com.omarea.scene_mode.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuGameInfoTest {
    @Test
    fun parsesProviderRows() {
        val rows = MiuGameInfo.parse(
            """
            Row: 0 pkg=com.tencent.ig, name=PUBG MOBILE, Mode=1, enable=1, fps=60, gamemode=2
            Row: 1 pkg=com.example.light, name=Puzzle, Mode=3, enable=0, fps=120, gamemode=3
            """.trimIndent()
        )
        assertEquals(2, rows.size)
        val pubg = rows["com.tencent.ig"]!!
        assertEquals("PUBG MOBILE", pubg.name)
        assertEquals("1", pubg.mode)
        assertEquals("2", pubg.gameMode)
        assertEquals("1", pubg.enable)
        assertEquals("60", pubg.fps)
    }

    @Test
    fun skipsRowsWithoutPackage() {
        val rows = MiuGameInfo.parse(
            "Row: 0 name=NoPackage, Mode=1\n" +
                "not a row\n" +
                "Row: 1 pkg=com.example.game, Mode=2"
        )
        assertEquals(1, rows.size)
        assertTrue(rows.containsKey("com.example.game"))
    }

    @Test
    fun keepsCommasInsideTheName() {
        val rows = MiuGameInfo.parse(
            "Row: 0 pkg=com.example.game, name=Hello, World, Mode=2, fps=90"
        )
        val row = rows["com.example.game"]!!
        assertEquals("2", row.mode)
        assertEquals("90", row.fps)
        assertTrue(row.name.startsWith("Hello"))
    }

    @Test
    fun summaryDescribesTheState() {
        val enabled = MiuGameInfo.Row(
            pkg = "com.example.game", enable = "1", mode = "1", gameMode = "1", fps = "60"
        )
        assertEquals("enabled \u00B7 mode 1 \u00B7 60 FPS", MiuGameInfo.summary(enabled))

        val listed = MiuGameInfo.Row(pkg = "com.example.game")
        assertEquals("listed", MiuGameInfo.summary(listed))
    }
}
