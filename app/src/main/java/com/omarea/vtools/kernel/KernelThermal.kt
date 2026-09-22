package com.omarea.vtools.kernel

/** A readable thermal zone; [temperatureTenths] is null when the zone has no temperature node. */
data class ThermalZone(
    val index: Int,
    val type: String,
    val temperatureTenths: Int?
)

/**
 * Reads thermal zones 0..15 in one shell round trip.
 *
 * Zones are enumerated instead of hardcoded so the list matches the device: surya exposes a
 * different set than other SM7150 kernels. Denied zones simply disappear from the result.
 */
object ThermalZones {
    private const val ZONE_DIR = "/sys/class/thermal"
    private const val MAX_ZONES = 16

    fun load(): List<ThermalZone> {
        val paths = (0 until MAX_ZONES).flatMap { index ->
            listOf("$ZONE_DIR/thermal_zone$index/type", "$ZONE_DIR/thermal_zone$index/temp")
        }
        val values = KernelShell.readMany(paths)
        val zones = ArrayList<ThermalZone>()
        for (index in 0 until MAX_ZONES) {
            val typePath = "$ZONE_DIR/thermal_zone$index/type"
            val tempPath = "$ZONE_DIR/thermal_zone$index/temp"
            val type = values[typePath].orEmpty()
            val temperature = values[tempPath].orEmpty()
            if (type.isEmpty() && temperature.isEmpty()) {
                continue
            }
            zones.add(
                ThermalZone(
                    index = index,
                    type = type.ifEmpty { "thermal_zone$index" },
                    temperatureTenths = temperature.toIntOrNull()
                )
            )
        }
        return zones
    }
}
