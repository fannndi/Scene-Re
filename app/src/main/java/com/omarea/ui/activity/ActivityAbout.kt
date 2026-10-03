package com.omarea.ui.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityAboutBinding

/**
 * About / help: version, device facts and attribution — all offline (the app
 * ships no web links; the old Home help entry pointed at a dead external
 * site and had no callers).
 *
 * Responsibility: show static info + route to Diagnostics.
 * Non-goals: per-screen help (HelpIcon owns that).
 */
class ActivityAbout : ActivityBase() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()
        title = getString(R.string.about_title)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val versionCode = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)

        binding.aboutVersion.text = getString(R.string.about_version, versionName, versionCode)
        binding.aboutDevice.text = getString(
            R.string.about_device,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        )
        binding.aboutKernel.text = getString(
            R.string.about_kernel,
            System.getProperty("os.version") ?: "?"
        )

        binding.aboutDiagnostics.setOnClickListener {
            startActivity(Intent(this, ActivityDiagnostics::class.java))
        }
    }
}
