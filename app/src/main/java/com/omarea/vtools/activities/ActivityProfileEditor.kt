package com.omarea.vtools.activities

import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.omarea.core.profile.DeviceProfileStore
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityProfileEditorBinding

/**
 * Minimal monospace editor for kernel-profile shell files
 * (/sdcard/Scene/profiles/<mode>.sh). Pass file=<name> (without extension).
 */
class ActivityProfileEditor : ActivityBase() {
    private lateinit var binding: ActivityProfileEditorBinding
    private var fileName: String = "custom"
    private val myHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fileName = intent?.getStringExtra("file") ?: "sm6150"
        title = "$fileName.sh"

        binding.editorSave.setOnClickListener { save() }
        load()
    }

    private fun load() {
        Thread {
            val content = DeviceProfileStore.readUserTuningText(fileName)
                ?: DeviceProfileStore.ensureUserCopy(this, fileName).let { DeviceProfileStore.readUserTuningText(fileName) } ?: ""
            myHandler.post {
                binding.editorInput.setText(content)
                binding.editorStatus.text = getString(R.string.profile_editor_loaded, "$fileName.sh")
            }
        }.start()
    }

    private fun save() {
        val content = binding.editorInput.text.toString()
        Thread {
            val ok = DeviceProfileStore.writeUserTuning(fileName, content)
            myHandler.post {
                binding.editorStatus.text = if (ok) {
                    Toast.makeText(this, R.string.profile_editor_saved, Toast.LENGTH_SHORT).show()
                    getString(R.string.profile_editor_saved_at, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
                } else {
                    getString(R.string.profile_editor_failed)
                }
            }
        }.start()
    }
}
