package com.hound.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hound.app.HoundApp
import com.hound.app.R
import com.hound.app.data.Prefs
import com.hound.app.sos.SosController
import com.hound.app.ui.MainActivity

/**
 * Always-on guardian. Listens for rapid power-button presses (detected via
 * SCREEN_ON / SCREEN_OFF toggles, the only signal an app can observe without
 * root) and fires an SOS when the user presses N times within a short window.
 */
class SosForegroundService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var sos: SosController

    private val toggleTimes = ArrayDeque<Long>()
    private var lastTriggerAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> onScreenToggle()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sos = SosController(this, prefs) { state -> onSosState(state) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        startInForeground(guardianNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> sos.cancel()
            ACTION_TRIGGER -> maybeTrigger(force = true)
        }
        // Sticky so the OS restarts the guardian if it is killed.
        return START_STICKY
    }

    private fun onScreenToggle() {
        val now = SystemClock.elapsedRealtime()
        val window = prefs.pressWindowMs
        toggleTimes.addLast(now)
        while (toggleTimes.isNotEmpty() && now - toggleTimes.first() > window) {
            toggleTimes.removeFirst()
        }
        if (toggleTimes.size >= prefs.pressCount) {
            toggleTimes.clear()
            maybeTrigger(force = false)
        }
    }

    private fun maybeTrigger(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // Debounce so one panic burst doesn't create several alerts.
        if (!force && now - lastTriggerAt < 8000) return
        lastTriggerAt = now
        sos.trigger()
    }

    private fun onSosState(state: SosController.State) {
        val notif = when (state) {
            SosController.State.IDLE -> guardianNotification()
            SosController.State.TRIGGERING -> alertNotification("Sending alert…")
            SosController.State.ACTIVE -> alertNotification("SOS ACTIVE — help is being notified")
            SosController.State.OFFLINE_SMS ->
                alertNotification("No internet — SOS sent to your contacts by SMS")
            SosController.State.ERROR -> guardianNotification("Last alert failed to send")
        }
        startInForeground(notif)
    }

    // ---- notifications ----

    private fun guardianNotification(extra: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HoundApp.CHANNEL_GUARDIAN)
            .setContentTitle("Hound is protecting you")
            .setContentText(extra ?: "Press power ${prefs.pressCount}× fast to send an SOS")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun alertNotification(text: String): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, SosForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HoundApp.CHANNEL_ALERT)
            .setContentTitle("🚨 Emergency alert active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "I'm safe — cancel", cancel)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                val micGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (micGranted && prefs.captureAudio &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. location permission revoked after enabling the guardian.
            // Fall back to a plain foreground notification so we don't crash.
            try { startForeground(NOTIF_ID, notification) } catch (ignored: Exception) {}
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        sos.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_CANCEL = "com.hound.app.CANCEL"
        const val ACTION_TRIGGER = "com.hound.app.TRIGGER"

        fun start(context: Context) {
            val intent = Intent(context, SosForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SosForegroundService::class.java))
        }
    }
}
