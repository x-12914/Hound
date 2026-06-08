package com.hound.app.sos

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.hound.app.audio.AudioRecorder
import com.hound.app.data.Api
import com.hound.app.data.Prefs
import com.hound.app.location.LocationStreamer
import com.hound.app.net.Connectivity
import com.hound.app.sms.SmsAlerter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Owns one active emergency.
 *
 * Online: creates the alert, streams location, uploads rolling audio clips.
 * Offline: texts the emergency contacts a location SMS and keeps watching for a
 * signal — the moment internet returns it auto-upgrades to the full online alert
 * (dashboard + live location + audio).
 */
class SosController(
    private val context: Context,
    private val prefs: Prefs,
    private val onState: (State) -> Unit,
) {
    enum class State { IDLE, TRIGGERING, ACTIVE, OFFLINE_SMS, ERROR }

    private val api = Api(prefs)
    private val location = LocationStreamer(context)
    private val audio = AudioRecorder(context)
    private val sms = SmsAlerter(context)

    private var scope: CoroutineScope? = null
    private var alertId: Int? = null

    @Volatile private var onlineAlertId: Int? = null
    @Volatile private var lastFix: Location? = null
    @Volatile private var smsModeUsed = false

    @Volatile var active: Boolean = false
        private set

    fun trigger() {
        if (active) return
        active = true
        smsModeUsed = false
        onState(State.TRIGGERING)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { run() }
    }

    private suspend fun run() {
        try {
            lastFix = awaitLastKnown()
            // GPS works with no internet, so start collecting fixes either way.
            startLocationCollection()

            val id = if (Connectivity.hasInternet(context)) tryCreateAlert() else null
            if (id != null) {
                goOnline(id)
            } else {
                onState(State.OFFLINE_SMS)
                scope?.launch { offlineLoop() }
            }
        } catch (e: Exception) {
            onState(State.ERROR)
            active = false
        }
    }

    private fun goOnline(id: Int) {
        alertId = id
        onlineAlertId = id   // from now on, new fixes are pushed to the server
        onState(State.ACTIVE)
        if (prefs.captureAudio) scope?.launch { audioLoop(id) }
    }

    private suspend fun tryCreateAlert(): Int? = try {
        val deviceId = ensureDevice()
        api.createAlert(deviceId, lastFix?.latitude, lastFix?.longitude, lastFix?.accuracy)
    } catch (e: Exception) {
        null
    }

    private suspend fun ensureDevice(): Int {
        if (prefs.deviceId > 0) return prefs.deviceId
        return api.registerDevice(android.os.Build.MODEL ?: "Android phone")
    }

    private suspend fun awaitLastKnown(): Location? =
        suspendCancellableCoroutine { cont -> location.lastKnown { cont.resume(it) } }

    private fun startLocationCollection() {
        val interval = prefs.locationIntervalSec.coerceAtLeast(3)
        location.start(interval) { loc ->
            lastFix = loc
            val id = onlineAlertId
            if (id != null) {
                scope?.launch {
                    try {
                        api.pushLocation(id, loc.latitude, loc.longitude, loc.accuracy, loc.speed)
                    } catch (e: Exception) { /* next fix retries */ }
                }
            }
        }
    }

    private suspend fun offlineLoop() {
        sendSmsBurst(allClear = false)
        var lastSms = SystemClock.elapsedRealtime()
        val updateMs = prefs.smsUpdateMin.coerceIn(1, 30) * 60_000L
        while (active) {
            delay(15_000)
            if (!active) break
            // Try to come back online — if we can, switch to the full alert.
            if (Connectivity.hasInternet(context)) {
                val id = tryCreateAlert()
                if (id != null) {
                    goOnline(id)
                    return
                }
            }
            // Still offline: re-send the location SMS on the chosen cadence.
            if (SystemClock.elapsedRealtime() - lastSms >= updateMs) {
                sendSmsBurst(allClear = false)
                lastSms = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun sendSmsBurst(allClear: Boolean) {
        if (!prefs.smsFallback || !sms.canSend()) return
        val contacts = prefs.getContacts()
        if (contacts.isEmpty()) return
        val who = prefs.ownerName?.takeIf { it.isNotBlank() } ?: prefs.email ?: "Someone"
        val sent = sms.sendSos(contacts, who, lastFix, allClear)
        if (sent > 0) smsModeUsed = true
    }

    private suspend fun audioLoop(id: Int) {
        val clipMs = prefs.audioClipSec.coerceIn(5, 60) * 1000L
        while (active) {
            try {
                audio.start()
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < clipMs && active) delay(250)
                val file = audio.stop() ?: continue
                val dur = (SystemClock.elapsedRealtime() - start) / 1000.0
                try {
                    api.uploadAudio(id, file, dur)
                } finally {
                    file.delete()
                }
            } catch (e: Exception) {
                audio.stop()
                delay(1000)
            }
        }
    }

    fun cancel(status: String = "cancelled") {
        if (!active && alertId == null && !smsModeUsed) return
        active = false
        val id = alertId
        val notifyContacts = smsModeUsed
        val contacts = if (notifyContacts) prefs.getContacts() else emptyList()
        val who = prefs.ownerName?.takeIf { it.isNotBlank() } ?: prefs.email ?: "Someone"
        val lastLoc = lastFix

        // Fire-and-forget on a fresh scope so cancelling the main scope doesn't kill it.
        CoroutineScope(Dispatchers.IO).launch {
            if (id != null) {
                try { api.updateStatus(id, status) } catch (e: Exception) {}
            }
            if (notifyContacts) {
                try { sms.sendSos(contacts, who, lastLoc, allClear = true) } catch (e: Exception) {}
            }
        }

        location.stop()
        audio.stop()
        scope?.cancel()
        scope = null
        alertId = null
        onlineAlertId = null
        smsModeUsed = false
        onState(State.IDLE)
    }
}
