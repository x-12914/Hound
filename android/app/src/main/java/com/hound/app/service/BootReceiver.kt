package com.hound.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hound.app.data.Prefs

/** Restarts the guardian service after a reboot if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.guardianEnabled && prefs.isLoggedIn()) {
            SosForegroundService.start(context)
        }
    }
}
