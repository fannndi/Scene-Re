package com.omarea.runtime

import android.content.Context
import com.omarea.common.shell.KeepShell
import com.omarea.data.AppInfo.AppType.SYSTEM
import com.omarea.data.AppInfo.AppType.USER
import com.omarea.util.AppListHelper
import com.omarea.vtools.R

class SceneStandbyMode(private val context: Context, private val keepShell: KeepShell) {
    companion object {
        public val configSpfName = "SceneStandbyList"
    }

    private val stateProp = "persist.vtools.suspend"

    /** Packages the standby list will suspend (shared with the journal). */
    private fun selectedPackages(): List<String> {
        val apps = AppListHelper(context).getAll()
        val blackListConfig = context.getSharedPreferences(configSpfName, Context.MODE_PRIVATE)
        val whiteList = context.resources.getStringArray(R.array.scene_standby_white_list)
        return apps.filter { app ->
            !whiteList.contains(app.packageName) &&
                (((app.appType == SYSTEM || app.updated) && blackListConfig.getBoolean(app.packageName.toString(), false)) ||
                    (app.appType == USER && (!app.updated) && blackListConfig.getBoolean(app.packageName.toString(), true)))
        }.map { it.packageName }
    }

    public fun getCmds(on: Boolean): String {
        val cmds = StringBuffer()
        if (on) {
            for (packageName in selectedPackages()) {
                cmds.append("pm suspend \"")
                cmds.append(packageName)
                cmds.append("\"\n")
                cmds.append("am force-stop \"")
                cmds.append(packageName)
                cmds.append("\"\n")
            }
            cmds.append("\n")
            cmds.append("sync\n")
            cmds.append("echo 3 > /proc/sys/vm/drop_caches\n")
            cmds.append("setprop ")
            cmds.append(stateProp)
            cmds.append(" 1")
            cmds.append("\n")
        } else {
            cmds.append("for app in `pm list package | cut -f2 -d ':'`; do\n" +
                    "      pm unsuspend \$app 1 > /dev/null\n" +
                    "    done\n")
            cmds.append("setprop ")
            cmds.append(stateProp)
            cmds.append(" 0")
            cmds.append("\n")
        }
        return cmds.toString()
    }

    public fun on() {
        if (keepShell.doCmdSync("getprop $stateProp").equals("1")) {
            return
        }
        keepShell.doCmdSync(getCmds(true))
        // Journal for the uninstall guard: standby-suspended apps must not
        // stay frozen when Scene is gone.
        selectedPackages().forEach { PmStateJournal.record(context, "suspend", it) }
    }

    public fun off() {
        if (keepShell.doCmdSync("getprop $stateProp").equals("0")) {
            return
        }
        keepShell.doCmdSync(getCmds(false))
        // `off` unsuspends everything — drop every suspend entry.
        PmStateJournal.clearKind(context, "suspend")
    }
}
