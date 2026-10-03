package com.omarea.ui.activity

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.ui.DialogHelper
import com.omarea.common.ui.DialogItemChooser2
import com.omarea.common.model.SelectItem
import com.omarea.data.CustomTaskAction
import com.omarea.data.EventType
import com.omarea.data.TaskAction
import com.omarea.data.TimingTaskInfo
import com.omarea.data.TriggerInfo
import com.omarea.runtime.TimingTaskManager
import com.omarea.runtime.TriggerManager
import com.omarea.runtime.TrueOff
import com.omarea.vtools.R
import com.omarea.vtools.databinding.ActivityAutomationBinding
import java.util.UUID

/**
 * Automation: the creation UI for the two schedulers the engine already runs
 * (TimingTaskManager alarms + TriggerIEventMonitor/TriggerExecutorService).
 * Both were complete but unreachable — nothing could ever save a task or a
 * trigger.
 *
 * Responsibility: list/create/edit/enable/delete both kinds.
 * Non-goals: executing actions (TaskActionsExecutor) or arming alarms
 * (TimingTaskManager owns that, including the TRUE OFF guard).
 */
class ActivityAutomation : ActivityBase() {

    private lateinit var binding: ActivityAutomationBinding
    private val taskManager by lazy { TimingTaskManager(this) }
    private val triggerManager by lazy { TriggerManager(this) }

    /** TaskAction -> label; the only actions the executor understands. */
    private val actionItems: List<Pair<TaskAction, Int>> = listOf(
        TaskAction.FSTRIM to R.string.action_fstrim,
        TaskAction.STANDBY_MODE_ON to R.string.action_standby_on,
        TaskAction.STANDBY_MODE_OFF to R.string.action_standby_off
    )

    /**
     * Events TriggerIEventMonitor.eventFilter actually accepts — offering
     * anything else would create triggers that can never fire.
     */
    private val eventItems: List<Pair<EventType, Int>> = listOf(
        EventType.POWER_CONNECTED to R.string.event_power_connected,
        EventType.POWER_DISCONNECTED to R.string.event_power_disconnected,
        EventType.BATTERY_LOW to R.string.event_battery_low,
        EventType.SCREEN_ON to R.string.event_screen_on,
        EventType.SCREEN_OFF to R.string.event_screen_off,
        EventType.BOOT_COMPLETED to R.string.event_boot
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setBackArrow()
        title = getString(R.string.automation_title)

        binding.automationAddTask.setOnClickListener {
            if (TrueOff.guardOrToast(this)) showTaskDialog(null)
        }
        binding.automationAddTrigger.setOnClickListener {
            if (TrueOff.guardOrToast(this)) showTriggerDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        renderTasks()
        renderTriggers()
    }

    // ------------------------------------------------------------------ tasks
    private fun renderTasks() {
        val list = binding.automationTasksList
        list.removeAllViews()
        val tasks = taskManager.listTask().sortedBy { it.triggerTimeMinutes }
        binding.automationTasksEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        tasks.forEach { task -> list.addView(taskRow(task)) }
    }

    private fun taskRow(task: TimingTaskInfo): View {
        val row = layoutInflater.inflate(R.layout.item_automation, binding.automationTasksList, false)
        row.findViewById<TextView>(R.id.row_title).text =
            task.taskName?.takeIf { it.isNotEmpty() } ?: getString(R.string.automation_task_dialog_title)
        row.findViewById<TextView>(R.id.row_summary).text = taskSummary(task)

        val toggle = row.findViewById<CompoundButton>(R.id.row_switch)
        toggle.isChecked = task.enabled
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!TrueOff.guardOrToast(this)) {
                toggle.isChecked = task.enabled
                return@setOnCheckedChangeListener
            }
            task.enabled = checked
            taskManager.setTaskAndSave(task)
            renderTasks()
        }

        row.findViewById<View>(R.id.row_content).setOnClickListener {
            if (TrueOff.guardOrToast(this)) showTaskDialog(task)
        }
        row.findViewById<View>(R.id.row_delete).setOnClickListener {
            DialogHelper.confirm(
                this,
                getString(R.string.automation_delete),
                getString(R.string.automation_delete_task),
                Runnable {
                    taskManager.removeTask(task)
                    Toast.makeText(this, R.string.automation_deleted, Toast.LENGTH_SHORT).show()
                    renderTasks()
                },
                null
            )
        }
        return row
    }

    private fun taskSummary(task: TimingTaskInfo): String {
        val parts = ArrayList<String>()
        parts += hhmm(task.triggerTimeMinutes)
        parts += task.taskActions.orEmpty().map { actionLabel(it) }
        if (task.afterScreenOff) parts += getString(R.string.automation_after_screen_off)
        if (task.chargeOnly) parts += getString(R.string.automation_charge_only)
        if (task.batteryCapacityRequire > 0) {
            parts += getString(R.string.automation_battery_min) + " " + task.batteryCapacityRequire
        }
        if (!task.customTaskActions.isNullOrEmpty()) parts += "shell"
        return parts.joinToString(" · ")
    }

    private fun showTaskDialog(existing: TimingTaskInfo?) {
        val view = layoutInflater.inflate(R.layout.dialog_automation_task, null)
        val dialog = DialogHelper.customDialog(this, view)

        val nameInput = view.findViewById<EditText>(R.id.task_name)
        val timeView = view.findViewById<TextView>(R.id.task_time)
        val actionsView = view.findViewById<TextView>(R.id.task_actions)
        val afterScreenOff = view.findViewById<CompoundButton>(R.id.task_after_screen_off)
        val chargeOnly = view.findViewById<CompoundButton>(R.id.task_charge_only)
        val batteryInput = view.findViewById<EditText>(R.id.task_battery)
        val customInput = view.findViewById<EditText>(R.id.task_custom_cmd)

        var minutes = existing?.triggerTimeMinutes
            ?: (java.util.Calendar.getInstance().let {
                it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
            })
        var actions: List<TaskAction> = existing?.taskActions?.toList() ?: emptyList()

        fun paintTime() {
            timeView.text = hhmm(minutes)
        }

        fun paintActions() {
            actionsView.text = if (actions.isEmpty()) {
                getString(R.string.automation_actions_empty)
            } else {
                actions.joinToString(", ") { actionLabel(it) }
            }
        }

        nameInput.setText(existing?.taskName ?: "")
        afterScreenOff.isChecked = existing?.afterScreenOff ?: false
        chargeOnly.isChecked = existing?.chargeOnly ?: false
        batteryInput.setText((existing?.batteryCapacityRequire ?: 0).toString())
        customInput.setText(
            existing?.customTaskActions.orEmpty().joinToString("\n") { it.Command }.orEmpty()
        )
        paintTime()
        paintActions()

        timeView.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                minutes = hour * 60 + minute
                paintTime()
            }, minutes / 60, minutes % 60, true).show()
        }

        actionsView.setOnClickListener {
            val selected = ArrayList<SelectItem>()
            val options = ArrayList<SelectItem>()
            actionItems.forEach { (action, labelRes) ->
                options += SelectItem().apply {
                    title = getString(labelRes)
                    value = action.name
                    this.selected = actions.contains(action)
                    if (this.selected) selected += this
                }
            }
            DialogItemChooser2(themeMode.isDarkMode, options, selected, true, object : DialogItemChooser2.Callback {
                override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                    val names = selected.mapNotNull { it.value }
                    actions = actionItems.filter { (action, _) -> names.contains(action.name) }.map { it.first }
                    paintActions()
                }
            }).setTitle(getString(R.string.automation_actions)).show(supportFragmentManager, "task-actions")
        }

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            if (actions.isEmpty()) {
                Toast.makeText(this, R.string.automation_no_actions, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val batteryMin = batteryInput.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 0
            val task = existing ?: TimingTaskInfo(UUID.randomUUID().toString()).apply { enabled = true }
            task.taskName = nameInput.text.toString().trim()
            task.triggerTimeMinutes = minutes
            task.taskActions = ArrayList(actions)
            task.afterScreenOff = afterScreenOff.isChecked
            task.chargeOnly = chargeOnly.isChecked
            task.batteryCapacityRequire = batteryMin
            task.customTaskActions = mergeCustomCommands(existing, customInput.text.toString())
            taskManager.setTaskAndSave(task)
            dialog.dismiss()
            Toast.makeText(this, R.string.automation_saved, Toast.LENGTH_SHORT).show()
            renderTasks()
        }
    }

    /**
     * Line-per-command editing: keep the human name of a command that is
     * unchanged, name new ones generically — never drop a command silently.
     */
    private fun mergeCustomCommands(existing: TimingTaskInfo?, text: String): ArrayList<CustomTaskAction> {
        val previous = existing?.customTaskActions.orEmpty()
        val result = ArrayList<CustomTaskAction>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { command ->
            result += CustomTaskAction().apply {
                Command = command
                Name = previous.firstOrNull { it.Command == command }?.Name ?: getString(R.string.automation_task_dialog_title)
            }
        }
        return result
    }

    // --------------------------------------------------------------- triggers
    private fun renderTriggers() {
        val list = binding.automationTriggersList
        list.removeAllViews()
        val triggers = triggerManager.list()
        binding.automationTriggersEmpty.visibility = if (triggers.isEmpty()) View.VISIBLE else View.GONE
        triggers.forEach { trigger -> list.addView(triggerRow(trigger)) }
    }

    private fun triggerRow(trigger: TriggerInfo): View {
        val row = layoutInflater.inflate(R.layout.item_automation, binding.automationTriggersList, false)
        row.findViewById<TextView>(R.id.row_title).text =
            trigger.id.takeIf { it.isNotEmpty() } ?: getString(R.string.automation_trigger_dialog_title)
        row.findViewById<TextView>(R.id.row_summary).text = triggerSummary(trigger)

        val toggle = row.findViewById<CompoundButton>(R.id.row_switch)
        toggle.isChecked = trigger.enabled
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!TrueOff.guardOrToast(this)) {
                toggle.isChecked = trigger.enabled
                return@setOnCheckedChangeListener
            }
            trigger.enabled = checked
            triggerManager.save(trigger)
            renderTriggers()
        }

        row.findViewById<View>(R.id.row_content).setOnClickListener {
            if (TrueOff.guardOrToast(this)) showTriggerDialog(trigger)
        }
        row.findViewById<View>(R.id.row_delete).setOnClickListener {
            DialogHelper.confirm(
                this,
                getString(R.string.automation_delete),
                getString(R.string.automation_delete_trigger),
                Runnable {
                    triggerManager.remove(trigger.id)
                    Toast.makeText(this, R.string.automation_deleted, Toast.LENGTH_SHORT).show()
                    renderTriggers()
                },
                null
            )
        }
        return row
    }

    private fun triggerSummary(trigger: TriggerInfo): String {
        val parts = ArrayList<String>()
        parts += trigger.events.orEmpty().map { eventLabel(it) }
        parts += trigger.taskActions.orEmpty().map { actionLabel(it) }
        if (trigger.timeLimited) parts += "${hhmm(trigger.timeStart)}–${hhmm(trigger.timeEnd)}"
        if (!trigger.customTaskActions.isNullOrEmpty()) parts += "shell"
        return parts.joinToString(" · ")
    }

    private fun showTriggerDialog(existing: TriggerInfo?) {
        val view = layoutInflater.inflate(R.layout.dialog_automation_trigger, null)
        val dialog = DialogHelper.customDialog(this, view)

        val nameInput = view.findViewById<EditText>(R.id.trigger_name)
        val eventsView = view.findViewById<TextView>(R.id.trigger_events)
        val actionsView = view.findViewById<TextView>(R.id.trigger_actions)
        val timeLimited = view.findViewById<CompoundButton>(R.id.trigger_time_limited)
        val windowRows = view.findViewById<View>(R.id.trigger_window_rows)
        val startView = view.findViewById<TextView>(R.id.trigger_start)
        val endView = view.findViewById<TextView>(R.id.trigger_end)

        var events: List<EventType> = existing?.events?.toList() ?: emptyList()
        var actions: List<TaskAction> = existing?.taskActions?.toList() ?: emptyList()
        var start = existing?.timeStart ?: 0
        var end = existing?.timeEnd ?: (24 * 60 - 1)

        fun paintEvents() {
            eventsView.text = if (events.isEmpty()) {
                getString(R.string.automation_events_empty)
            } else {
                events.joinToString(", ") { eventLabel(it) }
            }
        }

        fun paintActions() {
            actionsView.text = if (actions.isEmpty()) {
                getString(R.string.automation_actions_empty)
            } else {
                actions.joinToString(", ") { actionLabel(it) }
            }
        }

        fun paintWindow() {
            windowRows.visibility = if (timeLimited.isChecked) View.VISIBLE else View.GONE
            startView.text = hhmm(start)
            endView.text = hhmm(end)
        }

        nameInput.setText(existing?.id ?: "")
        timeLimited.isChecked = existing?.timeLimited ?: false
        paintEvents()
        paintActions()
        paintWindow()

        eventsView.setOnClickListener {
            val selected = ArrayList<SelectItem>()
            val options = ArrayList<SelectItem>()
            eventItems.forEach { (event, labelRes) ->
                options += SelectItem().apply {
                    title = getString(labelRes)
                    value = event.name
                    this.selected = events.contains(event)
                    if (this.selected) selected += this
                }
            }
            DialogItemChooser2(themeMode.isDarkMode, options, selected, true, object : DialogItemChooser2.Callback {
                override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                    val names = selected.mapNotNull { it.value }
                    events = eventItems.filter { (event, _) -> names.contains(event.name) }.map { it.first }
                    paintEvents()
                }
            }).setTitle(getString(R.string.automation_events)).show(supportFragmentManager, "trigger-events")
        }

        actionsView.setOnClickListener {
            val selected = ArrayList<SelectItem>()
            val options = ArrayList<SelectItem>()
            actionItems.forEach { (action, labelRes) ->
                options += SelectItem().apply {
                    title = getString(labelRes)
                    value = action.name
                    this.selected = actions.contains(action)
                    if (this.selected) selected += this
                }
            }
            DialogItemChooser2(themeMode.isDarkMode, options, selected, true, object : DialogItemChooser2.Callback {
                override fun onConfirm(selected: List<SelectItem>, status: BooleanArray) {
                    val names = selected.mapNotNull { it.value }
                    actions = actionItems.filter { (action, _) -> names.contains(action.name) }.map { it.first }
                    paintActions()
                }
            }).setTitle(getString(R.string.automation_actions)).show(supportFragmentManager, "trigger-actions")
        }

        timeLimited.setOnCheckedChangeListener { _, _ -> paintWindow() }
        startView.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                start = hour * 60 + minute
                paintWindow()
            }, start / 60, start % 60, true).show()
        }
        endView.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                end = hour * 60 + minute
                paintWindow()
            }, end / 60, end % 60, true).show()
        }

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_confirm).setOnClickListener {
            if (events.isEmpty() || actions.isEmpty()) {
                Toast.makeText(this, R.string.automation_no_actions, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val id = nameInput.text.toString().trim().ifEmpty {
                existing?.id ?: UUID.randomUUID().toString().substring(0, 8)
            }
            val trigger = existing ?: TriggerInfo(id)
            trigger.id = id
            trigger.enabled = existing?.enabled ?: true
            trigger.events = ArrayList(events)
            trigger.taskActions = ArrayList(actions)
            trigger.timeLimited = timeLimited.isChecked
            trigger.timeStart = start
            trigger.timeEnd = end
            triggerManager.save(trigger)
            dialog.dismiss()
            Toast.makeText(this, R.string.automation_saved, Toast.LENGTH_SHORT).show()
            renderTriggers()
        }
    }

    // ------------------------------------------------------------------ labels
    private fun actionLabel(action: TaskAction): String =
        actionItems.firstOrNull { it.first == action }?.second?.let { getString(it) } ?: action.name

    private fun eventLabel(event: EventType): String =
        eventItems.firstOrNull { it.first == event }?.second?.let { getString(it) } ?: event.name

    private fun hhmm(minutes: Int): String =
        String.format("%02d:%02d", ((minutes % 1440) + 1440) % 1440 / 60, ((minutes % 1440) + 1440) % 1440 % 60)
}
