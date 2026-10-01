package com.omarea.engine

/**
 * Per-platform SoC identity shown on the Home CPU card.
 *
 * The `ro.board.platform` value is `sm6150` while the real chip is SM7150
 * ("moorea", soc_id 365/366) — see `docs/STOCK-ROM.md`. The card labels the
 * marketing name with the silicon id so the two never contradict.
 */
object SocInfo {
    data class Info(val soc: String, val cpu: String)

    fun forPlatform(platform: String): Info = when (platform.lowercase()) {
        "sm6150" -> Info(
            soc = "Qualcomm Snapdragon 732G · SM7150",
            cpu = "Octa-core: 2× Kryo 470 Gold @2.3 GHz · 6× Kryo 470 Silver @1.8 GHz"
        )
        else -> Info(platform.uppercase(), "Octa-core")
    }
}
