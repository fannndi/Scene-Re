package com.omarea.scene_mode.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.omarea.library.calculator.GetUpTime
import com.omarea.model.TimingTaskInfo
import com.omarea.store.TimingTaskStorage
import com.omarea.utils.PlatformCapabilities
import com.omarea.scene_mode.service.SceneTaskIntentService

public class TimingTaskManager(private var context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val taskListConfig = context.getSharedPreferences("scene_task_list", Context.MODE_PRIVATE)

    private fun getPendingIntent(timingTaskInfo: TimingTaskInfo): PendingIntent {
        val taskId = timingTaskInfo.taskId
        val taskIntent = Intent(context, SceneTaskIntentService::class.java)
        taskIntent.putExtra("taskId", taskId)
        taskIntent.action = taskId
        taskIntent.setAction(taskId)
        // Android 12+ requires an explicit mutability flag on every PendingIntent.
        return PendingIntent.getService(
            context,
            0,
            taskIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    public fun setTaskAndSave(timingTaskInfo: TimingTaskInfo) {
        TimingTaskStorage(context).save(timingTaskInfo)

        val taskId = timingTaskInfo.taskId

        setTask(timingTaskInfo)

        taskListConfig.edit().putBoolean(taskId, timingTaskInfo.enabled).apply()
    }

    public fun setTask(timingTaskInfo: TimingTaskInfo) {
        // if the task is enabled, add it to the queue immediately
        if (timingTaskInfo.enabled && (timingTaskInfo.expireDate < 1 || timingTaskInfo.expireDate > System.currentTimeMillis())) {
            val delay = GetUpTime(timingTaskInfo.triggerTimeMinutes).minutes.toLong() * 60 * 1000 // next execution

            val pendingIntent = getPendingIntent(timingTaskInfo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 12+ gates exact alarms behind SCHEDULE_EXACT_ALARM:
                // use them when granted, otherwise fall back to the inexact
                // variant instead of throwing a SecurityException.
                val exact = PlatformCapabilities.canScheduleExactAlarms(context)
                val triggerAt = SystemClock.elapsedRealtime() + delay
                try {
                    if (exact) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    }
                } catch (ex: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pendingIntent)
            }
        } else {
            timingTaskInfo.enabled = false
            cancelTask(timingTaskInfo)
        }
    }

    public fun updateAlarmManager() {
        val tasks = listTask()
        tasks.forEach {
            setTask(it)
        }
    }

    public fun listTask(): ArrayList<TimingTaskInfo> {
        val taskList = ArrayList<TimingTaskInfo>()
        val storage = TimingTaskStorage(context)
        taskListConfig.all.keys.forEach {
            storage.load(it)?.run {
                taskList.add(this)
            }
        }
        return taskList
    }

    public fun cancelTask(timingTaskInfo: TimingTaskInfo) {
        val pendingIntent = getPendingIntent(timingTaskInfo)
        cancelTask(pendingIntent)
    }

    public fun removeTask(timingTaskInfo: TimingTaskInfo) {
        cancelTask(timingTaskInfo)
        taskListConfig.edit().remove(timingTaskInfo.taskId).apply()
        val storage = TimingTaskStorage(context)
        storage.remove(timingTaskInfo.taskId)
    }

    /**
     */
    public fun cancelTask(pendingIntent: PendingIntent): TimingTaskManager {
        alarmManager.cancel(pendingIntent)
        return this
    }
}
