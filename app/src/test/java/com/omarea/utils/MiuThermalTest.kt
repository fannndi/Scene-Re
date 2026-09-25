package com.omarea.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuThermalTest {
    @Test
    fun parsesBracketedMap() {
        val rows = MiuThermal.parseMap(
            "[0:thermal-normal.conf]\n[9:thermal-tgame.conf]\n[10:thermal-nolimits.conf]\n"
        )
        assertEquals("thermal-normal.conf", rows[0])
        assertEquals("thermal-tgame.conf", rows[9])
        assertEquals("thermal-nolimits.conf", rows[10])
        assertEquals(3, rows.size)
    }

    @Test
    fun parsesPlainMapAndSkipsJunk() {
        val rows = MiuThermal.parseMap(
            "# comment\n8=thermal-phone.conf\n\nnot a row\n12:thermal-camera.conf\n"
        )
        assertEquals("thermal-phone.conf", rows[8])
        assertEquals("thermal-camera.conf", rows[12])
        assertEquals(2, rows.size)
    }

    @Test
    fun mapsKeysToShippedConfigs() {
        assertEquals("thermal-normal.conf", MiuThermal.configName(0))
        assertEquals("thermal-phone.conf", MiuThermal.configName(8))
        assertEquals("thermal-tgame.conf", MiuThermal.configName(9))
        assertEquals("thermal-tgame.conf", MiuThermal.configName(13))
        assertEquals("thermal-tgame.conf", MiuThermal.configName(16))
        assertEquals("thermal-nolimits.conf", MiuThermal.configName(10))
        assertEquals("thermal-camera.conf", MiuThermal.configName(12))
        assertEquals("thermal-arvr.conf", MiuThermal.configName(15))
        assertTrue(MiuThermal.configName(7).isEmpty())
    }

    @Test
    fun choicesStartWithRomDefaultAndTgame() {
        assertEquals(0, MiuThermal.CHOICES.first())
        assertTrue(MiuThermal.CHOICES.contains(9))
        assertTrue(MiuThermal.CHOICES.contains(10))
    }
}
