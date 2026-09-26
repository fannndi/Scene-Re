package com.omarea.library.shell

import android.content.Context
import android.util.Log
import com.omarea.common.shared.FileWrite
import com.omarea.common.shell.*
import com.omarea.model.ZramWriteBackStat
import java.io.File

/**
 * Created by Hello on 2017/11/01.
 */

class SwapUtils(private val context: Context) {
    private var swapfilePath: String = "/data/swapfile"
    private var swapControlScript = FileWrite.writePrivateShellFile("addin/swap_control.sh", "addin/swap_control.sh", context)
    private var swapForceKswapdScript:String? = null
    // private var zramControlScript = FileWrite.writePrivateShellFile("addin/zram_control.sh", "addin/zram_control.sh", context)

    // Whether the swapfile has been created
    val swapExists: Boolean
        get() {
            return RootFile.itemExists(swapfilePath)
        }

    // swap currently activated by Scene
    val sceneSwaps: String
        get() {
            if (swapExists) {
                val ret = KernelProrp.getProp("/proc/swaps")
                val txt = ret.replace("\t\t", "\t").replace("\t", " ")
                if (txt.contains("/data/swapfile") || txt.contains("/swapfile")) {
                    return "/data/swapfile"
                } else {
                    val loopName = PropsUtils.getProp("vtools.swap.loop").split("/").lastOrNull()
                    if (loopName != null && loopName != "error" && txt.contains(loopName)) {
                        return loopName
                    }
                }
            }
            return ""
        }

    // Get the current swap size
    val swapFileSize: Int
        get() {
            if (swapExists) {

                var size = 0L
                try {
                    size = KeepShellPublic.doCmdSync("ls -l /data/swapfile | awk '{ print \$5 }'").toLong()
                } catch (ex: Exception) {
                    try {
                        size = File("/data/swapfile").length()
                    } catch (ex: Exception) {

                    }
                }
                return (size / 1024 / 1024).toInt()
            }
            return 0
        }

    // Create swap
    fun mkswap(size: Int) {
        val sb = StringBuilder()
        sb.append("swapoff $swapfilePath >/dev/null 2>&1;\n")
        sb.append("dd if=/dev/zero of=$swapfilePath bs=1048576 count=$size;\n")
        val keepShell = KeepShell()
        keepShell.doCmdSync(sb.toString())
        keepShell.tryExit()
    }

    // Start swap
    fun swapOn(priority: Int, useLoop: Boolean = false, keepShell: KeepShell): String {
        val sb = StringBuilder()

        sb.append("sh ")
        sb.append(swapControlScript)
        sb.append(" enable_swap ")
        if (useLoop) {
            sb.append("1")
        } else {
            sb.append("0")
        }
        if (priority > -2) {
            sb.append(" ")
            sb.append(priority)
        }

        return ShellTranslation(context).resolveRow(
            keepShell.doCmdSync(sb.toString())
        )
    }

    // Start swap
    fun swapOn(priority: Int, useLoop: Boolean = false): String {
        val keepShell = KeepShell()

        val result = swapOn(priority, useLoop, keepShell)

        keepShell.tryExit()
        return result
    }

    // Stop swap
    fun swapOff() {
        val sb = StringBuilder("sync\necho 3 > /proc/sys/vm/drop_caches\n")

        sb.append("sh ")
        sb.append(swapControlScript)
        sb.append(" disable_swap ")
        if (sceneSwaps.contains("loop")) {
            sb.append("1")
        } else {
            sb.append("0")
        }

        val keepShell = KeepShell()
        keepShell.doCmdSync(sb.toString())
        keepShell.tryExit()
    }

    // Delete the swap file
    fun swapDelete() {
        val sb = StringBuilder("sync\necho 3 > /proc/sys/vm/drop_caches\n")

        sb.append("sh ")
        sb.append(swapControlScript)
        sb.append(" disable_swap ")
        if (sceneSwaps.contains("loop")) {
            sb.append("1")
        } else {
            sb.append("0")
        }

        sb.append("\nrm -f $swapfilePath")

        val keepShell = KeepShell()
        keepShell.doCmdSync(sb.toString())
        keepShell.tryExit()
    }

    // Whether zram is supported
    val zramSupport: Boolean
        get() {
            return KeepShellPublic.doCmdSync(
                    "if [[ ! -e /dev/block/zram0 ]] && [[ -e /sys/class/zram-control ]]; then\n" +
                    "  cat /sys/class/zram-control/hot_add\n" +
                    "fi\n" +
                    "if [[ -e /dev/block/zram0 ]]; then echo 1; else echo 0; fi;") == "1"
        }

    // Whether zram WriteBack is supported
    val zramWriteBackSupport: Boolean
        get() {
            return KeepShellPublic.doCmdSync("if [[ -e /sys/block/zram0/backing_dev ]]; then echo 1; else echo 0; fi;") == "1"
        }

    // Whether zram is enabled
    val zramEnabled: Boolean
        get() {
            return KeepShellPublic.doCmdSync("cat /proc/swaps | grep /block/zram0").contains("/block/zram0")
        }

    // Disable zram
    fun zramOff() {
        val sb = StringBuilder("sync\necho 3 > /proc/sys/vm/drop_caches\n")

        sb.append("swapoff /dev/block/zram0\n")
        val keepShell = KeepShell()
        keepShell.doCmdSync(sb.toString())
        keepShell.tryExit()
    }

    // Resize zram
    fun resizeZram(sizeVal: Int, algorithm: String = "") {
        val keepShell = KeepShell()
        val currentSize = zramCurrentSizeMB
        if (currentSize != sizeVal || (algorithm.isNotEmpty() && algorithm != compAlgorithm) || !zramEnabled) {
            val sb = StringBuilder()
            sb.append("echo 4 > /sys/block/zram0/max_comp_streams\n")
            sb.append("sync\n")

            sb.append("if [[ -f /sys/block/zram0/backing_dev ]]; then\n")
            sb.append("  backing_dev=$(cat /sys/block/zram0/backing_dev)\n")
            sb.append("fi\n")

            sb.append("echo 3 > /proc/sys/vm/drop_caches\n")
            sb.append("swapoff /dev/block/zram0 >/dev/null 2>&1\n")
            sb.append("echo 1 > /sys/block/zram0/reset\n")

            if (algorithm.isNotEmpty()) {
                sb.append("echo \"$algorithm\" > /sys/block/zram0/comp_algorithm\n")
            }

            sb.append("if [[ -f /sys/block/zram0/backing_dev ]]; then\n")
            sb.append("  echo \"\$backing_dev\" > /sys/block/zram0/backing_dev\n")
            sb.append("fi\n")

            if (sizeVal > 2047) {
                sb.append("echo " + sizeVal + "M > /sys/block/zram0/disksize\n")
            } else {
                sb.append("echo " + (sizeVal * 1024 * 1024L) + " > /sys/block/zram0/disksize\n")
            }

            sb.append("echo 4 > /sys/block/zram0/max_comp_streams\n")
            sb.append("mkswap /dev/block/zram0 >/dev/null 2>&1\n")
            sb.append("swapon /dev/block/zram0 -p 0 >/dev/null 2>&1\n")
            keepShell.doCmdSync(sb.toString())
        }

        keepShell.tryExit()
    }

    // ZRAM write-back status
    val writeBackStat: ZramWriteBackStat
        get () {
            return ZramWriteBackStat().apply {
                backingDev = KernelProrp.getProp("/sys/block/zram0/backing_dev")
                val bdStats = KernelProrp.getProp("/sys/block/zram0/bd_stat").trim().split(Regex("[ ]+"))
                if (bdStats.size == 3) {
                    backed = bdStats[0].toInt() * 4
                    backReads = bdStats[1].toInt() * 4
                    backWrites = bdStats[2].toInt() * 4
                }
            }
        }

    val zramCurrentSizeMB: Int
        get () {
            val currentSize = KeepShellPublic.doCmdSync("cat /sys/block/zram0/disksize")
            try {
                return (currentSize.toLong() / 1024 /1024).toInt()
            } catch (ex: java.lang.Exception) {
                return 0
            }
        }

    // Get the available ZRAM compression algorithms
    val compAlgorithmOptions: Array<String>
        get() {
            val compAlgorithmItems = KernelProrp.getProp("/sys/block/zram0/comp_algorithm").split(" ")
            return compAlgorithmItems.map {
                it.replace("[", "").replace("]", "")
            }.toTypedArray()
        }

    // Get the ZRAM compression algorithm currently in use
    var compAlgorithm: String
        get() {
            val compAlgorithmItems = KernelProrp.getProp("/sys/block/zram0/comp_algorithm").split(" ")
            val result = compAlgorithmItems.find {
                it.startsWith("[") && it.endsWith("]")
            }
            if (result != null) {
                return result.replace("[", "").replace("]", "").trim()
            }
            return ""
        }
        set(value) {
            KeepShellPublic.doCmdSync("echo 1 > /sys/block/zram0/reset")
            KernelProrp.setProp("/sys/block/zram0/comp_algorithm", value)
        }

    // Force memory reclaim
    // level    0: barely    1: light    2: heavier    3: extreme
    fun forceKswapd(level: Int): String {
        if (swapForceKswapdScript == null) {
            swapForceKswapdScript = FileWrite.writePrivateShellFile("addin/force_compact.sh", "addin/force_compact.sh", context)
            KeepShellPublic.doCmdSync("rm /cache/force_compact.log 2>/dev/null")
        }

        // Snapshot the field: it is a mutable property, so a null-check followed by a
        // read can observe two different values if another thread writes in between.
        val script = swapForceKswapdScript
        if (script != null) {
            return KeepShellPublic.getInstance("swap-clear", true)
                .doCmdSync(ShellEscape.cmd("sh", script, level.toString()))
        }
        return "Fail!"
    }

    val procSwaps: MutableList<String>
        get() {
            val ret = KernelProrp.getProp("/proc/swaps")
            var txt = ret.replace("\t\t", "\t").replace("\t", " ")
            while (txt.contains("  ")) {
                txt = txt.replace("  ", " ")
            }
            val rows = txt.split("\n").toMutableList()
            return rows
        }

    val swapUsedSize: Int
        get() {
            var loopName: String? = PropsUtils.getProp("vtools.swap.loop").split("/").lastOrNull()
            if (loopName != null && !loopName.contains("loop")) {
                loopName = null
            }
            for (row in procSwaps) {
                if (row.startsWith("/swapfile ") || row.startsWith("/data/swapfile ") || (loopName != null && row.contains(loopName))) {
                    val cols = row.split(" ").toMutableList()
                    val usedStr = cols[3]

                    try {
                        return usedStr.toInt() / 1024
                    } catch (ex: java.lang.Exception) {
                        break
                    }
                }
            }
            return -1
        }

    val zramUsedSize: Int
        get() {
            for (row in procSwaps) {
                if (row.startsWith("/block/zram0 ") || row.startsWith("/dev/block/zram0 ")) {
                    val cols = row.split(" ").toMutableList()
                    val usedStr = cols[3]

                    try {
                        return usedStr.toInt() / 1024
                    } catch (ex: java.lang.Exception) {
                        break
                    }
                }
            }
            return -1
        }

    val zramPriority: Int?
        get() {
            for (row in procSwaps) {
                if (row.startsWith("/block/zram0 ") || row.startsWith("/dev/block/zram0 ")) {
                    val cols = row.split(" ").toMutableList()

                    try {
                        return cols[4].toInt()
                    } catch (ex: java.lang.Exception) {
                        break
                    }
                }
            }
            return null
        }
}
