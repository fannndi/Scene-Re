package com.omarea.engine

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterCatalogTest {

    private val caps = DeviceCaps(
        freqs = mapOf(
            "policy0" to listOf(300000L, 1804800L),
            "policy6" to listOf(300000L, 2304000L)
        ),
        governors = mapOf(
            "policy0" to listOf("schedutil", "performance"),
            "policy6" to listOf("schedutil")
        )
    )

    @Test
    fun `documents allowed values and per-profile values`() {
        val catalog = ParameterCatalog.generate(
            "sm6150",
            JSONObject(
                """
                {
                  "profiles": {
                    "balance": { "cpu": { "policy0": { "max": 1708800 } } },
                    "release": { "cpu": { "policy0": { "max": 1804800 } } }
                  }
                }
                """.trimIndent()
            ),
            caps
        )
        assertTrue(catalog.contains("[cpu.policy0.max]"))
        assertTrue(catalog.contains("allowed: 300000..1804800 KHz"))
        assertTrue(catalog.contains("stock       : 1804800"))
        assertTrue(catalog.contains("balance     : 1708800"))
    }

    @Test
    fun `legacy fast key is merged into the custom profile`() {
        val catalog = ParameterCatalog.generate(
            "sm6150",
            JSONObject(
                """
                {
                  "profiles": {
                    "fast": { "cpu": { "policy0": { "governor": "schedutil" } } }
                  }
                }
                """.trimIndent()
            ),
            caps
        )
        assertTrue(catalog.contains("custom      : schedutil"))
        assertFalse(catalog.contains("fast"))
    }

    @Test
    fun `locked keys are marked in the catalog`() {
        val catalog = ParameterCatalog.generate(
            "sm6150",
            JSONObject(
                """
                {
                  "profiles": {
                    "balance": { "gpu": { "throttling": 0 } },
                    "release": {}
                  }
                }
                """.trimIndent()
            ),
            caps,
            locked = setOf("kgsl_throttling")
        )
        assertTrue(catalog.contains("gpu.throttling"))
        assertTrue(catalog.contains("LOCKED"))
        assertTrue(catalog.contains("GPU thermal throttling"))
    }
}
