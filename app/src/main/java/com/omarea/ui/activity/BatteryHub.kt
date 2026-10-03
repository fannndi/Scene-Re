package com.omarea.ui.activity

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView

/**
 * Shared navigation for the two battery screens (App power ·
 * Hardware read-only): the same tab strip sits on all of them, so they read
 * as one hub instead of the old "Are you looking for other features?"
 * cross-links, and the Home battery tap always lands inside this set.
 *
 * Responsibility: route + highlight the hub tabs.
 * Non-goals: any battery data (each screen owns its own).
 */
object BatteryHub {

    enum class Tab { APPS, HARDWARE }

    /** Opens [tab] without stacking duplicates on top of the current screen. */
    fun open(context: Context, tab: Tab) {
        val target = when (tab) {
            Tab.APPS -> ActivityPowerUtilization::class.java
            Tab.HARDWARE -> ActivityChargeController::class.java
        }
        context.startActivity(
            Intent(context, target)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /**
     * Highlights the current tab, wires the others and hides Hardware on
     * devices without QC/BP charge nodes.
     */
    fun bind(
        context: Context,
        current: Tab,
        hardwareAvailable: Boolean,
        apps: TextView,
        hardware: TextView
    ) {
        listOf(Tab.APPS to apps, Tab.HARDWARE to hardware).forEach { (tab, view) ->
            val active = tab == current
            view.visibility =
                if (tab == Tab.HARDWARE && !hardwareAvailable) View.GONE else View.VISIBLE
            view.alpha = if (active) 1f else 0.65f
            view.paint.isFakeBoldText = active
            view.setOnClickListener { if (!active) open(context, tab) }
        }
    }
}
