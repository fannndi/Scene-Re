package com.omarea.engine

import com.omarea.common.shell.KeepShellPublic

/**
 * Generic controller for the Qualcomm bus DCVS domains (DDR, DDRQOS, L3, LLCC).
 *
 * Ports the old kr-script ddr.sh / ddrqos.sh / l3.sh / llcc.sh behaviour:
 * writes lock the node by chmod 644 -> write -> chmod 444.
 *
 * Responsibility: read/write min/max/boost (+ DDR fixed freq).
 * Non-goals: UI.
 */
object BusDcvs {

    data class Domain(val id: String, val path: String)

    const val BASE = "/sys/devices/system/cpu/bus_dcvs"
    const val DDR_FIXED_FREQ = "/dev/scene/debug/qcom_aoss/ddr_frequency_mhz"
    const val DDR_FIXED_READ = "/dev/scene/ddr_frequency_mhz"

    val domains = listOf(
        Domain("DDR", "$BASE/DDR"),
        Domain("DDRQOS", "$BASE/DDRQOS"),
        Domain("L3", "$BASE/L3"),
        Domain("LLCC", "$BASE/LLCC")
    )

    enum class Kind(val leaf: String) {
        MIN("min_freq"),
        MAX("max_freq"),
        BOOST("boost_freq")
    }

    fun find(id: String): Domain? = domains.firstOrNull { it.id == id }

    fun visible(id: String): Boolean =
        KeepShellPublic.doCmdSync("[ -d ${find(id)?.path} ] && echo 1 || echo 0").trim() == "1"

    fun options(id: String): List<String> {
        val path = find(id)?.path ?: return emptyList()
        return KeepShellPublic.doCmdSync("cat $path/available_frequencies 2>/dev/null")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun read(id: String, kind: Kind): String {
        val path = find(id)?.path ?: return ""
        return if (kind == Kind.BOOST) {
            KeepShellPublic.doCmdSync("cat $path/boost_freq 2>/dev/null").trim()
        } else {
            KeepShellPublic.doCmdSync("cat $path/*/${kind.leaf} 2>/dev/null | head -1").trim()
        }
    }

    /** Pure command builder; write + chmod lock, same as the old scripts. */
    fun setCommand(id: String, kind: Kind, value: String): String {
        val path = find(id)?.path ?: return ""
        val pattern = if (kind == Kind.BOOST) "$path/boost_freq" else "$path/*/${kind.leaf}"
        return "for f in $pattern; do chmod 644 \"\$f\" 2>/dev/null; " +
            "echo $value > \"\$f\" 2>/dev/null; chmod 444 \"\$f\" 2>/dev/null; done"
    }

    fun set(id: String, kind: Kind, value: String) {
        KeepShellPublic.doCmdSync(setCommand(id, kind, value))
    }

    fun ddrFixedVisible(): Boolean =
        KeepShellPublic.doCmdSync("[ -e $DDR_FIXED_FREQ ] && echo 1 || echo 0").trim() == "1"

    fun ddrFixedRead(): String =
        KeepShellPublic.doCmdSync("cat $DDR_FIXED_READ 2>/dev/null").trim()

    fun ddrFixedSet(khz: String) {
        KeepShellPublic.doCmdSync(
            "echo $(( $khz / 1000 )) > $DDR_FIXED_FREQ 2>/dev/null; echo $khz > $DDR_FIXED_READ 2>/dev/null"
        )
    }
}
