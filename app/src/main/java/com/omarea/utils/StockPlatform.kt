package com.omarea.utils

import com.omarea.common.shell.KeepShellPublic

/**
 * Read-only view of the stock MIUI 14 platform configuration that Scene builds
 * on. Everything here is a file or property the ROM itself ships, so the report
 * shows what the platform asks for (and what Scene therefore follows) instead
 * of assumptions:
 *
 *  - `vendor/etc/lm/GameOptimizationFeature.xml`: MIUI's own game DDR floor
 *    (`MIN_DDR_FREQ`) and its ceiling (`MAX_MIN_DDR_FREQ`); the options layer
 *    reads the same file at runtime for the in-game DDR floor.
 *  - `vendor/etc/perf/targetconfig.xml`: the perf HAL's target description -
 *    `CpufreqGov` (1 = schedutil on sm6150) and the core_ctl expectations
 *    (`CoreCtlCpu`, `MinCoreOnline`).
 *  - `vendor/etc/perf/perfconfigstore.xml`: the properties the perf HAL answers
 *    with (IOP/prefetch/gesture boost/LMK).
 *  - `system/system/etc/perfinit.conf`: MIUI's memory config (zram size per RAM
 *    tier, swappiness, extm backing file, dex2oat thread budgets).
 *  - the power props from the build.prop files (`vendor.power.pasr.enabled`,
 *    `ro.charger.enable_suspend`, `dalvik.vm.dexopt.thermal-cutoff`, ...).
 *  - the stock IRQ balancer: the ROM ships the binary and the confs but leaves
 *    all three services `disabled`, which is why the options layer offers it as
 *    an explicit opt-in.
 */
object StockPlatform {
    private const val LM_XML = "/vendor/etc/lm/GameOptimizationFeature.xml"
    private const val TARGET_XML = "/vendor/etc/perf/targetconfig.xml"
    private const val STORE_XML = "/vendor/etc/perf/perfconfigstore.xml"
    private const val PERFINIT = "/system/system/etc/perfinit.conf"
    private const val IRQ_CONF = "/vendor/etc/msm_irqbalance.conf"

    fun report(): String {
        val sb = StringBuilder("Stock MIUI platform\n")
        try {
            sb.append("Game optimization (").append(LM_XML).append(")\n")
            val lm = shell("cat $LM_XML 2> /dev/null")
            if (lm.isBlank()) {
                sb.append("  (absent)\n")
            } else {
                sb.append("  Enable = ").append(xmlValue(lm, "Enable").ifEmpty { "?" }).append('\n')
                sb.append("  MIN_DDR_FREQ = ").append(xmlValue(lm, "MIN_DDR_FREQ").ifEmpty { "?" }).append('\n')
                sb.append("  MAX_MIN_DDR_FREQ = ").append(xmlValue(lm, "MAX_MIN_DDR_FREQ").ifEmpty { "?" }).append('\n')
            }
            sb.append("  used by Scene for the in-game DDR floor\n")

            sb.append("\nPerf HAL target (").append(TARGET_XML).append(")\n")
            val target = shell("cat $TARGET_XML 2> /dev/null")
            sb.append("  soc_id = ").append(valueOf("/sys/devices/soc0/soc_id")).append('\n')
            if (target.isBlank()) {
                sb.append("  (absent)\n")
            } else {
                for (label in listOf("sdmmagpie", "msmsteppe")) {
                    val block = target.substringAfter("Target=\"$label\"", "")
                    if (block.isEmpty()) {
                        continue
                    }
                    sb.append("  ").append(label)
                        .append(": CpufreqGov=").append(attr(block, "CpufreqGov"))
                        .append(" CoreCtlCpu=").append(attr(block, "CoreCtlCpu"))
                        .append(" MinCoreOnline=").append(attr(block, "MinCoreOnline"))
                        .append(" (CpufreqGov 1 = schedutil)")
                        .append('\n')
                }
            }

            sb.append("\nPerf config store (").append(STORE_XML).append(")\n")
            val store = shell("cat $STORE_XML 2> /dev/null")
            for (match in Regex("<Prop Name=\"([^\"]+)\" Value=\"([^\"]*)\"").findAll(store)) {
                sb.append("  ").append(match.groupValues[1]).append(" = ")
                    .append(match.groupValues[2]).append('\n')
            }

            sb.append("\nMemory config (").append(PERFINIT).append(")\n")
            val perfinit = shell("cat $PERFINIT 2> /dev/null")
            if (perfinit.isBlank()) {
                sb.append("  (absent)\n")
            } else {
                sb.append("  swap_on = ").append(jsonNumber(perfinit, "swap_on")).append('\n')
                sb.append("  global_swappiness = ").append(jsonNumber(perfinit, "global_swappiness")).append('\n')
                sb.append("  page_cluster = ").append(jsonNumber(perfinit, "page_cluster")).append('\n')
                sb.append("  zram_size = ").append(jsonObject(perfinit, "zram_size")).append('\n')
                sb.append("  extm = ").append(jsonNumber(perfinit, "extm_on"))
                    .append(" size ").append(jsonObject(perfinit, "extm_size")).append('\n')
                sb.append("  dex2oat_threads/surya = ").append(suryaDex2oat(perfinit)).append('\n')
            }

            sb.append("\nPower properties\n")
            for (prop in listOf(
                "ro.vendor.perf-hal.ver", "ro.vendor.extension_library", "vendor.power.pasr.enabled",
                "ro.charger.enable_suspend", "dalvik.vm.dexopt.thermal-cutoff",
                "persist.power.useautobrightadj", "persist.vendor.quick.charge",
                "ro.lmk.use_minfree_levels", "ro.lmk.psi_complete_stall_ms", "sys.thermal.data.path"
            )) {
                sb.append("  ").append(prop).append(" = ")
                    .append(shell("getprop $prop 2> /dev/null").trim().ifEmpty { "(unset)" }).append('\n')
            }

            sb.append("\nDDR/L3 latency governors (perf HAL resource 0xD)\n")
            val latencies = shell(
                "for d in /sys/class/devfreq/*lat*; do [ -d \"\$d\" ] || continue; " +
                    "echo \"  \$(basename \"\$d\"): gov=\$(cat \"\$d/governor\" 2> /dev/null) " +
                    "min=\$(cat \"\$d/min_freq\" 2> /dev/null) " +
                    "ratio_ceil=\$(cat \"\$d/mem_latency/ratio_ceil\" 2> /dev/null) " +
                    "stall_floor=\$(cat \"\$d/mem_latency/stall_floor\" 2> /dev/null)\"; done"
            ).trim()
            sb.append(latencies.ifEmpty { "  (none exposed)" }).append('\n')

            sb.append("\nIRQ balancer (shipped, disabled by the ROM)\n")
            sb.append("  init.svc.vendor.msm_irqbalance = ")
                .append(shell("getprop init.svc.vendor.msm_irqbalance 2> /dev/null").trim().ifEmpty { "(not running)" })
                .append('\n')
            sb.append("  conf = ")
                .append(
                    shell("grep -E '^PRIO=|^IGNORED_IRQ=' $IRQ_CONF 2> /dev/null")
                        .trim().replace('\n', ' ').ifEmpty { "(absent)" }
                )
                .append('\n')
            sb.append("  Scene instance = ")
                .append(shell("getprop vtools.scene.irqbal.owned 2> /dev/null").trim().ifEmpty { "0" })
                .append(" (conf /data/adb/scene/irqbalance.conf, adopted from IRQ-Balancer-Configuration)")
                .append('\n')
            sb.append("  Scene pins = ")
                .append(shell("getprop vtools.scene.irq.pinned 2> /dev/null").trim().ifEmpty { "(none)" })
                .append('\n')
            for (irq in shell("getprop vtools.scene.irq.pinned 2> /dev/null").trim()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)) {
                sb.append("    irq ").append(irq).append(" -> ")
                    .append(shell("cat /proc/irq/$irq/smp_affinity_list 2> /dev/null").trim().ifEmpty { "?" })
                    .append('\n')
            }
        } catch (ex: Exception) {
            sb.append("  (report failed: ").append(ex.javaClass.simpleName).append(")\n")
        }
        return sb.toString()
    }

    private fun xmlValue(xml: String, tag: String): String =
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)?.trim() ?: ""

    private fun attr(block: String, name: String): String =
        Regex("$name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: "?"

    private fun jsonNumber(text: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1) ?: "?"

    private fun jsonObject(text: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\\{([^}]*)\\}").find(text)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.trim() ?: "?"

    /** The surya entry of perfinit's dex2oat table (def/a/bg values). */
    private fun suryaDex2oat(text: String): String {
        val start = text.indexOf("\"surya\"")
        if (start < 0) {
            return "?"
        }
        val end = text.indexOf('}', text.indexOf('}', start) + 1)
        if (end <= start) {
            return "?"
        }
        return text.substring(start, end)
            .replace(Regex("\\s+"), " ")
            .replace("\"product_name\": [", "products: [")
            .take(180)
    }

    private fun valueOf(path: String): String =
        shell("cat $path 2> /dev/null").trim().ifEmpty { "?" }

    private fun shell(command: String): String = try {
        KeepShellPublic.doCmdSync(command)
    } catch (ex: Exception) {
        ""
    }
}
