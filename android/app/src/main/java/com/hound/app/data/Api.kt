package com.hound.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny synchronous API client over OkHttp + org.json. Call from a background
 * thread / coroutine (Dispatchers.IO). Throws ApiException on non-2xx.
 */
class Api(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    class ApiException(val code: Int, message: String) : Exception(message)

    private val jsonMedia = "application/json".toMediaType()

    private fun url(path: String) = prefs.baseUrl.trimEnd('/') + path

    private fun authedBuilder(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        prefs.token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun execJson(request: Request): JSONObject {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, parseError(body, resp.code))
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun parseError(body: String, code: Int): String =
        try { JSONObject(body).optString("detail", "HTTP $code") }
        catch (e: Exception) { "HTTP $code" }

    /** Returns the access token; also stores it + email into prefs. */
    fun login(email: String, password: String): String {
        val payload = JSONObject().put("email", email).put("password", password)
        val req = Request.Builder()
            .url(url("/api/auth/login-json"))
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val obj = execJson(req)
        val token = obj.getString("access_token")
        prefs.token = token
        prefs.email = email
        return token
    }

    fun register(email: String, password: String, fullName: String): String {
        val payload = JSONObject()
            .put("email", email).put("password", password).put("full_name", fullName)
        val req = Request.Builder()
            .url(url("/api/auth/register"))
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        val obj = execJson(req)
        val token = obj.getString("access_token")
        prefs.token = token
        prefs.email = email
        return token
    }

    /** Registers/updates this device, returns the server device id. */
    fun registerDevice(name: String): Int {
        val payload = JSONObject()
            .put("install_id", prefs.installId)
            .put("name", name)
            .put("platform", "android")
        val req = authedBuilder("/api/devices")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        val obj = execJson(req)
        val id = obj.getInt("id")
        prefs.deviceId = id
        return id
    }

    /** Creates an alert with an optional first location. Returns alert id. */
    fun createAlert(deviceId: Int, lat: Double?, lng: Double?, accuracy: Float?): Int {
        val payload = JSONObject().put("device_id", deviceId).put("note", "panic trigger")
        if (lat != null && lng != null) {
            payload.put("location", JSONObject().apply {
                put("lat", lat); put("lng", lng)
                if (accuracy != null) put("accuracy_m", accuracy.toDouble())
            })
        }
        val req = authedBuilder("/api/alerts")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return execJson(req).getInt("id")
    }

    fun pushLocation(alertId: Int, lat: Double, lng: Double, accuracy: Float?, speed: Float?) {
        val payload = JSONObject().put("lat", lat).put("lng", lng)
        if (accuracy != null) payload.put("accuracy_m", accuracy.toDouble())
        if (speed != null) payload.put("speed_mps", speed.toDouble())
        val req = authedBuilder("/api/alerts/$alertId/locations")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        execJson(req)
    }

    fun uploadAudio(alertId: Int, file: File, durationSec: Double) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("audio/aac".toMediaType()),
            )
            .addFormDataPart("duration_s", durationSec.toString())
            .build()
        val req = authedBuilder("/api/alerts/$alertId/audio").post(body).build()
        execJson(req)
    }

    fun updateStatus(alertId: Int, status: String) {
        val payload = JSONObject().put("status", status)
        val req = authedBuilder("/api/alerts/$alertId/status")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        execJson(req)
    }
}
