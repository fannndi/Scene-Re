package com.omarea.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.omarea.Scene
import com.omarea.benchmark.workload.BenchmarkWorkload
import com.omarea.benchmark.workload.CpuWorkload
import com.omarea.benchmark.workload.GpuWorkload
import com.omarea.benchmark.workload.IdleWorkload
import com.omarea.benchmark.workload.IoWorkload
import com.omarea.benchmark.workload.MixedWorkload
import com.omarea.benchmark.workload.ScrollWorkload
import com.omarea.benchmark.workload.VideoWorkload
import com.omarea.common.shell.KeepShellPublic
import com.omarea.data.BenchmarkStore
import com.omarea.engine.ProfileController
import com.omarea.engine.TuningRepository
import com.omarea.runtime.ModeSwitcher
import com.omarea.util.measure.DesignCapacity
import com.omarea.util.PlatformUtils
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark orchestrator (manual single target + automated multi-target suite).
 *
 * Responsibility: apply the target profile, gate on temperature, run each
 * scenario (warmup + measured window) while sampling 1 Hz into the export
 * bundle, honour abort rules, then restore the original engine state.
 *
 * Non-goals: UI (Stage/Listener callbacks), aggregation (BenchmarkMetrics),
 * report rendering (BenchmarkReport).
 */
class BenchmarkRunner(
    private val context: Context,
    private val config: BenchConfig,
    private val stage: Stage,
    private val listener: Listener
) {

    interface Stage {
        /** Attach the workload view on the main thread (blocks until done). */
        fun attach(workload: BenchmarkWorkload)

        /** Detach the current workload view on the main thread. */
        fun detach()

        /** Post a status line to the UI. */
        fun status(text: String)
    }

    interface Listener {
        fun onProgress(phase: String, detail: String)
        fun onSample(sample: BenchSample)
        fun onScenarioDone(target: String, summary: BenchmarkMetrics.Summary)
        fun onRunDone(run: BenchmarkReport.RunData)
        fun onSuiteDone(ok: Boolean, message: String, bundlePath: String)
    }

    @Volatile
    private var cancelled = false

    /** Design capacity + its source (recorded in the run meta). */
    private var designCapacityMah = 0
    private var designCapacitySource = "unknown"

    fun cancel() {
        cancelled = true
    }

    // ------------------------------------------------------------------ run
    fun run() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val exporter = BenchmarkExporter(context, stamp, config.mode)
        val sampler = BenchmarkSampler(context)
        val store = BenchmarkStore(context)

        val originalEngineOff = ProfileController.isEngineOff(context)
        // The prop is volatile; fall back to the persisted last mode (same
        // source BootWorker uses) so restore re-applies the real profile.
        val originalMode = ModeSwitcher.getCurrentPowerMode().ifEmpty {
            context.getSharedPreferences(com.omarea.data.SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
                .getString(com.omarea.data.SpfConfig.GLOBAL_SPF_LAST_MODE, "").orEmpty()
        }
        // Drain percentages depend on the capacity they divide by: prefer the
        // kernel's charge_full_design node over the ROM power profile.
        val (capMah, capSource) = DesignCapacity.resolve(context)
        designCapacityMah = capMah
        designCapacitySource = capSource
        val designCapacity = capMah.toDouble().takeIf { it > 100 }

        val brightness = fixBrightness()
        val runs = ArrayList<BenchmarkReport.RunData>()
        var abortReason: String? = null
        val baselineTemp = currentBatteryTemp()

        try {
            for (targetId in config.targets) {
                if (cancelled) {
                    abortReason = "cancelled"
                    break
                }
                val target = BenchTarget.byId(targetId) ?: continue
                if (!plugStateMatches()) {
                    abortReason = "plug state does not match ${config.mode} mode"
                    break
                }
                listener.onProgress("target", "Preparing ${target.label}")
                stage.status("Applying ${target.label}")
                applyTarget(target)
                waitForCooldown(baselineTemp)

                val runDir = exporter.runDir(target.id)
                val meta = buildMeta(target)
                exporter.writeMeta(runDir, meta)
                val writer = BenchmarkExporter.SampleWriter(runDir)
                val runStartedAt = System.currentTimeMillis()
                val runId = store.startRun(config.mode, target.id, meta["tuning_hash"] ?: "", runDir.absolutePath)

                val samples = ArrayList<BenchSample>()
                var scenarioBreak: String? = null

                for (scenario in config.scenarios) {
                    if (cancelled) {
                        scenarioBreak = "cancelled"
                        break
                    }
                    val workload = createWorkload(scenario)
                    listener.onProgress("warmup", "${target.id}/${scenario.id} warmup")
                    stage.attach(workload)
                    val scenarioSamples = ArrayList<BenchSample>()
                    try {
                        stage.status("${target.label} · ${scenario.label} · warmup ${config.warmupSeconds}s")
                        sleepSeconds(config.warmupSeconds.toLong())
                        workload.start()

                        val start = SystemClock.elapsedRealtime()
                        var lastTick = start
                        while (true) {
                            if (cancelled) {
                                scenarioBreak = "cancelled"
                                break
                            }
                            val tickStart = SystemClock.elapsedRealtime()
                            val elapsed = tickStart - start
                            if (elapsed >= config.measureSeconds * 1000L) break
                            val dt = (tickStart - lastTick).coerceAtLeast(200L)
                            lastTick = tickStart

                            val sample = sampler.sample(scenario, elapsed, dt, workload.workUnits())
                            writer.append(sample)
                            samples.add(sample)
                            scenarioSamples.add(sample)
                            listener.onSample(sample)
                            listener.onProgress(
                                "measure",
                                "${target.id}/${scenario.id} ${elapsed / 1000}s / ${config.measureSeconds}s"
                            )
                            stage.status("${target.label} · ${scenario.label} · ${elapsed / 1000}s/${config.measureSeconds}s · " +
                                "${sample.batteryMa ?: "?"}mA " +
                                "${sample.batteryTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}°C")

                            checkAbort(sample)?.let {
                                scenarioBreak = it
                                break
                            }
                            val spent = SystemClock.elapsedRealtime() - tickStart
                            if (spent < 1000L) Thread.sleep(1000L - spent)
                        }
                    } finally {
                        runCatching { workload.stop() }
                        stage.detach()
                    }

                    val summary = BenchmarkMetrics.summarize(scenarioSamples, config.mode, designCapacity)
                    store.insertScenario(runId, summary)
                    listener.onScenarioDone(target.id, summary)
                    if (scenarioBreak != null) {
                        abortReason = scenarioBreak
                        break
                    }
                }
                writer.close()

                val runData = BenchmarkReport.RunData(
                    mode = config.mode,
                    target = target.id,
                    startedAt = runStartedAt,
                    capacityStart = samples.firstNotNullOfOrNull { it.capacityPct },
                    capacityEnd = samples.asReversed().firstNotNullOfOrNull { it.capacityPct },
                    maxBatteryTempC = samples.mapNotNull { it.batteryTempC }.maxOrNull(),
                    tuningHash = meta["tuning_hash"],
                    summaries = samples.groupBy { it.scenario }.map { (_, list) ->
                        BenchmarkMetrics.summarize(list, config.mode, designCapacity)
                    },
                    aborted = abortReason != null,
                    abortReason = abortReason,
                    meta = meta
                )
                exporter.writeRunReport(runDir, runData)
                val totalMwh = runData.summaries.mapNotNull { it.mwh }.sum()
                store.finishRun(
                    runId, abortReason != null, abortReason ?: "",
                    runData.capacityStart ?: -1, runData.capacityEnd ?: -1,
                    totalMwh, runData.maxBatteryTempC ?: -1.0
                )
                runs.add(runData)
                listener.onRunDone(runData)
                if (abortReason != null) break
            }
        } catch (ex: Exception) {
            abortReason = ex.message ?: "error"
        } finally {
            restoreState(originalEngineOff, originalMode)
            restoreBrightness(brightness)
            exporter.writeSuite(runs)
            listener.onSuiteDone(
                abortReason == null,
                abortReason ?: "done",
                exporter.root.absolutePath
            )
        }
    }

    // ---------------------------------------------------------------- state
    private fun applyTarget(target: BenchTarget) {
        if (target.id == BenchTarget.STOCK.id) {
            ProfileController.setEngineEnabled(context, false)
        } else {
            if (ProfileController.isEngineOff(context)) {
                ProfileController.setEngineEnabled(context, true)
            }
            ModeSwitcher().executePowercfgMode(target.id, context.packageName)
        }
        // Let the daemons/schedulers settle before the gate.
        Thread.sleep(2000)
    }

    private fun restoreState(engineOff: Boolean, mode: String) {
        runCatching {
            if (engineOff) {
                ProfileController.setEngineEnabled(context, false)
            } else {
                if (ProfileController.isEngineOff(context)) ProfileController.setEngineEnabled(context, true)
                if (mode.isNotEmpty()) ModeSwitcher().executePowercfgMode(mode, context.packageName)
            }
        }
    }

    // ----------------------------------------------------------------- gate
    private fun waitForCooldown(baseline: Double?) {
        if (baseline == null) return
        if (config.targets.size <= 1) return
        val deadline = SystemClock.elapsedRealtime() + config.cooldownMaxMs
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            val temp = currentBatteryTemp() ?: break
            if (temp <= baseline + config.cooldownTargetDeltaC) return
            listener.onProgress("cooldown", String.format(Locale.US, "cooling %.1f°C (target %.1f°C)", temp, baseline + config.cooldownTargetDeltaC))
            stage.status(String.format(Locale.US, "Cooling down: %.1f°C → target %.1f°C", temp, baseline + config.cooldownTargetDeltaC))
            Thread.sleep(5000)
        }
    }

    private fun checkAbort(sample: BenchSample): String? {
        sample.batteryTempC?.let {
            if (it > config.maxBatteryTempC) return "battery temp ${String.format(Locale.US, "%.1f", it)}°C"
        }
        sample.socTempC?.let {
            if (it > config.maxSocTempC) return "soc temp ${String.format(Locale.US, "%.1f", it)}°C"
        }
        if (config.mode == BenchMode.DISCHARGE) {
            sample.capacityPct?.let {
                if (it < config.minCapacityPct) return "battery low ($it%)"
            }
        }
        if (!plugStateMatches()) return "charger state changed"
        if (!isScreenOn()) return "screen off"
        return null
    }

    private fun isScreenOn(): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    private fun plugStateMatches(): Boolean = runCatching {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val wantPlugged = config.mode == BenchMode.CHARGER
        plugged == wantPlugged
    }.getOrDefault(true)

    private fun currentBatteryTemp(): Double? =
        com.omarea.data.GlobalStatus.updateBatteryTemperature().takeIf { it > 5.0 && it < 120.0 }

    // -------------------------------------------------------------- workload
    private fun createWorkload(scenario: BenchScenario): BenchmarkWorkload = when (scenario) {
        BenchScenario.IDLE -> IdleWorkload()
        BenchScenario.SCROLL -> ScrollWorkload()
        BenchScenario.CPU -> CpuWorkload()
        BenchScenario.GPU -> GpuWorkload()
        BenchScenario.MIXED -> MixedWorkload()
        BenchScenario.IO -> IoWorkload(context.cacheDir)
        BenchScenario.VIDEO -> VideoWorkload()
    }

    // ------------------------------------------------------------------ meta
    private fun buildMeta(target: BenchTarget): Map<String, String> {
        val tuningHash = tuningHash()
        val chargePrefs = context.getSharedPreferences(com.omarea.data.SpfConfig.CHARGE_SPF, Context.MODE_PRIVATE)
        return linkedMapOf(
            "scene_version" to "${Build.VERSION.SDK_INT} / ${Scene.context.packageName}",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "android" to Build.VERSION.RELEASE,
            "kernel" to System.getProperty("os.version").orEmpty(),
            "build" to Build.DISPLAY,
            "platform" to PlatformUtils().getCPUName(),
            "mode" to config.mode.name,
            "target" to target.id,
            "engine_off_before" to ProfileController.isEngineOff(context).toString(),
            "tuning_source" to if (TuningRepository.hasUserCopy(PlatformUtils().getCPUName())) "user copy" else "bundled",
            "tuning_hash" to tuningHash,
            "warmup_s" to config.warmupSeconds.toString(),
            "measure_s" to config.measureSeconds.toString(),
            "design_capacity" to "${designCapacityMah}mAh [$designCapacitySource]",
            "scenarios" to config.scenarios.joinToString(",") { it.id },
            "charger_type" to KeepShellPublic.doCmdSync("cat /sys/class/power_supply/usb/real_type 2>/dev/null").trim(),
            "charger_current_max" to KeepShellPublic.doCmdSync("cat /sys/class/power_supply/usb/current_max 2>/dev/null").trim(),
            "sconfig" to KeepShellPublic.doCmdSync("cat /sys/class/thermal/thermal_message/sconfig 2>/dev/null").trim(),
            "night_slow_charge" to chargePrefs.getBoolean(com.omarea.data.SpfConfig.CHARGE_SPF_NIGHT_MODE, false).toString(),
            "qc_limit" to chargePrefs.getInt(com.omarea.data.SpfConfig.CHARGE_SPF_QC_LIMIT, -1).toString(),
            "started_at" to System.currentTimeMillis().toString()
        )
    }

    private fun tuningHash(): String = runCatching {
        val platform = PlatformUtils().getCPUName()
        val json = TuningRepository.read(context, platform)?.toString().orEmpty()
        val digest = MessageDigest.getInstance("SHA-1").digest(json.toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(12)
    }.getOrDefault("-")

    // ------------------------------------------------------------- display
    private class BrightnessState(val brightness: String, val mode: String)

    private fun fixBrightness(): BrightnessState {
        val brightness = KeepShellPublic.doCmdSync("settings get system screen_brightness").trim()
        val mode = KeepShellPublic.doCmdSync("settings get system screen_brightness_mode").trim()
        KeepShellPublic.doCmdSync(
            "settings put system screen_brightness_mode 0; settings put system screen_brightness 180"
        )
        return BrightnessState(brightness, mode)
    }

    private fun restoreBrightness(state: BrightnessState) {
        runCatching {
            val brightness = state.brightness.toIntOrNull()
            if (brightness != null) {
                KeepShellPublic.doCmdSync("settings put system screen_brightness $brightness")
            }
            val mode = state.mode.toIntOrNull()
            if (mode != null) {
                KeepShellPublic.doCmdSync("settings put system screen_brightness_mode $mode")
            }
        }
    }

    private fun sleepSeconds(seconds: Long) {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
    }
}
