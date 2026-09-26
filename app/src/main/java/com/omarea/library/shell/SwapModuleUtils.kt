package com.omarea.library.shell

import android.content.SharedPreferences
import com.omarea.common.shared.RootBackend
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import com.omarea.common.shell.ShellEscape
import com.omarea.store.SpfConfig

/*
# Configuration example

# Enable swap
swap=true
# swap size (MB); on some devices values above 2047 fail to start
swap_size=1536
# swap usage order (0: use together with zram, -1: use after zram is exhausted, 5: use before zram)
swap_priority=0
# Whether to mount as a loop device (not recommended unless necessary)
swap_use_loop=false

# Enable zram
zram=true
# zram size (MB); on some devices values above 2047 fail to start
zram_size=1536
# zram compression algorithm (the settable values depend on kernel support)
comp_algorithm=lzo

# Willingness to use zram/swap
swappiness=100
# extra_free_kbytes(kbytes)
extra_free_kbytes=98304
*/

/**
 * Persists the swap/zram configuration so it can be re-applied on boot.
 *
 * <h3>History</h3>
 *
 * This used to delegate to a third-party module installed on the device: it read and wrote
 * `/data/swap_config.conf` and required the `vtools.swap.controller` property to equal
 * `"magisk"` before it would do anything. That made the whole swap feature unavailable
 * unless that particular module was installed, even though the app performs the swap itself
 * through [SwapUtils] and re-applies it on boot through `BootWorker`.
 *
 * <p>The configuration file is now owned by Scene and lives in its own state directory. The
 * on-disk format is unchanged, so a configuration written by an older install is still read
 * correctly — only the location and the ownership check differ.
 *
 * <p>Nothing here requires any particular root manager; it needs only root, because the
 * config has to be readable by the boot worker before the app is unlocked.
 */
class SwapModuleUtils {
    private var configAvailable: Boolean? = null

    /** Whether Scene's own swap config file exists and can be read. */
    val configReady: Boolean
        get() {
            if (configAvailable == null) {
                configAvailable = RootFile.fileExists(CONFIG_PATH)
            }
            return configAvailable!!
        }

    private val swapEnable = "swap"
    private val swapSize = "swap_size"
    private val swapPriority = "swap_priority"
    private val swapUseLoop = "swap_use_loop"

    private val zramEnable = "zram"
    private val zramSize = "zram_size"
    private val zramCompAlgorithm = "comp_algorithm"

    private val swappiness = "swappiness"
    private val extraFreeKbytes = "extra_free_kbytes"
    private val watermarkScaleFactor = "watermark_scale_factor"

    // The property name must be a plain identifier, otherwise it cannot be safely interpolated into the sed expression
    private fun isValidPropName(prop: String) = prop.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))

    /**
     * Best zram compression algorithm the running kernel advertises, preferring
     * zstd (best ratio) over lz4 (fastest) over lzo (the historical default).
     *
     * msm-4.14 ships all three on surya, so hard-coding `lzo` threw away memory
     * for nothing. The node lists the compiled-in algorithms with the active one
     * in brackets, e.g. `lzo lz4 [zstd]`; either form counts as available.
     */
    fun bestZramAlgorithm(): String {
        val available = KeepShellPublic.doCmdSync(
            "cat /sys/block/zram0/comp_algorithm 2>/dev/null"
        )
        if (available.isBlank()) {
            return "lzo"
        }
        val names = available.replace("[", " ").replace("]", " ").split(" ")
        for (candidate in listOf("zstd", "lz4", "lzo")) {
            if (names.contains(candidate)) {
                return candidate
            }
        }
        return "lzo"
    }

    private fun getProp(prop: String): String {
        if (!isValidPropName(prop)) {
            return ""
        }
        return KeepShellPublic.doCmdSync(
            "grep -F " + ShellEscape.quote("$prop=") + " " + ShellEscape.quote(CONFIG_PATH) + " | grep -v -F '#' | cut -f2 -d '='"
        )
    }

    private fun getProp(config: List<String>, prop: String): String {
        val result = config.find { it.startsWith("$prop=") }
        if (result != null) {
            return result.subSequence(prop.length + 1, result.length).toString()
        }
        return ""
    }

    private fun setProp(prop: String, value: Any) {
        if (!isValidPropName(prop)) {
            return
        }
        // The value may be a user-entered algorithm name, so escape everything before passing it to sed
        val safeValue = ShellEscape.quote(value.toString())
        // The file may not exist yet on a fresh install, so create it first;
        // otherwise sed silently does nothing and the setting is lost.
        KeepShellPublic.doCmdSync(
            "touch " + ShellEscape.quote(CONFIG_PATH) + "\n" +
                    "busybox sed -i " + ShellEscape.quote("s/^$prop=.*/$prop=$safeValue/") + " " + ShellEscape.quote(CONFIG_PATH)
        )
    }

    /** Persist the current settings so the boot worker can re-apply them. */
    fun saveModuleConfig(spf: SharedPreferences) {
        ensureConfigDir()
        // Append any missing keys first, so sed has a line to replace.
        ensureKeys()

        setProp(swapEnable, spf.getBoolean(SpfConfig.SWAP_SPF_SWAP, false))
        setProp(swapSize, spf.getInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, 0))
        setProp(swapPriority, spf.getInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, 0))
        setProp(swapUseLoop, spf.getBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, false))

        setProp(zramEnable, spf.getBoolean(SpfConfig.SWAP_SPF_ZRAM, false))
        setProp(zramSize, spf.getInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, 0))
        setProp(zramCompAlgorithm, "" + spf.getString(SpfConfig.SWAP_SPF_ALGORITHM, bestZramAlgorithm()))

        setProp(swappiness, spf.getInt(SpfConfig.SWAP_SPF_SWAPPINESS, 65))
        setProp(extraFreeKbytes, spf.getInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, 29615))
        setProp(watermarkScaleFactor, spf.getInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, 100))

        configAvailable = true
    }

    /** Load previously saved settings into the preference store. */
    fun loadModuleConfig(spf: SharedPreferences) {
        if (!configReady) {
            return
        }

        val editor = spf.edit()
        val savedConfig = KeepShellPublic.doCmdSync("cat " + ShellEscape.quote(CONFIG_PATH)).split("\n")

        try {
            editor.putBoolean(SpfConfig.SWAP_SPF_SWAP, getProp(savedConfig, swapEnable) == "true")
            editor.putInt(SpfConfig.SWAP_SPF_SWAP_SWAPSIZE, getProp(savedConfig, swapSize).toInt())
            editor.putInt(SpfConfig.SWAP_SPF_SWAP_PRIORITY, getProp(savedConfig, swapPriority).toInt())
            editor.putBoolean(SpfConfig.SWAP_SPF_SWAP_USE_LOOP, getProp(savedConfig, swapUseLoop) == "true")
        } catch (ex: Exception) {
        }

        try {
            editor.putBoolean(SpfConfig.SWAP_SPF_ZRAM, getProp(savedConfig, zramEnable) == "true")
            editor.putInt(SpfConfig.SWAP_SPF_ZRAM_SIZE, getProp(savedConfig, zramSize).toInt())
            editor.putString(SpfConfig.SWAP_SPF_ALGORITHM, getProp(savedConfig, zramCompAlgorithm))
        } catch (ex: Exception) {
        }

        try {
            editor.putInt(SpfConfig.SWAP_SPF_SWAPPINESS, getProp(savedConfig, swappiness).toInt())
            editor.putInt(SpfConfig.SWAP_SPF_EXTRA_FREE_KBYTES, getProp(savedConfig, extraFreeKbytes).toInt())
        } catch (ex: Exception) {
        }
        try {
            editor.putInt(SpfConfig.SWAP_SPF_WATERMARK_SCALE, getProp(savedConfig, watermarkScaleFactor).toInt())
        } catch (ex: Exception) {
        }

        editor.apply()
    }

    private fun ensureConfigDir() {
        KeepShellPublic.doCmdSync("mkdir -p " + ShellEscape.quote(RootBackend.stateDir()))
    }

    /**
     * Append every key that is not already present, so a first-run config file is complete
     * and the subsequent `sed` replacements all match a real line.
     */
    private fun ensureKeys() {
        val keys = listOf(
            swapEnable to "false",
            swapSize to "0",
            swapPriority to "0",
            swapUseLoop to "false",
            zramEnable to "false",
            zramSize to "0",
            zramCompAlgorithm to bestZramAlgorithm(),
            swappiness to "65",
            extraFreeKbytes to "29615",
            watermarkScaleFactor to "100"
        )
        val sb = StringBuilder("touch ").append(ShellEscape.quote(CONFIG_PATH)).append("\n")
        for ((key, default) in keys) {
            // `grep -q` guards against duplicating a key that is already there.
            sb.append("grep -q ")
                .append(ShellEscape.quote("^$key="))
                .append(" ").append(ShellEscape.quote(CONFIG_PATH))
                .append(" || echo ").append(ShellEscape.quote("$key=$default"))
                .append(" >> ").append(ShellEscape.quote(CONFIG_PATH)).append("\n")
        }
        KeepShellPublic.doCmdSync(sb.toString())
    }

    companion object {
        /** Config file scene owns and reads back on boot. */
        const val CONFIG_PATH = "/data/adb/scene/swap.conf"
    }
}
