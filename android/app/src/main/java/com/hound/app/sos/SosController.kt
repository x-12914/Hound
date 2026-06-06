package com.hound.app.sos

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.hound.app.audio.AudioRecorder
import com.hound.app.data.Api
import com.hound.app.data.Prefs
import com.hound.app.location.LocationStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Owns one active emergency: creates the alert, streams location, and uploads
 * rolling audio clips until cancelled. Hosted by the foreground service. All
 * network work runs on Dispatchers.IO; transient failures are swallowed and
 * retried on the next cycle.
 */
class SosController(
    private val context: Context,
    private val prefs: Prefs,
    private val onState: (State) -> Unit,
) {
    enum class State { IDLE, TRIGGERING, ACTIVE, ERROR }

    private val api = Api(prefs)
    private val location = LocationStreamer(context)
    private val audio = AudioRecorder(context)

    private var scope: CoroutineScope? = null
    private var alertId: Int? = null

    @Volatile
    var active: Boolean = false
        private set

    fun trigger() {
        if (active) return
        active = true
        onState(State.TRIGGERING)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { run() }
    }

    private suspend fun run() {
        try {
            val deviceId = ensureDevice()
            val first = awaitLastKnown()
            val id = api.createAlert(
                deviceId, first?.latitude, first?.longitude, first?.accuracy,
            )
            alertId = id
            onState(State.ACTIVE)

            scope?.launch { streamLocation(id) }
            if (prefs.captureAudio) scope?.launch { audioLoop(id) }
        } catch (e: Exception) {
            onState(State.ERROR)
            active = false
        }
    }

    private suspend fun ensureDevice(): Int {
        if (prefs.deviceId > 0) return prefs.deviceId
        return api.registerDevice(android.os.Build.MODEL ?: "Android phone")
    }

    private suspend fun awaitLastKnown(): Location? =
        suspendCancellableCoroutine { cont ->
            location.lastKnown { cont.resume(it) }
        }

    private suspend fun streamLocation(id: Int) {
        val interval = prefs.locationIntervalSec.coerceAtLeast(3)
        location.start(interval) { loc ->
            scope?.launch {
                try {
                    api.pushLocation(id, loc.latitude, loc.longitude, loc.accuracy, loc.speed)
                } catch (e: Exception) { /* next fix retries */ }
            }
        }
        // Keep this coroutine alive while the alert is active so updates flow.
        while (active) delay(1000)
        location.stop()
    }

    private suspend fun audioLoop(id: Int) {
        val clipMs = prefs.audioClipSec.coerceIn(5, 60) * 1000L
        while (active) {
            try {
                audio.start()
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < clipMs && active) {
                    delay(250)
                }
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
        if (!active && alertId == null) return
        active = false
        val id = alertId

        // Fire-and-forget status update on a fresh scope so cancelling the main
        // scope below doesn't kill the request.
        if (id != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try { api.updateStatus(id, status) } catch (e: Exception) {}
            }
        }
        location.stop()
        audio.stop()
        scope?.cancel()
        scope = null
        alertId = null
        onState(State.IDLE)
    }
}
