package com.omarea.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFormatTest {

    @Test
    fun `khz formats with one decimal only when needed`() {
        assertEquals("1804.8", HomeFormat.mhz(1804800))
        assertEquals("1324.8", HomeFormat.mhz(1324800))
        assertEquals("300", HomeFormat.mhz(300000))
        assertEquals("2208", HomeFormat.mhz(2208000))
        assertEquals("--", HomeFormat.mhz(null))
        assertEquals("--", HomeFormat.mhz(0))
    }

    @Test
    fun `cluster shows profile range and thermal hold marker`() {
        val text = HomeFormat.cluster(
            governor = "schedutil",
            curKhz = 979200,
            liveMinKhz = 300000,
            liveMaxKhz = 1209600,
            profileMinKhz = 300000,
            profileMaxKhz = 2208000,
            kernelHeld = true
        )
        assertEquals(
            "schedutil\ncur 979.2 MHz · live 300–1209.6\nprofil 300–2208 MHz · kernel thermal",
            text
        )
    }

    @Test
    fun `cluster omits the profile line without a profile`() {
        val text = HomeFormat.cluster(
            governor = "schedutil",
            curKhz = 300000,
            liveMinKhz = 300000,
            liveMaxKhz = 1804800,
            profileMinKhz = null,
            profileMaxKhz = null,
            kernelHeld = false
        )
        assertEquals("schedutil\ncur 300 MHz · live 300–1804.8", text)
    }

    @Test
    fun `cluster without a matching profile keeps the thermal marker off`() {
        val text = HomeFormat.cluster(
            governor = "performance",
            curKhz = null,
            liveMinKhz = null,
            liveMaxKhz = null,
            profileMinKhz = 300000,
            profileMaxKhz = 1804800,
            kernelHeld = false
        )
        assertEquals("performance\ncur -- MHz · live --\nprofil 300–1804.8 MHz", text)
    }

    @Test
    fun `pwrlevel range maps indices through the frequency table`() {
        val table = listOf("800", "650", "565", "430", "355", "267", "180")
        // powersave floor 6 / cap 5, order-independent.
        assertEquals("180–267", HomeFormat.pwrlevelRangeMhz(table, 6, 5))
        // performance floor 5 / cap 0 (no cap → highest clock).
        assertEquals("267–800", HomeFormat.pwrlevelRangeMhz(table, 5, 0))
        // equal bounds collapse to a single value.
        assertEquals("267", HomeFormat.pwrlevelRangeMhz(table, 5, 5))
        // missing table → null.
        assertEquals(null, HomeFormat.pwrlevelRangeMhz(emptyList(), 5, 0))
    }

    @Test
    fun `gpu detail appends the profile range when known`() {
        val withProfile = HomeFormat.gpuDetail(
            "msm-adreno-tz", "355 MHz", "180 - 800 MHz", "180–800"
        )
        assertEquals("msm-adreno-tz · profil 180–800 MHz\n355 MHz  (180 - 800 MHz)", withProfile)

        val without = HomeFormat.gpuDetail(
            "msm-adreno-tz", "355 MHz", "180 - 800 MHz", null
        )
        assertEquals("msm-adreno-tz\n355 MHz  (180 - 800 MHz)", without)
    }
}
