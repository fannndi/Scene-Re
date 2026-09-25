package com.omarea.vtools.dialogs

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.omarea.common.ui.DialogHelper
import com.omarea.scene_mode.game.GameProfileStore
import com.omarea.scene_mode.game.GameSessionStore
import com.omarea.vtools.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Game session history recorded by GameSessionTracker: battery drain, max
 * temperature, average FPS and the modes used per session. Shares
 * dialog_text_report.xml with the other report dialogs.
 */
class DialogGameSessions(private val context: Activity) {
    fun show() {
        val view = context.layoutInflater.inflate(R.layout.dialog_text_report, null)
        val dialog = DialogHelper.customDialog(context, view)

        view.findViewById<TextView>(R.id.report_title).text = context.getString(R.string.game_sessions_title)
        val body = view.findViewById<TextView>(R.id.report_body)
        body.text = format()

        val action = view.findViewById<Button>(R.id.report_action)
        action.visibility = View.VISIBLE
        action.setText(R.string.game_sessions_clear)
        action.setOnClickListener {
            GameSessionStore.clear(context)
            body.text = format()
        }

        view.findViewById<View>(R.id.report_refresh).setOnClickListener {
            body.text = format()
        }
        view.findViewById<View>(R.id.report_close).setOnClickListener { dialog.dismiss() }
    }

    private fun format(): String {
        val sessions = GameSessionStore.list(context)
        if (sessions.isEmpty()) {
            return context.getString(R.string.game_sessions_empty)
        }
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sessions.forEach { session ->
            val minutes = ((session.endedAt - session.startedAt) / 60_000L).coerceAtLeast(0)
            val label = try {
                context.packageManager.getApplicationInfo(session.packageName, 0)
                    .loadLabel(context.packageManager).toString()
            } catch (ex: Exception) {
                session.packageName
            }
            sb.append(label)
            if (label != session.packageName) {
                sb.append("  (").append(session.packageName).append(')')
            }
            sb.append('\n')
            val detected = GameProfileStore.classOf(session.packageName)
            if (detected.isNotEmpty()) {
                sb.append("  detected: ").append(detected).append('\n')
            }
            sb.append("  ").append(date.format(Date(session.startedAt)))
                .append("  ").append(minutes).append(" min")
                .append("  ").append(session.samples).append(" samples").append('\n')
            if (session.startLevel >= 0 && session.endLevel >= 0) {
                sb.append("  battery ").append(session.startLevel).append("% -> ")
                    .append(session.endLevel).append("%  (drain ")
                    .append(session.startLevel - session.endLevel).append("%)").append('\n')
            }
            if (session.maxTempC > 0) {
                sb.append("  max temp ")
                    .append(String.format(Locale.US, "%.1f C", session.maxTempC)).append('\n')
            }
            if (session.avgFps > 0) {
                sb.append("  avg fps ")
                    .append(String.format(Locale.US, "%.1f", session.avgFps)).append('\n')
            }
            if (session.modes.isNotEmpty()) {
                sb.append("  modes: ").append(session.modes).append('\n')
            }
            if (session.guardActivations > 0) {
                sb.append("  thermal guard: ").append(session.guardActivations).append("x\n")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
