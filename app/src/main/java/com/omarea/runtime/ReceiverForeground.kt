package com.omarea.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Root watcher -> foreground package (a11y-free fallback).
 *
 * Explicit broadcast from the root shell script; exported=false so only
 * root/system can deliver it.
 */
class ReceiverForeground : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RootForegroundWatch.ACTION) return
        val pkg = intent.getStringExtra("packageName") ?: return
        runCatching { ForegroundFallback.handle(context, pkg) }
    }
}
