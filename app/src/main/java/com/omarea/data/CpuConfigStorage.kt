package com.omarea.data

import android.content.Context
import com.omarea.common.shared.ObjectStorage
import com.omarea.common.shell.KeepShellPublic
import com.omarea.util.CpuFrequencyUtils
import com.omarea.data.CpuStatus
import java.io.File

/**
 * Persistent "apply on boot" CPU configuration (a single default file).
 *
 * Saved from the CPU control screen when "apply on boot" is checked and
 * re-applied by BootWorker. Profile modes are not stored here — they live in
 * the tuning JSON (see com.omarea.engine.ProfileController).
 *
 * Responsibility: store/apply the manual CPU state.
 * Non-goals: profile engine modes.
 */
class CpuConfigStorage(context: Context) : ObjectStorage<CpuStatus>(context) {
    private val defaultFile = "cpuconfig.dat"

    fun load(): CpuStatus? = super.load(defaultFile)

    fun saveCpuConfig(status: CpuStatus?): Boolean {
        remove("$defaultFile.sh")
        return super.save(status, defaultFile)
    }

    /** Applies the cached shell script, regenerating it from [load] when missing. */
    fun applyCpuConfig() {
        val cache = getSaveDir("$defaultFile.sh")
        if (File(cache).exists()) {
            KeepShellPublic.doCmdSync(cache)
            return
        }
        load()?.let { status ->
            val commands = CpuFrequencyUtils().buildShell(status).joinToString("\n")
            File(cache).apply {
                writeText(commands, Charsets.UTF_8)
                setWritable(true)
                setExecutable(true, false)
                setReadable(true)
            }
            KeepShellPublic.doCmdSync(commands)
        }
    }
}
