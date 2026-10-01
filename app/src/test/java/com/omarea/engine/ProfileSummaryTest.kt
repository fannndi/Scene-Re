package com.omarea.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSummaryTest {

    private val policies = listOf("policy0", "policy6")

    @Test
    fun `caps and ufs are rendered in order`() {
        val profile = JSONObject(
            """{"cpu":{"policy0":{"max":1324800},"policy6":{"max":1497600}},"ufs":"save"}"""
        )
        assertEquals("1.32 / 1.50 GHz · UFS save", ProfileSummary.summarize(profile, policies))
    }

    @Test
    fun `gpu cap is appended when set`() {
        val profile = JSONObject(
            """{"cpu":{"policy0":{"max":1804800}},"gpu":{"max_pwrlevel":5}}"""
        )
        assertEquals("1.80 GHz · GPU ≤ p5", ProfileSummary.summarize(profile, policies))
    }

    @Test
    fun `uncapped gpu and missing values are skipped`() {
        val profile = JSONObject("""{"cpu":{"policy0":{"max":1804800}},"gpu":{"max_pwrlevel":0}}""")
        assertEquals("1.80 GHz", ProfileSummary.summarize(profile, policies))
        assertEquals("", ProfileSummary.summarize(null, policies))
        assertEquals("", ProfileSummary.summarize(JSONObject("{}"), policies))
    }
}
