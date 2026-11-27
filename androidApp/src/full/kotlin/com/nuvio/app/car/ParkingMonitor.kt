package com.nuvio.app.car

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
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
 * Tells whether the car is parked, from the speed it reports.
 *
 * The car counts as parked only after it has stood still for [PARKED_CONFIRM_MS]; any movement
 * ends that at once. When the speed is unknown the car never counts as parked.
 */
internal class ParkingMonitor(
    private val carContext: CarContext,
    private val onStateChanged: () -> Unit,
    private val onSpeedReading: (metersPerSecond: Float?) -> Unit = {},
) {
    enum class State { Unknown, PermissionDenied, SpeedUnavailable, Moving, Parked }

    var state: State = State.Unknown
        private set

    /** Why the speed is unavailable, shown on the car screen to help diagnose a car that reports none. */
    var detail: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var carInfo: CarInfo? = null
    private var parkedPending = false
    private var started = false
    private val confirmParked = Runnable {
        parkedPending = false
        update(State.Parked)
    }
    private val speedListener = OnCarDataAvailableListener<Speed> { speed -> onSpeed(speed) }

    fun start() {
        started = true
        if (hasSpeedPermission()) {
            listen()
            return
        }
        // The speed permission is a runtime permission; Android Auto asks for it on the phone.
        try {
            carContext.requestPermissions(listOf(CAR_SPEED_PERMISSION)) { granted, _ ->
                if (!started) return@requestPermissions
                if (CAR_SPEED_PERMISSION in granted) {
                    listen()
                } else {
                    update(State.PermissionDenied, "permission denied")
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not request the car speed permission", error)
            update(State.PermissionDenied, "permission request failed: ${error.javaClass.simpleName}")
        }
    }

    fun stop() {
        started = false
        cancelPending()
        runCatching { carInfo?.removeSpeedListener(speedListener) }
        carInfo = null
    }

    private fun hasSpeedPermission(): Boolean =
        ContextCompat.checkSelfPermission(carContext, CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun listen() {
        if (carInfo != null) return
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
            carInfo = info
        } catch (error: Exception) {
            Log.w(TAG, "Car speed is not available", error)
            update(State.SpeedUnavailable, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private fun onSpeed(speed: Speed) {
        // Some cars report only the speed shown on the dashboard, not the raw one. When both are
        // reported, the higher one counts, so that either showing movement stops the video.
        val raw = speed.rawSpeedMetersPerSecond
        val display = speed.displaySpeedMetersPerSecond
        val metersPerSecond = listOf(raw, display)
            .filter { it.isSuccess() }
            .mapNotNull { it.value?.let(::abs) }
            .maxOrNull()
        onSpeedReading(metersPerSecond)
        when {
            metersPerSecond == null -> {
                cancelPending()
                update(
                    State.SpeedUnavailable,
                    "raw=${raw.status.statusName()} display=${display.status.statusName()}",
                )
            }
            metersPerSecond > STANDSTILL_MAX_SPEED_MPS -> {
                cancelPending()
                update(State.Moving)
            }
            state != State.Parked && !parkedPending -> {
                // Standing still: wait before treating it as parked, so a short stop does not start the video.
                update(State.Moving)
                parkedPending = true
                handler.postDelayed(confirmParked, PARKED_CONFIRM_MS)
            }
        }
    }

    private fun CarValue<Float>.isSuccess(): Boolean = status == CarValue.STATUS_SUCCESS && value != null

    private fun Int.statusName(): String = when (this) {
        CarValue.STATUS_SUCCESS -> "ok"
        CarValue.STATUS_UNIMPLEMENTED -> "unimplemented"
        CarValue.STATUS_UNAVAILABLE -> "unavailable"
        CarValue.STATUS_UNKNOWN -> "unknown"
        else -> "status $this"
    }

    private fun cancelPending() {
        parkedPending = false
        handler.removeCallbacks(confirmParked)
    }

    private fun update(newState: State, newDetail: String? = null) {
        if (newDetail != null) Log.i(TAG, "Car speed: $newState ($newDetail)")
        if (state == newState && detail == newDetail) return
        state = newState
        detail = newDetail
        onStateChanged()
    }

    private companion object {
        const val TAG = "NuvioParkingMonitor"
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        const val STANDSTILL_MAX_SPEED_MPS = 0.5f
        const val PARKED_CONFIRM_MS = 30_000L
    }
}
