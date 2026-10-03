package com.omarea.ui.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.omarea.Scene
import com.omarea.common.shared.MagiskExtend
import com.omarea.common.shell.KeepShellPublic
import com.omarea.common.shell.KernelProrp
import com.omarea.common.shell.RootFile
import com.omarea.common.ui.DialogHelper
import com.omarea.util.CheckRootStatus
import com.omarea.data.SpfConfig
import com.omarea.ui.TabIconHelper2
import com.omarea.util.ElectricityUnit
import com.omarea.vtools.R
import com.omarea.ui.dialog.DialogMonitor
import com.omarea.ui.dialog.DialogPower
import com.omarea.ui.screen.FragmentCpuModes
import com.omarea.ui.screen.FragmentHome
import com.omarea.ui.screen.FragmentNav
import com.omarea.vtools.databinding.ActivityMainBinding
import java.util.ArrayDeque

class ActivityMain : ActivityBase() {
    companion object {
        const val EXTRA_SELECT_TAB = "select_tab"
        const val TAB_HOME = 0
        const val TAB_TUNER = 1
        const val TAB_NAV = 2
        var lastSelectedTab = TAB_HOME
    }

    private lateinit var globalSPF: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private val tabHistory = ArrayDeque<Int>()
    private var suppressTabHistory = false

    private class ThermalCheckThread(private var context: Activity) : Thread() {
        private fun deleteThermalCopyWarn(onYes: Runnable) {
            Scene.post {
                if (!context.isFinishing) {
                    val view = LayoutInflater.from(context).inflate(R.layout.dialog_delete_thermal, null)
                    val dialog = DialogHelper.customDialog(context, view)
                    view.findViewById<View>(R.id.btn_no).setOnClickListener {
                        dialog.dismiss()
                    }
                    view.findViewById<View>(R.id.btn_yes).setOnClickListener {
                        dialog.dismiss()
                        onYes.run()
                    }
                    dialog.setCancelable(false)
                }
            }
        }

        override fun run() {
            sleep(500)
            if (
                    MagiskExtend.magiskSupported() &&
                    KernelProrp.getProp("${MagiskExtend.MAGISK_PATH}system/vendor/etc/thermal.current.ini") != ""
            ) {
                when {
                    RootFile.list("/data/thermal/config").size > 0 -> {
                        deleteThermalCopyWarn {
                            KeepShellPublic.doCmdSync(
                                    "chattr -R -i /data/thermal 2> /dev/null\n" +
                                            "rm -rf /data/thermal 2> /dev/null\n" +
                                            "sync;svc power reboot || reboot;"
                            )
                        }
                    }
                    RootFile.list("/data/vendor/thermal/config").size > 0 -> {
                        if (
                                RootFile.fileEquals(
                                        "/data/vendor/thermal/config/thermal-normal.conf",
                                        MagiskExtend.getMagiskReplaceFilePath("/system/vendor/etc/thermal-normal.conf")
                                )
                        ) {
                            // Scene.toast("文件相同，跳过温控清理", Toast.LENGTH_SHORT)
                            return
                        } else {
                            deleteThermalCopyWarn {
                                KeepShellPublic.doCmdSync(
                                        "chattr -R -i /data/vendor/thermal 2> /dev/null\n" +
                                                "rm -rf /data/vendor/thermal 2> /dev/null\n" +
                                                "sync;svc power reboot || reboot;"
                                )
                            }
                        }
                    }
                    else -> return
                }
            }
        }
    }

    @SuppressLint("ResourceAsColor")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ActivityStartSplash.finished) {
            val intent = Intent(this.applicationContext, ActivityStartSplash::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            // intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            startActivity(intent)
            finish()
            return
        }

        /*
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()   // or .detectAll() for all detectable problems
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .penaltyDeath()
                .detectAll()
                .build());
        */

        globalSPF = getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        if (!globalSPF.contains(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT)) {
            globalSPF.edit().putInt(SpfConfig.GLOBAL_SPF_CURRENT_NOW_UNIT, ElectricityUnit().getDefaultElectricityUnit(this)).apply()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            insets
        }

        val tabIconHelper2 = TabIconHelper2(binding.tabList, binding.tabContent, this, R.layout.list_item_tab2)
        // Home is always FragmentHome: without root it renders the Monitor-mode
        // banner + read-only monitoring instead of the dead "Unrooted" page
        // (the old conditional swap also made the banner unreachable).
        tabIconHelper2.newTabSpec(getString(R.string.app_home), getDrawable(R.drawable.app_home)!!, FragmentHome())
        tabIconHelper2.newTabSpec(getString(R.string.app_tuner), getDrawable(R.drawable.app_settings)!!, FragmentCpuModes())
        tabIconHelper2.newTabSpec(getString(R.string.app_nav), getDrawable(R.drawable.app_menu)!!, FragmentNav.createPage(themeMode))
        binding.tabContent.adapter = tabIconHelper2.adapter
        binding.tabList.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                if (suppressTabHistory) {
                    return
                }
                val position = tab?.position ?: return
                if (tabHistory.peekLast() != position) {
                    tabHistory.addLast(position)
                }
                lastSelectedTab = position
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
        setInitialTab(intent?.getIntExtra(EXTRA_SELECT_TAB, TAB_HOME) ?: TAB_HOME)

        if (CheckRootStatus.lastCheckResult) {
            try {
                if (MagiskExtend.magiskSupported() &&
                        !(MagiskExtend.moduleInstalled() || globalSPF.getBoolean("magisk_dot_show", false))
                ) {
                    DialogHelper.confirm(this,
                            getString(R.string.magisk_install_title),
                            getString(R.string.magisk_install_desc),
                            {
                                MagiskExtend.magiskModuleInstall(this)
                            })
                    // 不再提示 globalSPF.edit().putBoolean("magisk_dot_show", true).apply()
                }
            } catch (ex: Exception) {
                DialogHelper.alert(
                        this,
                        getString(R.string.sorry),
                        "Failed to start app\n" + ex.message
                ) {
                    recreate()
                }
            }
            ThermalCheckThread(this).start()
        }

        binding.actionGraph.setOnClickListener {
            actionGraph()
        }
        binding.actionPower.setOnClickListener {
            DialogPower(this).showPowerMenu()
        }
        binding.actionSettings.setOnClickListener {
            startActivity(Intent(this.applicationContext, ActivityOtherSettings::class.java))
        }
    }

    private fun actionGraph() {
        if (!CheckRootStatus.lastCheckResult) {
            Toast.makeText(this, getString(R.string.not_root_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (Settings.canDrawOverlays(this)) {
                DialogMonitor(this).show()
            } else {
                //若没有权限，提示获取
                //val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                //startActivity(intent);
                val intent = Intent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                intent.data = Uri.fromParts("package", this.packageName, null)
                Toast.makeText(applicationContext, getString(R.string.permission_float), Toast.LENGTH_LONG).show()
            }
        } else {
            DialogMonitor(this).show()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setInitialTab(intent?.getIntExtra(EXTRA_SELECT_TAB, TAB_HOME) ?: TAB_HOME)
    }

    private fun setInitialTab(index: Int) {
        if (!::binding.isInitialized) {
            return
        }
        val tabCount = binding.tabList.tabCount
        val tabIndex = index.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
        suppressTabHistory = true
        binding.tabList.getTabAt(tabIndex)?.select()
        suppressTabHistory = false
        tabHistory.clear()
        tabHistory.addLast(tabIndex)
        lastSelectedTab = tabIndex
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    }

    //返回键事件
    override fun onBackPressed() {
        try {
            when {
                supportFragmentManager.backStackEntryCount > 0 -> {
                    supportFragmentManager.popBackStack()
                }
                tabHistory.size > 1 -> {
                    tabHistory.removeLast()
                    val previous = tabHistory.peekLast()
                    if (previous != null) {
                        suppressTabHistory = true
                        binding.tabList.getTabAt(previous)?.select()
                        suppressTabHistory = false
                        return
                    }
                    excludeFromRecent()
                    super.onBackPressed()
                }
                else -> {
                    excludeFromRecent()
                    super.onBackPressed()
                }
            }
        } catch (ex: Exception) {
            ex.stackTrace
        }
    }

    public override fun onPause() {
        super.onPause()
        if (!CheckRootStatus.lastCheckResult) {
            finish()
        }
    }

    override fun onDestroy() {
        val fragmentManager = supportFragmentManager
        fragmentManager.fragments.clear()
        super.onDestroy()
    }
}
