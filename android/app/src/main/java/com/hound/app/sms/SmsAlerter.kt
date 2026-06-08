package com.hound.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.hound.app.data.ContactInput

/** Sends the SOS to emergency contacts over the cellular network (no internet needed). */
class SmsAlerter(private val context: Context) {

    fun canSend(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        val hasTelephony =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        return granted && hasTelephony
    }

    @Suppress("DEPRECATION")
    private fun manager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    /** Returns the number of contacts a message was dispatched to. */
    fun sendSos(
        contacts: List<ContactInput>,
        who: String,
        loc: Location?,
        allClear: Boolean = false,
    ): Int {
        if (!canSend()) return 0
        val sm = manager()
        val body = buildMessage(who, loc, allClear)
        var sent = 0
        for (c in contacts) {
            val number = c.phone.trim()
            if (number.isEmpty()) continue
            try {
                val parts = sm.divideMessage(body)
                sm.sendMultipartTextMessage(number, null, parts, null, null)
                sent++
            } catch (e: Exception) {
                // bad number / radio busy — skip this contact, keep trying the rest
            }
        }
        return sent
    }

    private fun buildMessage(who: String, loc: Location?, allClear: Boolean): String {
        if (allClear) {
            return "$who is now safe. The Hound emergency alert has been cancelled."
        }
        val base = "SOS! $who triggered an emergency via Hound (no internet)."
        return if (loc != null) {
            val lat = loc.latitude
            val lng = loc.longitude
            "$base Location: $lat,$lng https://maps.google.com/?q=$lat,$lng"
        } else {
            "$base Location not yet available."
        }
    }
}
