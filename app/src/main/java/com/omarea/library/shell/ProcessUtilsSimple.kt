package com.omarea.library.shell

import android.content.Context
import com.omarea.common.shell.KeepShellPublic
import com.omarea.model.ProcessInfo
import com.omarea.model.ThreadInfo
import com.omarea.shell_utils.ToyboxIntaller
import java.util.*

/*
* Process management
*/
class ProcessUtilsSimple(private val context: Context) {
    /*
    VSS - Virtual Set Size, virtual memory (including shared libraries)
    RSS - Resident Set Size, resident physical memory (including shared libraries)
    PSS - Proportional Set Size, proportional physical memory (shared libraries split by ratio)
    USS - Unique Set Size, physical memory unique to the process (excluding shared libraries)
    In general, memory usage follows: VSS >= RSS >= PSS >= USS
    ————————————————
    Copyright: this is an original article by CSDN blogger "Volcanic Rock", licensed under CC 4.0 BY-SA; reproduce with attribution and a link to the original.
    Original link: https://blog.csdn.net/zhangcanyan/java/article/details/84556808
    */
    private val psCommand = object : TripleCacheValue(context, "ProcessUtils2CMD") {
        override fun initValue(): String {
            val outsideToybox = ToyboxIntaller(context).install()
            val perfectCmd = "top -o %CPU,NAME,COMMAND,PID -q -b -n 1 -m 65535"
            val outsidePerfectCmd = "$outsideToybox $perfectCmd"
            val insideCmd = "ps -e -o %CPU,NAME,COMMAND,PID"
            val outsideCmd = "$outsideToybox $insideCmd"
            for (cmd in arrayOf(outsidePerfectCmd, perfectCmd, outsideCmd, insideCmd)) {
                val rows = KeepShellPublic.doCmdSync("$cmd 2>&1").split("\n".toRegex()).toTypedArray()
                val result = rows[0]
                if (rows.size > 10 &&
                        !(
                            result.contains("bad -o") ||
                            result.contains("Unknown option") ||
                            result.contains("bad")
                        )
                ) {
                    return cmd
                }
            }
            return ""
        }
    }

    // compatibility check (TODO: the first call can be slow; show a loading indicator for better UX)
    fun supported(): Boolean {
        return this.psCommand.toString().isNotEmpty()
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

    // apps excluded from the process list
    private val excludeProcess: ArrayList<String> = object : ArrayList<String>() {
        init {
            add("toybox-outside")
            add("toybox-outside64")
            add("ps")
            add("top")
            add("com.omarea.vtools")
        }
    }

    // parse a single line of data
    private fun readRow(row: String): ProcessInfo? {
        val columns = row.split(" +".toRegex()).toTypedArray()
        if (columns.size >= 3) {
            try {
                val processInfo = ProcessInfo()
                processInfo.cpu = columns[0].toFloat()
                processInfo.name = columns[1]
                if (excludeProcess.contains(processInfo.name)) {
                    return null
                }
                processInfo.command = columns[2]
                processInfo.pid = columns[3].toInt()
                return processInfo
            } catch (ex: Exception) {
                // Log.e("Scene-ProcessUtils", "" + ex.getMessage() + " -> " + row);
            }
        } else {
            // Log.e("Scene-ProcessUtils", "" + row);
        }
        return null
    }

    // get all processes
    val allProcess: ArrayList<ProcessInfo>
        get() {
            val processInfoList = ArrayList<ProcessInfo>()
            val psCommand = this.psCommand.toString()
            if (psCommand.isNotEmpty()) {
                val skipRows = if (psCommand.startsWith("ps")) 1 else 0
                val rows = KeepShellPublic.doCmdSync(psCommand).split("\n".toRegex()).toTypedArray()
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

    // force stop process
    private fun killProcess(pid: Int) {
        KeepShellPublic.doCmdSync("kill -9 $pid")
    }

    private val androidProcessRegex = Regex(".*\\..*")
    private fun isAndroidProcess(processInfo: ProcessInfo): Boolean {
        return processInfo.command.contains("app_process") && processInfo.name.matches(androidProcessRegex)
    }

    // get the main process PID of an Android app
    fun getAppMainProcess(packageName: String?): Int {
        val pid = KeepShellPublic.doCmdSync(
            String.format("ps -ef -o PID,NAME | grep -e %s$ | egrep -o '[0-9]{1,}' | head -n 1", packageName)
        )
        return if (pid.isEmpty() || pid == "error") {
            -1
        } else pid.toInt()
    }

    // force stop process
    fun killProcess(processInfo: ProcessInfo) {
        if (isAndroidProcess(processInfo)) {
            val packageName = if (processInfo.name.contains(":")) {
                processInfo.name.substring(0, processInfo.name.indexOf(":"))
            } else {
                processInfo.name
            }
            KeepShellPublic.doCmdSync(
                String.format("killall -9 %s;am force-stop %s;am kill %s", packageName, packageName, packageName)
            )
        } else {
            killProcess(processInfo.pid)
        }
    }

    // get all threads of a process
    private fun getThreads(pid: Int): String {
        return KeepShellPublic.doCmdSync(
            String.format("top -H -b -q -n 1 -p %d -o TID,%%CPU,CMD", pid)
        )
    }

    // get all threads of a process
    fun getThreadLoads(pid: Int): List<ThreadInfo> {
        val result = getThreads(pid).split("\n".toRegex()).toTypedArray()
        val threadData = ArrayList<ThreadInfo>()
        for (row in result) {
            val rowStr = row.trim { it <= ' ' }
            val cols = rowStr.split(" +".toRegex()).toTypedArray()
            if (cols.size > 2) {
                try {
                    val threadInfo: ThreadInfo = object : ThreadInfo() {
                        init {
                            tid = cols[0].toInt()
                            cpuLoad = cols[1].toDouble()
                            name = rowStr.substring(
                                    rowStr.indexOf(cols[1]) + cols[1].length
                            ).trim { it <= ' ' }
                        }
                    }
                    threadData.add(threadInfo)
                } catch (ex: Exception) {
                    // Log.e("Scene-ProcessUtils", "" + ex.getMessage() + " -> " + row);
                }
            } else {
                // Log.e("Scene-ProcessUtils", "" + ex.getMessage() + " -> " + row);
            }
        }
        threadData.sortWith { o1, o2 ->
            val r = o2.cpuLoad - o1.cpuLoad
            if (r > 0) 1 else if (r < 0) -1 else 0
        }
        val count = threadData.size
        /*
           String taskDir = "/proc/" + pid + "/task/";
           for (ThreadInfo threadInfo: top15) {
               threadInfo.name = KernelProrp.INSTANCE.getProp(taskDir + threadInfo.tid + "/comm");
           }
       */
        return threadData.subList(0, count.coerceAtMost(15))
    }
}