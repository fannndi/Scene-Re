package com.omarea.vtools.kernel

/**
 * ZRAM and network tuning values, ported from RvKernel-Manager (GPL-3.0, Rve27).
 *
 * ZRAM reconfiguration is a multi-step kernel sequence (swapoff, reset, size, mkswap, swapon), so
 * it is executed as one script and verified by reading the node back.
 */
data class ZramInfo(
    val available: Boolean,
    val disksizeBytes: Long,
    val compAlgorithm: String,
    val availableCompAlgorithms: List<String>
)

data class TcpInfo(
    val available: Boolean,
    val congestionControl: String,
    val availableAlgorithms: List<String>
)

object KernelMemory {
    private const val ZRAM_DEVICE = "/dev/block/zram0"
    private const val ZRAM_RESET = "/sys/block/zram0/reset"
    private const val ZRAM_DISKSIZE = "/sys/block/zram0/disksize"
    private const val ZRAM_COMP_ALGORITHM = "/sys/block/zram0/comp_algorithm"

    const val SWAPPINESS = "/proc/sys/vm/swappiness"

    private const val TCP_CONGESTION = "/proc/sys/net/ipv4/tcp_congestion_control"
    private const val TCP_AVAILABLE = "/proc/sys/net/ipv4/tcp_available_congestion_control"

    const val MIN_ZRAM_BYTES = 256L * 1024 * 1024
    const val MAX_ZRAM_BYTES = 8L * 1024 * 1024 * 1024

    private val ALGORITHM_REGEX = Regex("^[A-Za-z0-9_]{1,32}$")
    private val CURRENT_ALGORITHM_REGEX = Regex("\\[([^\\]]+)]")

    fun loadZram(): ZramInfo {
        val values = KernelShell.readMany(listOf(ZRAM_DISKSIZE, ZRAM_COMP_ALGORITHM))
        val disksize = values[ZRAM_DISKSIZE].orEmpty().toLongOrNull() ?: 0L
        val algorithmLine = values[ZRAM_COMP_ALGORITHM].orEmpty()
        val current = CURRENT_ALGORITHM_REGEX.find(algorithmLine)?.groupValues?.get(1).orEmpty()
        val available = disksize > 0L || algorithmLine.isNotEmpty()
        return ZramInfo(
            available = available,
            disksizeBytes = disksize,
            compAlgorithm = current,
            availableCompAlgorithms = algorithmLine
                .replace("[", "").replace("]", "")
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() && ALGORITHM_REGEX.matches(it) }
        )
    }

    fun setZramSize(sizeBytes: Long): Boolean {
        if (sizeBytes < MIN_ZRAM_BYTES || sizeBytes > MAX_ZRAM_BYTES) {
            return false
        }
        val script = buildString {
            append("swapoff ").append(ZRAM_DEVICE).append(" 2>/dev/null\n")
            append("echo 1 > ").append(ZRAM_RESET).append(" 2>/dev/null\n")
            append("echo ").append(sizeBytes).append(" > ").append(ZRAM_DISKSIZE).append(" 2>/dev/null\n")
            append("mkswap ").append(ZRAM_DEVICE).append(" >/dev/null 2>&1\n")
            append("swapon ").append(ZRAM_DEVICE).append(" 2>/dev/null\n")
        }
        return KernelShell.writeSequence(script, ZRAM_DISKSIZE, sizeBytes.toString())
    }

    fun setZramCompAlgorithm(algorithm: String, currentSizeBytes: Long): Boolean {
        if (!ALGORITHM_REGEX.matches(algorithm) || currentSizeBytes <= 0L) {
            return false
        }
        val script = buildString {
            append("swapoff ").append(ZRAM_DEVICE).append(" 2>/dev/null\n")
            append("echo 1 > ").append(ZRAM_RESET).append(" 2>/dev/null\n")
            append("echo ").append(algorithm).append(" > ").append(ZRAM_COMP_ALGORITHM).append(" 2>/dev/null\n")
            append("echo ").append(currentSizeBytes).append(" > ").append(ZRAM_DISKSIZE).append(" 2>/dev/null\n")
            append("mkswap ").append(ZRAM_DEVICE).append(" >/dev/null 2>&1\n")
            append("swapon ").append(ZRAM_DEVICE).append(" 2>/dev/null\n")
        }
        return KernelShell.writeSequence(script, ZRAM_DISKSIZE, currentSizeBytes.toString())
    }

    fun loadTcp(): TcpInfo {
        val values = KernelShell.readMany(listOf(TCP_CONGESTION, TCP_AVAILABLE))
        val current = values[TCP_CONGESTION].orEmpty()
        val available = values[TCP_AVAILABLE].orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return TcpInfo(
            available = current.isNotEmpty() || available.isNotEmpty(),
            congestionControl = current,
            availableAlgorithms = available
        )
    }

    fun setTcpCongestion(algorithm: String): Boolean {
        if (!ALGORITHM_REGEX.matches(algorithm)) {
            return false
        }
        return KernelShell.write(TCP_CONGESTION, algorithm)
    }

    fun readSwappiness(): String = KernelShell.read(SWAPPINESS)

    fun writeSwappiness(value: String): Boolean {
        if (!KernelShell.isNumeric(value)) {
            return false
        }
        val numeric = value.toIntOrNull() ?: return false
        if (numeric > 100) {
            return false
        }
        return KernelShell.write(SWAPPINESS, value)
    }
}
