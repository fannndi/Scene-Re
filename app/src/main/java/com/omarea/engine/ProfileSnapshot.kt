package com.omarea.engine

import android.content.Context
import com.omarea.engine.RootShell
import com.omarea.util.PlatformUtils
import org.json.JSONObject

/**
 * Snapshots the live kernel CPU state into one profile of the user tuning JSON.
 *
 * Used while the profile engine is OFF: the user tunes live through the CPU
 * control screen, then saves that state into a mode card.
 *
 * Responsibility: read live state → write user tuning JSON.
 * Non-goals: applying profiles (ProfileController).
 */
object ProfileSnapshot {

    /** Saves the live state into [mode]; legacy ids are canonicalized. */
    fun save(context: Context, mode: String): Result<Unit> = try {
        val platform = PlatformUtils().getCPUName()
        val json = TuningRepository.read(context, platform)
            ?: throw IllegalStateException("tuning.json not found")

        val profiles = json.optJSONObject("profiles") ?: JSONObject()
        val canonicalMode = ProfileKey.canonical(mode)
        val profile = profiles.optJSONObject(canonicalMode)
            ?: JSONObject().also { profiles.put(canonicalMode, it) }

        // Governor / min / max per policy.
        val cpu = JSONObject()
        for (policy in DeviceCaps.POLICIES) {
            val node = com.omarea.engine.ShellNodes.cpufreq(policy)
            cpu.put(policy, JSONObject().apply {
                put("governor", RootShell.read("$node/scaling_governor"))
                put("min", RootShell.read("$node/scaling_min_freq").toLongOrNull() ?: 0L)
                put("max", RootShell.read("$node/scaling_max_freq").toLongOrNull() ?: 0L)
            })
        }
        profile.put("cpu", cpu)

        // Cores online.
        val online = CpuSet.parse(RootShell.read("/sys/devices/system/cpu/online"))
        val coreCount = RootShell.run("ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | wc -l")
            .trim().toIntOrNull() ?: 8
        val coresOnline = JSONObject()
        for (cpuIndex in 0 until coreCount) {
            coresOnline.put("$cpuIndex", if (online.contains(cpuIndex)) 1 else 0)
        }
        profile.put("cores_online", coresOnline)

        profiles.put(canonicalMode, profile)
        profiles.remove(ProfileKey.LEGACY_FAST)
        json.put("profiles", profiles)

        if (!TuningRepository.writeUser(platform, json.toString(4))) {
            throw IllegalStateException("cannot write tuning.json")
        }
        Result.success(Unit)
    } catch (ex: Exception) {
        Result.failure(ex)
    }
}
