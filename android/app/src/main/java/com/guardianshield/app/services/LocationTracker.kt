package com.guardianshield.app.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.guardianshield.app.Config

/**
 * Location tracker using Google's FusedLocationProviderClient.
 *
 * Provides periodic location updates and on-demand location requests.
 * Battery-efficient with configurable update interval.
 */
class LocationTracker(private val service: MainService) {

    companion object {
        private const val TAG = "GS_Location"
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var isRunning = false

    /**
     * Start periodic location tracking.
     */
    fun start() {
        if (isRunning) return

        // Check permissions
        if (ActivityCompat.checkSelfPermission(
                service, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(service)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            Config.locationIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(Config.locationIntervalMs / 2)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location: ${location.latitude}, ${location.longitude} (±${location.accuracy}m)")
                    service.emitLocation(location)
                }
            }
        }

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        isRunning = true
        Log.i(TAG, "Location tracking started (interval: ${Config.locationIntervalMs}ms)")
    }

    /**
     * Request an immediate high-accuracy location update.
     */
    fun requestNow() {
        if (ActivityCompat.checkSelfPermission(
                service, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient?.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY, null
        )?.addOnSuccessListener { location: Location? ->
            location?.let {
                Log.i(TAG, "On-demand location: ${it.latitude}, ${it.longitude}")
                service.emitLocation(it)
            }
        }
    }

    /**
     * Stop location tracking.
     */
    fun stop() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        isRunning = false
        Log.i(TAG, "Location tracking stopped")
    }
}
