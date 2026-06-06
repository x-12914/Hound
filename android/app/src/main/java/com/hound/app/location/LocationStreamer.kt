package com.hound.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Wraps FusedLocationProvider for one-shot fixes and continuous updates. */
class LocationStreamer(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(onResult: (Location?) -> Unit) {
        if (!hasPermission()) { onResult(null); return }
        client.lastLocation
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult(null) }
    }

    @SuppressLint("MissingPermission")
    fun start(intervalSec: Int, onLocation: (Location) -> Unit) {
        if (!hasPermission()) return
        stop()
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, intervalSec * 1000L,
        ).setMinUpdateIntervalMillis(intervalSec * 1000L / 2).build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocation)
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
