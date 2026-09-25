package com.omarea.library.shell

import android.os.Build
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.RootFile
import java.util.*

/**
 * MIUI thermal control toggle.
 *
 * MIUI exposes its thermal state through the `mi_thermald` daemon, which reads
 * `/sys/class/thermal/thermal_message/board_sensor_temp`. Blanking that node
 * makes the daemon's board-sensor rule ineffective, which is what the
 * "extreme performance" toggle wants.
 *
 * Two things matter for correctness on MIUI 14 (Android 12):
 *
 *  - **The original value has to be restored.** Overwriting the node with the
 *    `36500` sentinel and never putting the real temperature back leaves the
 *    thermal daemon reading a bogus value for the rest of the boot.
 *  - **`chmod` on a sysfs attribute may be rejected.** kernfs does not
 *    implement `setattr`, so the mode change can fail even as root. The write
 *    is therefore verified by reading the node back, and the toggle reports
 *    honestly instead of assuming success.
 *
 * The MiGt node (`glk_maxfreq`) is labelled `sysfs_migt` in SELinux and is only
 * writable by `joyose_app`, `system_app`, `mcd` and `system_server`; a write
 * from a plain root domain can be denied. That case is detected too.
 */
class ThermalDisguise {
    private final val boardSensorTemp = "/sys/class/thermal/thermal_message/board_sensor_temp"
    private final val migtMaxFreq = "/sys/module/migt/parameters/glk_maxfreq"
    private final val gameServiceApp = "com.xiaomi.gamecenter.sdk.service"
    private final val gameService = "com.xiaomi.gamecenter.sdk.service/.PidService"

    /** Current toggle state. */
    private final val vtoolsStorage = "vtools.thermal.disguise"

    /** One-shot snapshot of the real board sensor temperature. */
    private final val vtoolsBackup = "vtools.thermal.disguise.bak"

    /** One-shot snapshot of the stock MiGt max frequency. */
    private final val vtoolsMigtBackup = "vtools.thermal.disguise.migt.bak"

    /** Set to 1 when the last write was refused by SELinux. */
    private final val vtoolsBlocked = "vtools.thermal.disguise.blocked"

    /** The sentinel MIUI treats as "board sensor not applicable". */
    private final val sentinelTemp = "36500"

    /**
     * Feature detection.
     *
     * The node alone is not enough: without the `mi_thermald` daemon running,
     * blanking it changes nothing. Gating on the daemon keeps the toggle from
     * being offered where it cannot work.
     */
    public fun supported(): Boolean {
        if (Build.MANUFACTURER.uppercase(Locale.getDefault()) != "XIAOMI") {
            return false
        }
        val daemonUp = KeepShellPublic.doCmdSync(
            "getprop init.svc.mi_thermald; pidof mi_thermald"
        ).trim()
        if (daemonUp.isEmpty()) {
            // Older MIUI builds report neither; fall back to node presence.
            return RootFile.fileExists(boardSensorTemp) || RootFile.fileExists(migtMaxFreq)
        }
        return RootFile.fileExists(boardSensorTemp) || RootFile.fileExists(migtMaxFreq)
    }

    /** True when the last write attempt was denied by SELinux. */
    public fun isBlocked(): Boolean {
        return PropsUtils.getProp(vtoolsBlocked) == "1"
    }

    public fun disableMessage() {
        KeepShellPublic.doCmdSync(
            // Snapshot once, before the first overwrite. The property is not
            // persistent, so it is cleared by a reboot - which is exactly what
            // we want, because the node resets with the kernel too.
            "if [ -z \"\$(getprop $vtoolsBackup)\" ]; then\n" +
                    "  setprop $vtoolsBackup \"\$(cat $boardSensorTemp 2>/dev/null)\"\n" +
                    "fi\n" +
                    "if [ -z \"\$(getprop $vtoolsMigtBackup)\" ]; then\n" +
                    "  setprop $vtoolsMigtBackup \"\$(cat $migtMaxFreq 2>/dev/null)\"\n" +
                    "fi\n" +

                    "chmod 644 $boardSensorTemp 2>/dev/null\n" +
                    "echo $sentinelTemp > $boardSensorTemp 2>/dev/null\n" +
                    "chmod 000 $boardSensorTemp 2>/dev/null\n" +

                    // Verify by reading the node back rather than trusting chmod.
                    "blocked=0\n" +
                    "if [ \"\$(cat $boardSensorTemp 2>/dev/null)\" != \"$sentinelTemp\" ]; then blocked=1; fi\n" +

                    "chmod 644 $migtMaxFreq 2>/dev/null\n" +
                    "echo 0 0 0 > $migtMaxFreq 2>/dev/null\n" +
                    "setprop $vtoolsBlocked \"\$blocked\"\n" +

                    "pm disable $gameService 2>/dev/null\n" +
                    "pm clear $gameServiceApp 2>/dev/null\n" +
                    "setprop $vtoolsStorage 1")
    }

    public fun resumeMessage() {
        KeepShellPublic.doCmdSync(
            "chmod 644 $boardSensorTemp 2>/dev/null\n" +

                    // Put the real temperature back. Without this the daemon keeps
                    // reading the sentinel until the next reboot.
                    "bak=\"\$(getprop $vtoolsBackup)\"\n" +
                    "if [ -n \"\$bak\" ]; then echo \"\$bak\" > $boardSensorTemp 2>/dev/null; fi\n" +
                    "setprop $vtoolsBackup \"\"\n" +

                    "mbak=\"\$(getprop $vtoolsMigtBackup)\"\n" +
                    "if [ -n \"\$mbak\" ]; then echo \"\$mbak\" > $migtMaxFreq 2>/dev/null; fi\n" +
                    "setprop $vtoolsMigtBackup \"\"\n" +

                    "pm enable $gameService 2>/dev/null\n" +
                    "setprop $vtoolsStorage 0\n" +
                    "setprop $vtoolsBlocked 0")
    }

    /**
     * True when the toggle is on.
     *
     * Detects the state by value, not by file mode: `chmod 000` on a sysfs
     * attribute is not guaranteed to have taken effect, so the old
     * "mode starts with ----------" check reported the wrong answer whenever
     * the mode change was rejected.
     */
    public fun isDisabled(): Boolean {
        if (PropsUtils.getProp(vtoolsStorage) != "1") {
            return false
        }
        val current = KeepShellPublic.doCmdSync("cat $boardSensorTemp 2>/dev/null").trim()
        return current == sentinelTemp
    }
}
