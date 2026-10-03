package com.omarea.runtime

import android.content.Context
import com.omarea.data.TriggerInfo
import com.omarea.data.TriggerStorage

/**
 * One owner for the two places a trigger lives:
 *  - [TriggerStorage] holds the payload (events, window, actions),
 *  - the `scene_trigger_list` pref maps id -> event names, and that is the
 *    list [TriggerIEventMonitor] scans on every event.
 *
 * Saving one without the other makes the trigger invisible (the state the
 * feature was in before there was any UI).
 *
 * Responsibility: persist/list/remove triggers as one unit.
 * Non-goals: deciding when a trigger fires (TriggerIEventMonitor) or running
 * its actions (TriggerExecutorService).
 */
class TriggerManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val storage = TriggerStorage(context)

    fun list(): List<TriggerInfo> =
        prefs.all.keys.sorted().mapNotNull { storage.load(it) }

    fun save(trigger: TriggerInfo): Boolean {
        val ok = storage.save(trigger)
        prefs.edit()
            .putString(trigger.id, trigger.events.joinToString(",") { it.name })
            .apply()
        return ok
    }

    fun remove(id: String) {
        storage.remove(id)
        prefs.edit().remove(id).apply()
    }

    companion object {
        const val PREFS_NAME = "scene_trigger_list"
    }
}
