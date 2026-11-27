package com.nuvio.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Tells whether the car is parked.
 *
 * Some cars hand apps a fixed, meaningless speed, so the phone's GPS speed is the main source: the
 * car counts as parked only while a fresh GPS reading shows it standing still, and only after it has
 * done so for [PARKED_CONFIRM_MS]. Movement seen by either the GPS or the car ends that at once.
 * Without a fresh GPS reading the car never counts as parked.
 */
internal class ParkingMonitor(
    private val carContext: CarContext,
    private val onStateChanged: () -> Unit,
    private val onReadingsChanged: () -> Unit = {},
) {
    enum class State { Unknown, PermissionDenied, SpeedUnavailable, Moving, Parked }

    var state: State = State.Unknown
        private set

    /** Why the speed is unavailable, shown on the car screen to help diagnose it. */
    var detail: String? = null
        private set

    /** Latest speed from the car, in m/s, or null when it has none or it is stale. */
    val carSpeedMps: Float?
        get() = carSpeed.takeIf { isFresh(carSpeedAtMs) }

    /** Latest GPS speed, in m/s, or null when there is no fresh fix. */
    val gpsSpeedMps: Float?
        get() = gpsSpeed.takeIf { isFresh(gpsSpeedAtMs) }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var carInfo: CarInfo? = null
    private var locationManager: LocationManager? = null
    private var carSpeed: Float? = null
    private var carSpeedAtMs = 0L
    private var gpsSpeed: Float? = null
    private var gpsSpeedAtMs = 0L
    private var standstillSinceMs = 0L

    private val speedListener = OnCarDataAvailableListener<Speed> { speed -> onCarSpeed(speed) }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = evaluate()

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    // Re-evaluates every second so that stale readings end the parked state on their own.
    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        started = true
        val missing = PERMISSIONS.filterNot(::hasPermission)
        if (missing.isEmpty()) {
            listen()
            return
        }
        // These are runtime permissions; Android Auto asks for them on the phone.
        try {
            carContext.requestPermissions(missing) { _, _ ->
                if (!started) return@requestPermissions
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    listen()
                } else {
                    update(State.PermissionDenied, "location permission denied")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not request permissions", error)
            update(State.PermissionDenied, "permission request failed: ${error.javaClass.simpleName}")
        }
    }

    fun stop() {
        started = false
        handler.removeCallbacks(tick)
        runCatching { carInfo?.removeSpeedListener(speedListener) }
        carInfo = null
        runCatching { locationManager?.removeUpdates(locationListener) }
        locationManager = null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(carContext, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (locationManager == null) {
            try {
                val manager = carContext.getSystemService(LocationManager::class.java)
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
                locationManager = manager
            } catch (error: Exception) {
                Log.w(TAG, "GPS is not available", error)
            }
        }
        if (carInfo == null && hasPermission(CAR_SPEED_PERMISSION)) {
            try {
                val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
                info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
                carInfo = info
            } catch (error: Exception) {
                Log.w(TAG, "Car speed is not available", error)
            }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onCarSpeed(speed: Speed) {
        val readings = listOf(speed.rawSpeedMetersPerSecond, speed.displaySpeedMetersPerSecond)
            .filter { it.status == CarValue.STATUS_SUCCESS }
            .mapNotNull { it.value?.let(::abs) }
        carSpeed = readings.maxOrNull()
        carSpeedAtMs = SystemClock.elapsedRealtime()
        evaluate()
    }

    private fun onLocation(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER || !location.hasSpeed()) return
        gpsSpeed = abs(location.speed)
        gpsSpeedAtMs = SystemClock.elapsedRealtime()
        evaluate()
    }

    private fun evaluate() {
        onReadingsChanged()
        val now = SystemClock.elapsedRealtime()
        val car = carSpeedMps
        val gps = gpsSpeedMps
        val moving = (car != null && car > CAR_STANDSTILL_MAX_MPS) || (gps != null && gps > GPS_STANDSTILL_MAX_MPS)
        when {
            moving -> {
                standstillSinceMs = 0L
                update(State.Moving)
            }
            gps == null -> {
                standstillSinceMs = 0L
                update(State.SpeedUnavailable, "no GPS speed")
            }
            else -> {
                if (standstillSinceMs == 0L) standstillSinceMs = now
                if (now - standstillSinceMs >= PARKED_CONFIRM_MS) {
                    update(State.Parked)
                } else if (state != State.Parked) {
                    update(State.Moving)
                }
            }
        }
    }

    private fun isFresh(atMs: Long): Boolean =
        atMs != 0L && SystemClock.elapsedRealtime() - atMs <= READING_MAX_AGE_MS

    private fun update(newState: State, newDetail: String? = null) {
        if (state == newState && detail == newDetail) return
        if (newDetail != null) Log.i(TAG, "Parking: $newState ($newDetail)")
        state = newState
        detail = newDetail
        onStateChanged()
    }

    private companion object {
        const val TAG = "NuvioParkingMonitor"
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            CAR_SPEED_PERMISSION,
        )
        const val CAR_STANDSTILL_MAX_MPS = 1.5f

        /** GPS speed jitters a little even when standing; 1.5 m/s is about 5 km/h. */
        const val GPS_STANDSTILL_MAX_MPS = 1.5f
        const val PARKED_CONFIRM_MS = 30_000L
        const val READING_MAX_AGE_MS = 5_000L
        const val GPS_INTERVAL_MS = 1_000L
        const val TICK_MS = 1_000L
    }
}
