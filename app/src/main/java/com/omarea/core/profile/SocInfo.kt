package com.omarea.core.profile

/** Per-platform SoC identity shown on the Home CPU card. */
object SocInfo {
    data class Info(val soc: String, val cpu: String)

    fun forPlatform(platform: String): Info = when (platform.lowercase()) {
        "sm6150" -> Info(
            soc = "Qualcomm SDM732 Snapdragon 732G",
            cpu = "Octa-core Cortex A76 (2x2.3 GHz Kryo 470 Gold & 6x1.8 GHz Kryo 470 Silver) Cortex A55"
        )
        else -> Info(platform.uppercase(), "Octa-core")
    }
}
