package com.hound.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

    /** Text the emergency contacts directly when there's no internet. */
    var smsFallback: Boolean
        get() = sp.getBoolean("sms_fallback", true)
        set(v) = sp.edit().putBoolean("sms_fallback", v).apply()

    /** How often to re-send a location SMS while still offline (minutes). */
    var smsUpdateMin: Int
        get() = sp.getInt("sms_update_min", 2)
        set(v) = sp.edit().putInt("sms_update_min", v).apply()

    /** Name used in the SMS ("SOS! <name> triggered an emergency"). */
    var ownerName: String?
        get() = sp.getString("owner_name", null)
        set(v) = sp.edit().putString("owner_name", v).apply()

    /** Emergency contacts cached locally so SMS works with no internet. */
    fun setContacts(list: List<ContactInput>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("phone", c.phone)
                    .put("relation", c.relation)
            )
        }
        sp.edit().putString("contacts", arr.toString()).apply()
    }

    fun getContacts(): List<ContactInput> {
        val raw = sp.getString("contacts", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ContactInput(o.optString("name"), o.optString("phone"), o.optString("relation"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    var guardianEnabled: Boolean
        get() = sp.getBoolean("guardian_enabled", false)
        set(v) = sp.edit().putBoolean("guardian_enabled", v).apply()

    fun isLoggedIn(): Boolean = !token.isNullOrEmpty()

    fun clearSession() {
        sp.edit().remove("token").remove("email").remove("device_id").apply()
    }

    companion object {
        // Production server, pre-filled so users don't type it.
        const val DEFAULT_BASE_URL = "https://hound.157.250.205.174.nip.io"
    }
}
