package com.omarea.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRangeTest {

    private val json = JSONObject(
        """
        {
          "profiles": {
            "performance": {
              "cpu": {
                "policy0": { "min": 300000, "max": 1804800 },
                "policy6": { "max": 2208000 }
              },
              "gpu": { "min_pwrlevel": 5, "max_pwrlevel": 0 }
            },
            "fast": {
              "cpu": { "policy0": { "min": 1708800, "max": 1804800 } }
            }
          }
        }
        """.trimIndent()
    )

    @Test
    fun `cpu request reads min and max per policy`() {
        val request = ProfileRange.cpu(json, "performance", "policy0")!!
        assertEquals(300000L, request.minKhz)
        assertEquals(1804800L, request.maxKhz)
    }

    @Test
    fun `missing min key stays null`() {
        val request = ProfileRange.cpu(json, "performance", "policy6")!!
        assertNull(request.minKhz)
        assertEquals(2208000L, request.maxKhz)
    }

    @Test
    fun `unknown mode or policy yields null`() {
        assertNull(ProfileRange.cpu(json, "balance", "policy0"))
        assertNull(ProfileRange.cpu(json, "performance", "policy2"))
        assertNull(ProfileRange.cpu(null, "performance", "policy0"))
    }

    @Test
    fun `legacy fast alias resolves to the custom profile`() {
        val request = ProfileRange.cpu(json, "fast", "policy0")!!
        assertEquals(1708800L, request.minKhz)
        assertEquals(1804800L, request.maxKhz)
    }

    @Test
    fun `gpu request reads pwrlevels`() {
        val gpu = ProfileRange.gpu(json, "performance")!!
        assertEquals(5, gpu.floorPwrlevel)
        assertEquals(0, gpu.capPwrlevel)
    }
}
