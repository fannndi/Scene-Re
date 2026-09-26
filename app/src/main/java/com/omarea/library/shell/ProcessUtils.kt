package com.omarea.library.shell

import android.content.Context
import android.util.Log
import com.omarea.common.shell.KeepShellPublic.doCmdSync
import com.omarea.common.shell.KernelProrp.getProp
import com.omarea.common.shell.ShellEscape
import com.omarea.model.ProcessInfo
import com.omarea.model.ThreadInfo
import com.omarea.shell_utils.ToyboxIntaller
import java.util.*

/*
* Process management helpers
*/
class ProcessUtils(private val context: Context) {
    companion object {
        /** Maximum number of thread rows returned by [getThreadLoads]. */
        private const val MAX_THREAD_ROWS = 15
    }

    /*
    VSS- Virtual Set Size: virtual memory used (includes memory used by shared libraries)
    RSS- Resident Set Size: actual physical memory used (includes memory used by shared libraries)
    PSS- Proportional Set Size: actual physical memory used (shared libraries apportioned proportionally)
    USS- Unique Set Size: physical memory used by the process alone (excludes memory used by shared libraries)
    In general the memory sizes follow: VSS >= RSS >= PSS >= USS
    ————————————————
    Copyright notice: this is an original article by CSDN blogger "Volcano Stone", following the CC 4.0 BY-SA license; reprints must include the original source link and this notice.
    Original link: https://blog.csdn.net/zhangcanyan/java/article/details/84556808
    */
    // pageSize: getconf PAGESIZE

    private val listCmd: TripleCacheValue = object : TripleCacheValue(context, "ProcessUtilsList") {
        override fun initValue(): String {
            val outsideToybox = ToyboxIntaller(context).install()
            val perfectCmd = "top -o %CPU,RES,SWAP,NAME,PID,USER,COMMAND,CMDLINE -q -b -n 1 -m 65535"
            val outsidePerfectCmd = "$outsideToybox $perfectCmd"
            // String insideCmd = "ps -e -o %CPU,RSS,SHR,NAME,PID,USER,COMMAND,CMDLINE";
            // String insideCmd = "ps -e -o %CPU,RES,SHR,RSS,NAME,PID,S,USER,COMMAND,CMDLINE";
            val insideCmd = "ps -e -o %CPU,RES,SWAP,NAME,PID,USER,COMMAND,CMDLINE"
            val outsideCmd = "$outsideToybox $insideCmd"
            for (cmd in arrayOf(outsidePerfectCmd, perfectCmd, outsideCmd, insideCmd)) {
                val rows = doCmdSync("$cmd 2>&1").split("\n".toRegex()).toTypedArray()
                val result = rows[0]
                if (rows.size > 10 && !(result.contains("bad -o") || result.contains("Unknown option") || result.contains("bad"))) {
                    return cmd
                }
            }
            return ""
        }
    }
    private val detailCmd: TripleCacheValue = object : TripleCacheValue(context, "ProcessUtilsDetail") {
        override fun initValue(): String {
            val outsideToybox = ToyboxIntaller(context).install()
            val perfectCmd = "top -o %CPU,RES,SWAP,NAME,PID,USER,COMMAND,CMDLINE -q -b -n 1 -m 65535"
            val outsidePerfectCmd = "$outsideToybox $perfectCmd"
            // String insideCmd = "ps -e -o %CPU,RSS,SHR,NAME,PID,USER,COMMAND,CMDLINE";
            // String insideCmd = "ps -e -o %CPU,RES,SHR,RSS,NAME,PID,S,USER,COMMAND,CMDLINE";
            val insideCmd = "ps -e -o %CPU,RES,SWAP,NAME,PID,USER,COMMAND,CMDLINE"
            val outsideCmd = "$outsideToybox $insideCmd"
            for (cmd in arrayOf(outsideCmd, insideCmd)) {
                val rows = doCmdSync("$cmd 2>&1").split("\n".toRegex()).toTypedArray()
                val result = rows[0]
                if (rows.size > 10 && !(result.contains("bad -o") || result.contains("Unknown option") || result.contains("bad"))) {
                    return "$cmd --pid "
                }
            }
            return ""
        }
    }

    // compatibility check
    fun supported(): Boolean {
        return !(listCmd.toString().isEmpty() || detailCmd.toString().isEmpty())
    }

    private fun str2Long(str: String): Long {
        return when {
            str.contains("K") -> {
                str.substring(0, str.indexOf("K")).toDouble().toLong()
            }
            str.contains("M") -> {
                (str.substring(0, str.indexOf("M")).toDouble() * 1024).toLong()
            }
            str.contains("G") -> {
                (str.substring(0, str.indexOf("G")).toDouble() * 1048576).toLong()
            }
            else -> {
                str.toLong() / 1024
            }
        }
    }

    // Processes excluded from the process list
    private val excludeProcess: ArrayList<String> = object : ArrayList<String>() {
        init {
            add("toybox-outside")
            add("toybox-outside64")
            add("ps")
            add("top")
            add("com.omarea.vtools")
        }
    }

    // Parse a single row
    private fun readRow(row: String): ProcessInfo? {
        val columns = row.split(" +".toRegex()).toTypedArray()
        if (columns.size >= 6) {
            try {
                val processInfo = ProcessInfo()
                processInfo.cpu = columns[0].toFloat()
                processInfo.res = str2Long(columns[1])
                processInfo.swap = str2Long(columns[2])
                processInfo.name = columns[3]
                if (excludeProcess.contains(processInfo.name)) {
                    return null
                }
                processInfo.pid = columns[4].toInt()
                processInfo.user = columns[5]
                processInfo.command = columns[6]
                processInfo.cmdline = row.substring(row.indexOf(processInfo.command) + processInfo.command.length).trim { it <= ' ' }
                return processInfo
            } catch (ex: Exception) {
                // Log.e("Scene-ProcessUtils", "" + ex.getMessage() + " -> " + row);
            }
        } else {
            // Log.e("Scene-ProcessUtils", "" + row);
        }
        return null
    }

    // Get all processes
    val allProcess: ArrayList<ProcessInfo>
        get() {
            val processInfoList = ArrayList<ProcessInfo>()
            val cmd = this.listCmd.toString()
            if (cmd.isNotEmpty()) {
                val skipRows = if (cmd.startsWith("ps")) 1 else 0
                val rows = doCmdSync(cmd).split("\n".toRegex()).toTypedArray()
                var index = 0
                for (row in rows) {
                    if (index < skipRows) {
                        continue
                    }
                    index ++
                    val processInfo = readRow(row.trim { it <= ' ' })
                    if (processInfo != null) {
                        processInfoList.add(processInfo)
                    }
                }
            }
            return processInfoList
        }

    // Get process detail
    fun getProcessDetail(pid: Int): ProcessInfo? {
        val cmd = this.detailCmd.toString()
        if (cmd.isNotEmpty()) {
            val r = doCmdSync(cmd + pid)
            Log.d("Scene-SWAP", cmd + pid)
            Log.d("Scene-SWAP", "" + r)
            val rows = r.split("\n".toRegex()).toTypedArray()
            if (rows.size > 1) {
                val row = readRow(rows[1].trim { it <= ' ' })
                if (row != null) {
                    row.cpuSet = getProp("/proc/$pid/cpuset")
                    row.cGroup = getProp("/proc/$pid/cgroup")
                    row.oomAdj = getProp("/proc/$pid/oom_adj")
                    row.oomScore = getProp("/proc/$pid/oom_score")
                    row.oomScoreAdj = getProp("/proc/$pid/oom_score_adj")
                }
                return row
            }
        }
        return null
    }

    // Force-stop the process
    fun killProcess(pid: Int) {
        doCmdSync("kill -9 $pid")
    }

    // Get the main process PID of an Android app
    fun getAppMainProcess(packageName: String?): Int {
        if (packageName.isNullOrEmpty()) {
            return -1
        }
        val pkg = ShellEscape.quote(packageName)
        val pid = doCmdSync(
            "ps -ef -o PID,NAME | grep -e ${pkg}\$ | egrep -o '[0-9]{1,}' | head -n 1"
        )
        return if (pid.isEmpty() || pid == "error") -1 else pid.toIntOrNull() ?: -1
    }

    // Get all threads of a process (sorted by CPU usage descending, at most MAX_THREAD_ROWS rows)
    fun getThreadLoads(pid: Int): List<ThreadInfo> {
        val result = doCmdSync("top -H -b -q -n 1 -p $pid -o TID,%CPU,CMD")
            .split("\n".toRegex()).toTypedArray()
        val threadData = ArrayList<ThreadInfo>()
        for (row in result) {
            val rowStr = row.trim { it <= ' ' }
            val cols = rowStr.split(" +".toRegex()).toTypedArray()
            if (cols.size > 2) {
                try {
                    val tid = cols[0].toInt()
                    val cpuLoad = cols[1].toDouble()
                    val name = rowStr.substring(rowStr.indexOf(cols[1]) + cols[1].length).trim { it <= ' ' }
                    threadData.add(ThreadInfo().also {
                        it.tid = tid
                        it.cpuLoad = cpuLoad
                        it.name = name
                    })
                } catch (ex: Exception) {
                    // Skip rows that cannot be parsed
                }
            }
        }
        threadData.sortWith { o1, o2 ->
            val r = o2.cpuLoad - o1.cpuLoad
            if (r > 0) 1 else if (r < 0) -1 else 0
        }
        return threadData.subList(0, threadData.size.coerceAtMost(MAX_THREAD_ROWS))
    }

    private val androidProcessRegex = Regex(".*\\..*")
    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.command.contains("app_process") && processInfo.name.matches(androidProcessRegex)
    }

    // Force-stop the process
    fun killProcess(processInfo: ProcessInfo) {
        if (isAndroidProcess(processInfo)) {
            val packageName = if (processInfo.name.contains(":")) processInfo.name.substring(0, processInfo.name.indexOf(":")) else processInfo.name
            // Quote every interpolated argument: process names come from the running
            // system and are not guaranteed to be shell-safe.
            val pkg = ShellEscape.quote(packageName)
            doCmdSync("killall -9 $pkg;am force-stop $pkg;am kill $pkg")
        } else {
            killProcess(processInfo.pid)
        }
    }
}
