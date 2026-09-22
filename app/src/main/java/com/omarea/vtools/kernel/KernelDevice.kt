package com.omarea.vtools.kernel

import android.os.Build
import com.omarea.common.shell.KeepShellPublic
import com.omarea.library.shell.PlatformUtils
import com.omarea.library.shell.PropsUtils

/**
 * Read-only device/kernel identity shown on the Device tab.
 *
 * Values come from the framework where possible and from /proc otherwise, so only the kernel
 * version, RAM total and WireGuard probe depend on the shell tier.
 */
data class KernelDeviceInfo(
    val model: String,
    val codename: String,
    val manufacturer: String,
    val soc: String,
    val socModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val kernelVersion: String,
    val kernelFullVersion: String,
    val ramTotalBytes: Long,
    val gpuModel: String,
    val wireGuardAvailable: Boolean,
    val wireGuardVersion: String
)

object KernelDeviceInfoReader {
    private const val PROC_VERSION = "/proc/version"
    private const val MEMINFO = "/proc/meminfo"
    private const val WIREGUARD_VERSION = "/sys/module/wireguard/version"
    private const val GPU_MODEL = "/sys/class/kgsl/kgsl-3d0/gpu_model"

    fun load(): KernelDeviceInfo {
        val fullKernelVersion = KernelShell.readAll(PROC_VERSION)
        val wireGuardVersion = KernelShell.read(WIREGUARD_VERSION)
        return KernelDeviceInfo(
            model = Build.MODEL.orEmpty(),
            codename = Build.DEVICE.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            soc = PlatformUtils().getCPUName().orEmpty(),
            socModel = PropsUtils.getProp("ro.soc.model").trim(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            kernelVersion = parseKernelRelease(fullKernelVersion),
            kernelFullVersion = fullKernelVersion,
            ramTotalBytes = readRamTotalBytes(),
            gpuModel = readGpuModel(),
            wireGuardAvailable = wireGuardVersion.isNotEmpty(),
            wireGuardVersion = wireGuardVersion
        )
    }

    /** `Linux version 4.14.190-perf-g1234 (builder@host) ...` -> `4.14.190-perf-g1234`. */
    private fun parseKernelRelease(fullVersion: String): String {
        val parts = fullVersion.split(Regex("\\s+"))
        if (parts.size >= 3 && parts[0] == "Linux" && parts[1] == "version") {
            return parts[2]
        }
        return fullVersion.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun readRamTotalBytes(): Long {
        val meminfo = KernelShell.readAll(MEMINFO)
        for (line in meminfo.lines()) {
            if (!line.startsWith("MemTotal:")) {
                continue
            }
            val value = line.substringAfter("MemTotal:").trim().split(Regex("\\s+")).firstOrNull()
            val kilobytes = value?.toLongOrNull() ?: continue
            return kilobytes * 1024L
        }
        return 0L
    }

    private fun readGpuModel(): String {
        val fromKgsl = KernelShell.read(GPU_MODEL)
        if (fromKgsl.isNotEmpty()) {
            return fromKgsl
        }
        // Framework fallback: works with Shizuku even when /sys/class/kgsl is denied.
        val gles = try {
            KeepShellPublic.doCmdSync("dumpsys SurfaceFlinger 2>/dev/null | grep -m1 \"GLES:\"")
        } catch (ex: Exception) {
            ""
        }
        val line = gles.lineSequence().firstOrNull { it.contains("GLES:") } ?: return ""
        val afterTag = line.substringAfter("GLES:", "").trim()
        if (afterTag.isEmpty()) {
            return ""
        }
        val fields = afterTag.split(',')
        return if (fields.size >= 2) fields[1].trim() else afterTag
    }
}
