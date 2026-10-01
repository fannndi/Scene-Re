package com.omarea.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.omarea.benchmark.BenchConfig
import com.omarea.benchmark.BenchMode
import com.omarea.benchmark.BenchSample
import com.omarea.benchmark.BenchScenario
import com.omarea.benchmark.BenchTarget
import com.omarea.benchmark.BenchmarkMetrics
import com.omarea.benchmark.BenchmarkReport
import com.omarea.benchmark.BenchmarkRunner
import com.omarea.benchmark.workload.BenchmarkWorkload
import com.omarea.runtime.TrueOff
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityBenchmarkBinding
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * Internal benchmark screen.
 *
 * Responsibility: configuration UI (mode / target / scenarios / duration),
 * manual single-target run and the automated 4-profile agent suite; shows the
 * generated report and shares the per-second bundle.
 *
 * Non-goals: orchestration (BenchmarkRunner) and report rendering
 * (BenchmarkReport).
 */
class ActivityBenchmark : ActivityBase() {
    private lateinit var binding: ActivityBenchmarkBinding
    private var mode = BenchMode.DISCHARGE
    private var runner: BenchmarkRunner? = null
    private var runnerThread: Thread? = null
    private var lastReport = ""
    private var lastBundle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setBackArrow()
        title = getString(R.string.menu_benchmark)

        binding.benchModeDischarge.setOnClickListener { mode = BenchMode.DISCHARGE; updateModeUi() }
        binding.benchModeCharger.setOnClickListener { mode = BenchMode.CHARGER; updateModeUi() }

        binding.benchRun.setOnClickListener { startRun(suite = false) }
        binding.benchSuite.setOnClickListener { startRun(suite = true) }
        binding.benchStop.setOnClickListener {
            runner?.cancel()
            binding.benchStatus.text = getString(R.string.bench_running)
        }

        binding.benchShare.setOnClickListener { shareReport() }
        binding.benchCopy.setOnClickListener { copyReport() }

        mode = detectMode()
        updateModeUi()
    }

    override fun onResume() {
        super.onResume()
        updateModeUi()
    }

    override fun onDestroy() {
        runner?.cancel()
        runnerThread?.join(2000)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ui
    private fun updateModeUi() {
        binding.benchModeState.text = when {
            mode == BenchMode.CHARGER && isPlugged() -> getString(R.string.bench_plug_charger)
            mode == BenchMode.DISCHARGE && !isPlugged() -> getString(R.string.bench_plug_discharge)
            else -> getString(R.string.bench_plug_mismatch, mode.name.lowercase(), if (isPlugged()) "terpasang" else "tidak ada")
        }
    }

    private fun detectMode(): BenchMode = if (isPlugged()) BenchMode.CHARGER else BenchMode.DISCHARGE

    private fun isPlugged(): Boolean = runCatching {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    private fun selectedTarget(): String = when (binding.benchTargetGroup.checkedRadioButtonId) {
        R.id.bench_target_powersave -> BenchTarget.POWERSAVE.id
        R.id.bench_target_balance -> BenchTarget.BALANCE.id
        R.id.bench_target_performance -> BenchTarget.PERFORMANCE.id
        else -> BenchTarget.STOCK.id
    }

    private fun selectedScenarios(): List<BenchScenario> {
        val scenarios = ArrayList<BenchScenario>()
        if (binding.benchScenarioIdle.isChecked) scenarios.add(BenchScenario.IDLE)
        if (binding.benchScenarioScroll.isChecked) scenarios.add(BenchScenario.SCROLL)
        if (binding.benchScenarioCpu.isChecked) scenarios.add(BenchScenario.CPU)
        if (binding.benchScenarioGpu.isChecked) scenarios.add(BenchScenario.GPU)
        if (binding.benchScenarioMixed.isChecked) scenarios.add(BenchScenario.MIXED)
        if (binding.benchScenarioIo.isChecked) scenarios.add(BenchScenario.IO)
        if (binding.benchScenarioVideo.isChecked) scenarios.add(BenchScenario.VIDEO)
        return scenarios
    }

    private fun measureSeconds(): Int = when (binding.benchDurationGroup.checkedRadioButtonId) {
        R.id.bench_duration_30 -> 30
        R.id.bench_duration_120 -> 120
        else -> 60
    }

    // ------------------------------------------------------------------ run
    private fun startRun(suite: Boolean) {
        if (runnerThread?.isAlive == true) {
            Toast.makeText(this, R.string.bench_running, Toast.LENGTH_SHORT).show()
            return
        }
        // TRUE OFF: the benchmark applies profiles — it needs control back.
        if (!TrueOff.guardOrToast(this)) return
        if (isPlugged() != (mode == BenchMode.CHARGER)) {
            updateModeUi()
            Toast.makeText(
                this,
                getString(R.string.bench_plug_mismatch, mode.name.lowercase(), if (isPlugged()) "terpasang" else "tidak ada"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val scenarios = selectedScenarios()
        if (scenarios.isEmpty()) {
            Toast.makeText(this, "Pilih minimal satu skenario", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = if (suite) BenchTarget.ALL.map { it.id } else listOf(selectedTarget())
        val seconds = measureSeconds()
        val config = BenchConfig(
            mode = mode,
            targets = targets,
            scenarios = scenarios,
            warmupSeconds = if (seconds >= 60) 5 else 3,
            measureSeconds = seconds
        )

        val stage = object : BenchmarkRunner.Stage {
            override fun attach(workload: BenchmarkWorkload) = onMain {
                workload.attach(this@ActivityBenchmark, binding.benchStage)
            }

            override fun detach() = onMain { binding.benchStage.removeAllViews() }

            override fun status(text: String) {
                runOnUiThread { binding.benchDetail.text = text }
            }
        }

        val listener = object : BenchmarkRunner.Listener {
            override fun onProgress(phase: String, detail: String) {
                runOnUiThread { binding.benchPhase.text = "$phase · $detail" }
            }

            override fun onSample(sample: BenchSample) {
                // Per-second detail lives in samples.csv; keep the UI light.
            }

            override fun onScenarioDone(target: String, summary: BenchmarkMetrics.Summary) {
                runOnUiThread {
                    binding.benchStatus.text = "$target/${summary.scenario.id}: " +
                        "avg ${summary.avgMw?.let { String.format(Locale.US, "%.0f", it) } ?: "?"}mW, " +
                        "max ${summary.maxBatteryTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}°C"
                }
            }

            override fun onRunDone(run: BenchmarkReport.RunData) {
                val markdown = BenchmarkReport.runMarkdown(run)
                runOnUiThread { binding.benchResults.text = markdown }
            }

            override fun onSuiteDone(ok: Boolean, message: String, bundlePath: String) {
                lastBundle = bundlePath
                lastReport = runCatching {
                    File(bundlePath, "report.md").takeIf { it.exists() }?.readText().orEmpty()
                }.getOrDefault("")
                runOnUiThread {
                    if (lastReport.isNotEmpty()) {
                        binding.benchResults.text = lastReport
                    }
                    binding.benchPhase.text = ""
                    binding.benchStatus.text = getString(
                        if (ok) R.string.bench_finished else R.string.bench_failed,
                        "$message · $bundlePath"
                    )
                    binding.benchShare.isEnabled = lastReport.isNotEmpty()
                    binding.benchCopy.isEnabled = lastReport.isNotEmpty()
                    setRunning(false)
                }
            }
        }

        runner = BenchmarkRunner(this, config, stage, listener)
        setRunning(true)
        binding.benchResults.text = ""
        binding.benchStatus.text = getString(R.string.bench_running)
        binding.benchPhase.text = ""
        val run = runner
        runnerThread = Thread({ run?.run() }, "benchmark-runner").apply { start() }
    }

    private fun setRunning(running: Boolean) {
        binding.benchRun.isEnabled = !running
        binding.benchSuite.isEnabled = !running
        binding.benchStop.isEnabled = running
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    // -------------------------------------------------------------- actions
    private fun copyReport() {
        if (lastReport.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Scene benchmark", lastReport))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (lastReport.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastReport)
            putExtra(Intent.EXTRA_SUBJECT, "Scene benchmark report")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.bench_share)))
    }
}
