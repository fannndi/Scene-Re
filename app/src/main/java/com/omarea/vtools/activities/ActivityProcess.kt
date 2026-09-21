package com.omarea.vtools.activities

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.omarea.Scene
import com.omarea.common.ui.DialogHelper
import com.omarea.library.shell.ProcessUtils
import com.omarea.model.ProcessInfo
import com.omarea.ui.AdapterProcess
import com.omarea.utils.AppListHelper
import com.omarea.vtools.R
import com.omarea.vtools.dialogs.DialogSingleAppOptions
import com.omarea.vtools.databinding.ActivtyProcessBinding
import java.util.*

class ActivityProcess : ActivityBase() {
    private lateinit var binding: ActivtyProcessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivtyProcessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setBackArrow()

        onViewCreated(this)
    }

    private val processUtils = ProcessUtils(Scene.context)
    private var supported: Boolean = false
    private val handle = Handler(Looper.getMainLooper())

    private fun onViewCreated(context: Context) {
        supported = processUtils.supported()

        if (supported) {
            binding.processUnsupported.visibility = View.GONE
            binding.processView.visibility = View.VISIBLE
        } else {
            binding.processUnsupported.visibility = View.VISIBLE
            binding.processView.visibility = View.GONE
        }

        if (supported) {
            binding.processList.adapter = AdapterProcess(context).apply {
                // Use the search keyword passed by the caller
                var name = intent?.extras?.getString("name")
                if (name != null) {
                    if (name.contains(":")) {
                        name = name.substring(0, name.indexOf(":"))
                    }
                    updateKeywords(name)
                    updateFilterMode(AdapterProcess.FILTER_ANDROID)
                    binding.processFilter.setSelection(2)
                    binding.processSearch.setText(name)
                }
            }
            binding.processList.setOnItemClickListener { _, _, position, _ ->
                openProcessDetail((binding.processList.adapter as AdapterProcess).getItem(position))
            }
        }

        // Search keyword
        binding.processSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (binding.processList.adapter as AdapterProcess?)?.updateKeywords(v.text.toString())
                return@setOnEditorActionListener true
            }
            false
        }

        // Sort order
        binding.processSortMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (binding.processList.adapter as AdapterProcess?)?.updateSortMode(when (position) {
                    0 -> AdapterProcess.SORT_MODE_CPU
                    1 -> AdapterProcess.SORT_MODE_RES
                    2 -> AdapterProcess.SORT_MODE_PID
                    else -> AdapterProcess.SORT_MODE_DEFAULT
                })
            }
        }

        // Filter
        binding.processFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (binding.processList.adapter as AdapterProcess?)?.updateFilterMode(when (position) {
                    0 -> AdapterProcess.FILTER_ANDROID_USER
                    1 -> AdapterProcess.FILTER_ANDROID_SYSTEM
                    2 -> AdapterProcess.FILTER_ANDROID
                    3 -> AdapterProcess.FILTER_OTHER
                    4 -> AdapterProcess.FILTER_ALL
                    else -> AdapterProcess.FILTER_ALL
                })
            }
        }
    }

    // Update the task list
    private fun updateData() {
        val data = processUtils.allProcess
        handle.post {
            (binding.processList.adapter as AdapterProcess?)?.setList(data)
        }
    }

    private fun resume() {
        if (supported && timer == null) {
            timer = Timer()
            timer!!.schedule(object : TimerTask() {
                override fun run() {
                    updateData()
                }
            }, 0, 3000)
        }
    }

    private fun pause() {
        if (timer != null) {
            timer?.cancel()
            timer = null
        }
    }

    private var timer: Timer? = null
    override fun onResume() {
        super.onResume()
        title = getString(R.string.menu_processes)
        resume()
    }

    override fun onPause() {
        pause()
        super.onPause()
    }

    // Back key event
    override fun onBackPressed() {
        excludeFromRecent()
        this.finish()
    }

    private val regexUser = Regex("u[0-9]+_.*")
    private val regexPackageName = Regex(".*\\..*")
    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return (processInfo.command.contains("app_process") && processInfo.name.matches(regexPackageName))
    }

    private var pm: PackageManager? = null
    private fun loadIcon(imageView: ImageView, item: ProcessInfo) {
        Thread(Runnable {
            var icon: Drawable? = null
            try {
                val name = if (item.name.contains(":")) item.name.substring(0, item.name.indexOf(":")) else item.name
                val packageManager = pm ?: return@Runnable
                val installInfo = packageManager.getPackageInfo(name, 0)
                val appInfo = installInfo.applicationInfo
                if (appInfo != null) {
                    icon = appInfo.loadIcon(packageManager)
                }
            } catch (ex: Exception) {
            } finally {
                if (icon != null) {
                    imageView.post {
                        imageView.setImageDrawable(icon)
                    }
                } else {
                    imageView.post {
                        imageView.setImageDrawable(getDrawable(R.drawable.process_android))
                    }
                }
            }
        }).start()
    }

    private fun openProcessDetail(processInfo: ProcessInfo) {
        val detail = processUtils.getProcessDetail(processInfo.pid)
        if (detail != null) {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_process_detail, null)

            if (pm == null) {
                pm = packageManager
            }

            val name = if (detail.name.contains(":")) detail.name.substring(0, detail.name.indexOf(":")) else detail.name
            try {
                val app = pm!!.getApplicationInfo(name, 0)
                detail.friendlyName = "" + app.loadLabel(pm!!)
            } catch (ex: java.lang.Exception) {
                detail.friendlyName = name
            }
            val dialog = DialogHelper.customDialog(this, view)

            /*
            # On Android Q, memory can be reclaimed manually via /proc/[pid]/reclaim
            # Example: reclaim memory of a single app
            pgrep -f com.tencent.mobileqq | while read line ; do
              echo all > /proc/$line/reclaim
            done

            # Example: reclaim memory of all third-party apps (iteration is inefficient)
            # pm list packages | awk -F ':' '{print $2}' |  while read app ; do
            pm list packages | cut -f2 -d ':' |  while read app ; do
              echo $app
              pgrep -f $app | while read pid; do
                echo all > /proc/$pid/reclaim
              done
            done

            # Example: reclaim memory of all background apps (iteration is slightly more efficient)
            cat /dev/cpuset/background/tasks | while read line ; do
              echo all > /proc/$line/reclaim 2>/dev/null 2> /dev/null
            done

            # Example: reclaim memory of all background apps (enhanced: only filters empty processes)
            cat /dev/cpuset/background/tasks | while read line ; do
              if [[ -f /proc/$line/oom_adj ]] && [[ `cat /proc/$line/oom_adj` == 15 ]]; then
                echo all > /proc/$line/reclaim 2>/dev/null
              fi
            done
            */

            view.run {
                findViewById<TextView>(R.id.ProcessFriendlyName).text = detail.friendlyName
                findViewById<TextView>(R.id.ProcessName).text = detail.name
                findViewById<TextView>(R.id.ProcessCommand).text = detail.command
                findViewById<TextView>(R.id.ProcessCmdline).text = detail.cmdline
                findViewById<TextView>(R.id.ProcessPID).text = detail.pid.toString()
                findViewById<TextView>(R.id.ProcessCPU).text = detail.getCpu().toString() + "%"
                findViewById<TextView>(R.id.ProcessCpuSet).text = "" + detail.cpuSet.toString()
                findViewById<TextView>(R.id.ProcessCGroup).text = "" + detail.cGroup
                findViewById<TextView>(R.id.ProcessOOMADJ).text = "" + detail.oomAdj
                findViewById<TextView>(R.id.ProcessOOMScoreAdj).text = "" + detail.oomScoreAdj
                findViewById<TextView>(R.id.ProcessState).text = detail.getState()
                if (detail.res > 8192) {
                    findViewById<TextView>(R.id.ProcessMEM).text = (detail.res / 1024).toInt().toString() + "MB"
                } else {
                    findViewById<TextView>(R.id.ProcessMEM).text = detail.res.toString() + "KB"
                }
                if (detail.swap > 8192) {
                    findViewById<TextView>(R.id.ProcessSWAP).text = (detail.swap / 1024).toInt().toString() + "MB"
                } else {
                    findViewById<TextView>(R.id.ProcessSWAP).text = detail.swap.toString() + "KB"
                }
                findViewById<TextView>(R.id.ProcessUSER).text = processInfo.user
                if (isAndroidProcess(processInfo)) {
                    loadIcon(findViewById<ImageView>(R.id.ProcessIcon), processInfo)
                    val btn = findViewById<Button>(R.id.ProcessStopApp)
                    val options = findViewById<Button>(R.id.ProcessAppOptions)
                    btn.setOnClickListener {
                        processUtils.killProcess(processInfo)
                        dialog.dismiss()
                    }
                    options.setOnClickListener {
                        val packageName = if(processInfo.name.contains(":")) {
                            processInfo.name.substring(0, processInfo.name.indexOf(":"))
                        } else {
                            processInfo.name
                        }
                        val app = AppListHelper(context).getApp(packageName)
                        if (app != null) {
                            DialogSingleAppOptions(this@ActivityProcess, app, handle).showSingleAppOptions()
                        } else {
                            Toast.makeText(context, "App not found", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                    }
                    btn.visibility = View.VISIBLE
                    options.visibility = View.VISIBLE
                }
                findViewById<View>(R.id.ProcessKill).setOnClickListener {
                    processUtils.killProcess(detail.pid)
                    dialog.dismiss()
                }
            }
        } else {
            Toast.makeText(this, "Unable to get details; the process may have exited.", Toast.LENGTH_SHORT).show()
        }
    }
}
