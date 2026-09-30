package com.omarea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table must match the stock ROM's vendor/etc/thermal-map.conf exactly —
 * it is what mi_thermald uses to pick thermal-<x>.conf after `sconfig` is
 * written (decrypted with the mi-thermal-crypt reference, key/IV
 * "thermalopenssl.h").
 */
class ThermalProfilesTest {

    @Test
    fun `map matches the stock thermal-map conf`() {
        val expected = mapOf(
            0 to "thermal-normal.conf",
            1 to "thermal-high.conf",
            2 to "thermal-extreme.conf",
            8 to "thermal-phone.conf",
            9 to "thermal-tgame.conf",
            10 to "thermal-nolimits.conf",
            11 to "thermal-class0.conf",
            12 to "thermal-camera.conf",
            13 to "thermal-tgame.conf",
            14 to "thermal-youtube.conf",
            15 to "thermal-arvr.conf",
            16 to "thermal-tgame.conf"
        )
        assertEquals(expected.size, ThermalProfiles.entries.size)
        for ((sconfig, config) in expected) {
            assertEquals(config, ThermalProfiles.find(sconfig)?.config)
        }
        assertNull(ThermalProfiles.find(7))
        assertNull(ThermalProfiles.find(99))
    }

    @Test
    fun `only configs shipping in the rom are safe presets`() {
        val shipped = ThermalProfiles.shipped.map { it.sconfig }.toSet()
        assertEquals(setOf(0, 8, 9, 10, 12, 13, 15, 16), shipped)
        // values 1/2/11/14 reference missing files -> must be flagged
        for (missing in listOf(1, 2, 11, 14)) {
            assertFalse(ThermalProfiles.find(missing)!!.shipped)
        }
    }

    @Test
    fun `label resolves values and passes unknown through`() {
        assertEquals("0 (Normal)", ThermalProfiles.label("0"))
        assertEquals("10 (No limits)", ThermalProfiles.label(" 10 "))
        assertEquals("9 (Game)", ThermalProfiles.label("9"))
        assertEquals("42", ThermalProfiles.label("42"))
        assertEquals("", ThermalProfiles.label(null))
        assertEquals("", ThermalProfiles.label(""))
    }

    @Test
    fun `preset list is human readable and complete`() {
        val list = ThermalProfiles.presetList()
        assertTrue(list.startsWith("0=Normal"))
        assertTrue(list.contains("10=No limits"))
        assertTrue(list.contains("15=AR/VR"))
        // non-shipped values are not advertised
        assertFalse(list.contains("1=High"))
        assertFalse(list.contains("14=YouTube"))
    }
}
