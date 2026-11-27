package com.nuvio.app.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nuvio.android.R
import com.nuvio.app.features.player.car.CarPlaybackState
import com.nuvio.app.features.player.car.CarVideoOutput
import com.nuvio.app.features.player.car.CarVideoRenderer

/**
 * The Android Auto screen. While the car is parked it shows the video playing on the phone across
 * the whole car display (filled by default, fitted on request); while driving it shows only a
 * message and the playback buttons, and the video goes back to the phone with the audio playing on.
 */
internal class CarVideoScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val parking = ParkingMonitor(carContext) {
        updateVideo()
        invalidate()
    }
    private var playback: CarPlaybackState = CarVideoOutput.playbackState
    private val playbackListener: (CarPlaybackState) -> Unit = { newState ->
        val oldState = playback
        playback = newState
        if (oldState.hasPlayer != newState.hasPlayer || oldState.isPlaying != newState.isPlaying) invalidate()
    }
    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var renderer: CarVideoRenderer? = null
    private var fill = true
    private var hidden = false

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            releaseRenderer()
            this@CarVideoScreen.surfaceContainer = surfaceContainer
            updateVideo()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            releaseRenderer()
            this@CarVideoScreen.surfaceContainer = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@CarVideoScreen.visibleArea = visibleArea
            renderer?.setVisibleArea(visibleArea)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        CarVideoOutput.addPlaybackListener(playbackListener)
        parking.start()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        parking.stop()
        CarVideoOutput.removePlaybackListener(playbackListener)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        releaseRenderer()
    }

    override fun onGetTemplate(): Template {
        val showingVideo = renderer?.videoVisible == true
        val actions = ActionStrip.Builder()
        if (playback.hasPlayer) {
            actions.addAction(
                iconAction(if (playback.isPlaying) R.drawable.ic_car_pause else R.drawable.ic_car_play) {
                    CarVideoOutput.togglePlayPause()
                },
            )
            actions.addAction(iconAction(R.drawable.ic_car_rewind) { CarVideoOutput.seekBy(-REWIND_MS) })
        }
        if (showingVideo) {
            actions.addAction(
                iconAction(if (fill) R.drawable.ic_car_fit else R.drawable.ic_car_fill) {
                    fill = !fill
                    renderer?.fill = fill
                    invalidate()
                },
            )
        }
        actions.addAction(
            iconAction(if (hidden) R.drawable.ic_car_show else R.drawable.ic_car_hide) {
                hidden = !hidden
                updateVideo()
                invalidate()
            },
        )

        val template = NavigationTemplate.Builder().setActionStrip(actions.build())
        messageRes()?.let { message ->
            val info = MessageInfo.Builder(carContext.getString(message))
            if (parking.state == ParkingMonitor.State.SpeedUnavailable ||
                parking.state == ParkingMonitor.State.PermissionDenied
            ) {
                parking.detail?.let(info::setText)
            }
            template.setNavigationInfo(info.build())
        }
        return template.build()
    }

    private fun messageRes(): Int? = when {
        parking.state == ParkingMonitor.State.PermissionDenied -> R.string.car_message_speed_permission
        parking.state == ParkingMonitor.State.SpeedUnavailable -> R.string.car_message_speed_unavailable
        parking.state != ParkingMonitor.State.Parked -> R.string.car_message_driving
        !playback.hasPlayer -> R.string.car_message_no_player
        hidden -> R.string.car_message_hidden
        else -> null
    }

    // The navigation template's action strip allows at most one titled action, so all of them use icons.
    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()

    /**
     * Shows the video only while the car is parked. Otherwise the screen is drawn black rather than
     * released, so the last video frame does not stay on the car screen.
     */
    private fun updateVideo() {
        val showVideo = parking.state == ParkingMonitor.State.Parked && !hidden
        renderer?.let { current ->
            current.videoVisible = showVideo
            return
        }
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        renderer = runCatching {
            CarVideoRenderer(
                context = carContext.applicationContext,
                surface = surface,
                width = container.width,
                height = container.height,
                densityDpi = container.dpi,
                fill = fill,
                videoVisible = showVideo,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to show video on the car screen", error)
        }.getOrNull()?.also { created ->
            visibleArea?.let(created::setVisibleArea)
        }
    }

    private fun releaseRenderer() {
        renderer?.release()
        renderer = null
    }

    private companion object {
        const val TAG = "NuvioCarVideoScreen"
        const val REWIND_MS = 10_000L
    }
}
