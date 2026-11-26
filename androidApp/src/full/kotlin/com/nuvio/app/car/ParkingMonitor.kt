package com.nuvio.app.car

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
) {
    enum class State { Unknown, SpeedUnavailable, Moving, Parked }

    var state: State = State.Unknown
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var carInfo: CarInfo? = null
    private var parkedPending = false
    private val confirmParked = Runnable {
        parkedPending = false
        update(State.Parked)
    }
    private val speedListener = OnCarDataAvailableListener<Speed> { speed -> onSpeed(speed) }

    fun start() {
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
            carInfo = info
        } catch (error: Exception) {
            Log.w(TAG, "Car speed is not available", error)
            update(State.SpeedUnavailable)
        }
    }

    fun stop() {
        cancelPending()
        runCatching { carInfo?.removeSpeedListener(speedListener) }
        carInfo = null
    }

    private fun onSpeed(speed: Speed) {
        val reading = speed.rawSpeedMetersPerSecond
        val metersPerSecond = reading.value
        when {
            reading.status != CarValue.STATUS_SUCCESS || metersPerSecond == null -> {
                cancelPending()
                update(State.SpeedUnavailable)
            }
            abs(metersPerSecond) > STANDSTILL_MAX_SPEED_MPS -> {
                cancelPending()
                update(State.Moving)
            }
            state != State.Parked && !parkedPending -> {
                // Standing still: wait before treating it as parked, so a short stop does not start the video.
                if (state != State.Moving) update(State.Moving)
                parkedPending = true
                handler.postDelayed(confirmParked, PARKED_CONFIRM_MS)
            }
        }
    }

    private fun cancelPending() {
        parkedPending = false
        handler.removeCallbacks(confirmParked)
    }

    private fun update(newState: State) {
        if (state == newState) return
        state = newState
        onStateChanged()
    }

    private companion object {
        const val TAG = "NuvioParkingMonitor"
        const val STANDSTILL_MAX_SPEED_MPS = 0.5f
        const val PARKED_CONFIRM_MS = 30_000L
    }
}
