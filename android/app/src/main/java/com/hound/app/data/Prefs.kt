package com.hound.app.data

import android.content.Context
import java.util.UUID

/** Lightweight persisted settings + session. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hound", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()

    var email: String?
        get() = sp.getString("email", null)
        set(v) = sp.edit().putString("email", v).apply()

    var deviceId: Int
        get() = sp.getInt("device_id", -1)
        set(v) = sp.edit().putInt("device_id", v).apply()

    /** Stable id so re-installs/relogins map to a single device server-side. */
    val installId: String
        get() {
            var id = sp.getString("install_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString("install_id", id).apply()
            }
            return id
        }

    var pressCount: Int
        get() = sp.getInt("press_count", 3)
        set(v) = sp.edit().putInt("press_count", v).apply()

    var pressWindowMs: Long
        get() = sp.getLong("press_window_ms", 2000L)
        set(v) = sp.edit().putLong("press_window_ms", v).apply()

    /** How often to push a live location update during an active alert (seconds). */
    var locationIntervalSec: Int
        get() = sp.getInt("loc_interval_sec", 10)
        set(v) = sp.edit().putInt("loc_interval_sec", v).apply()

    /** Length of each uploaded audio clip during an active alert (seconds). */
    var audioClipSec: Int
        get() = sp.getInt("audio_clip_sec", 15)
        set(v) = sp.edit().putInt("audio_clip_sec", v).apply()

    var captureAudio: Boolean
        get() = sp.getBoolean("capture_audio", true)
        set(v) = sp.edit().putBoolean("capture_audio", v).apply()

    var guardianEnabled: Boolean
        get() = sp.getBoolean("guardian_enabled", false)
        set(v) = sp.edit().putBoolean("guardian_enabled", v).apply()

    fun isLoggedIn(): Boolean = !token.isNullOrEmpty()

    fun clearSession() {
        sp.edit().remove("token").remove("email").remove("device_id").apply()
    }

    companion object {
        // Android emulator reaches the host machine at 10.0.2.2.
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
    }
}
