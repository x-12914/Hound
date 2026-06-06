package com.hound.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HoundApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GUARDIAN, "Guardian", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Persistent protection service" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT, "SOS Alerts", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Active emergency alerts" }
            )
        }
    }

    companion object {
        const val CHANNEL_GUARDIAN = "guardian"
        const val CHANNEL_ALERT = "sos_alert"
    }
}
