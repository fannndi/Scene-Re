package com.omarea.vtools.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityDiagnosticsBinding
import com.omarea.core.diagnostics.DiagnosticsCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap diagnostics: collects device/CPU/GPU/thermal/memory/battery state,
 * recent shell executions and error logs into an LLM-friendly Markdown + JSON
 * bundle saved under the app's external debug directory.
 */
class ActivityDiagnostics : ActivityBase() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private var report: String = ""
    private var savedPath: String = ""
    private val myHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()
        title = getString(R.string.menu_diagnostics)

        binding.diagGenerate.setOnClickListener { generateReport() }
        binding.diagCopy.setOnClickListener { copyReport() }
        binding.diagShare.setOnClickListener { shareReport() }
    }

    private fun generateReport() {
        binding.diagGenerate.isEnabled = false
        binding.diagStatus.text = getString(R.string.diag_collecting)

        Thread {
            try {
                val md = DiagnosticsCollector.buildMarkdown(this)
                val json = DiagnosticsCollector.buildJson(this)
                val dir = File(getExternalFilesDir(null), "debug").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val mdFile = File(dir, "Scene-diagnostics-$stamp.md").apply { writeText(md) }
                File(dir, "Scene-diagnostics-$stamp.json").writeText(json)

                myHandler.post {
                    report = md
                    savedPath = mdFile.absolutePath
                    binding.diagStatus.text = getString(R.string.diag_saved, mdFile.name)
                    binding.diagReport.text = md
                    binding.diagCopy.isEnabled = true
                    binding.diagShare.isEnabled = true
                    binding.diagGenerate.isEnabled = true
                }
            } catch (ex: Exception) {
                myHandler.post {
                    binding.diagStatus.text = getString(R.string.diag_failed, ex.message ?: "")
                    binding.diagGenerate.isEnabled = true
                }
            }
        }.start()
    }

    private fun copyReport() {
        if (report.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Scene diagnostics", report))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        if (report.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_SUBJECT, "Scene diagnostics report")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diag_share)))
    }
}
