package com.nuvio.app.features.player.car

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot

/** A surface on the car display that the active player engine may render its video into. */
class CarVideoTarget internal constructor(
    val surface: Surface,
    val width: Int,
    val height: Int,
)

/** Implemented by a player engine that can move its video output onto the car display. */
internal interface CarVideoSink {
    /** Called on the main thread: render the video into [target] instead of the phone's view. */
    fun attachCarSurface(target: CarVideoTarget)

    /** Called on the main thread when the size of the attached car surface changes. */
    fun resizeCarSurface(width: Int, height: Int) {}

    /**
     * Called on the main thread: stop rendering into the car surface and go back to the phone's view.
     * [onDetached] runs, on any thread, once the engine no longer touches the car surface.
     */
    fun detachCarSurface(onDetached: () -> Unit)
}

data class CarPlaybackState(
    val hasPlayer: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * Connects the player that is playing on the phone with the Android Auto screen.
 *
 * The car side (only in the full distribution) offers a surface with [offerTarget] while the car is
 * parked and withdraws it with [withdrawTarget] when the car starts moving or the screen goes away.
 * The player engine registers itself as a [CarVideoSink] and moves its video output accordingly;
 * audio keeps playing either way. All functions must be called on the main thread.
 */
object CarVideoOutput {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: CarVideoSink? = null
    private var controller: PlayerEngineController? = null
    private var target: CarVideoTarget? = null
    private var state = CarPlaybackState()
    private val playbackListeners = LinkedHashSet<(CarPlaybackState) -> Unit>()
    private val subtitleListeners = LinkedHashSet<(String) -> Unit>()
    private var subtitleText = ""

    val playbackState: CarPlaybackState
        get() = state

    // region Car side

    fun offerTarget(newTarget: CarVideoTarget) {
        checkMainThread()
        target = newTarget
        sink?.attachCarSurface(newTarget)
    }

    fun resizeTarget(width: Int, height: Int) {
        checkMainThread()
        val current = target ?: return
        if (current.width == width && current.height == height) return
        target = CarVideoTarget(current.surface, width, height)
        sink?.resizeCarSurface(width, height)
    }

    /** Takes the car surface away from the player; [onReleased] runs on the main thread once it is safe to release it. */
    fun withdrawTarget(onReleased: () -> Unit) {
        checkMainThread()
        target = null
        val current = sink
        if (current == null) {
            onReleased()
        } else {
            current.detachCarSurface { runOnMain(onReleased) }
        }
    }

    fun addPlaybackListener(listener: (CarPlaybackState) -> Unit) {
        checkMainThread()
        playbackListeners += listener
        listener(state)
    }

    fun removePlaybackListener(listener: (CarPlaybackState) -> Unit) {
        playbackListeners -= listener
    }

    fun addSubtitleListener(listener: (String) -> Unit) {
        checkMainThread()
        subtitleListeners += listener
        listener(subtitleText)
    }

    fun removeSubtitleListener(listener: (String) -> Unit) {
        subtitleListeners -= listener
    }

    fun togglePlayPause() {
        val current = controller ?: return
        if (state.isPlaying) current.pause() else current.play()
    }

    fun seekBy(offsetMs: Long) {
        controller?.seekBy(offsetMs)
    }

    // endregion

    // region Player side

    internal fun registerSink(newSink: CarVideoSink) {
        checkMainThread()
        if (sink === newSink) return
        val previous = sink
        sink = newSink
        publish(state.copy(hasPlayer = true))
        val current = target ?: return
        if (previous == null) {
            newSink.attachCarSurface(current)
        } else {
            previous.detachCarSurface {
                runOnMain {
                    if (sink === newSink && target === current) newSink.attachCarSurface(current)
                }
            }
        }
    }

    internal fun unregisterSink(oldSink: CarVideoSink) {
        checkMainThread()
        if (sink !== oldSink) return
        sink = null
        controller = null
        publishSubtitle("")
        publish(CarPlaybackState())
    }

    internal fun updateController(newController: PlayerEngineController) {
        runOnMain { if (sink != null) controller = newController }
    }

    internal fun onSnapshot(snapshot: PlayerPlaybackSnapshot) {
        runOnMain {
            if (sink == null) return@runOnMain
            publish(
                CarPlaybackState(
                    hasPlayer = true,
                    isPlaying = snapshot.isPlaying,
                    isLoading = snapshot.isLoading,
                    videoWidth = snapshot.videoWidth,
                    videoHeight = snapshot.videoHeight,
                ),
            )
        }
    }

    internal fun onSubtitleText(text: String) {
        runOnMain { if (sink != null) publishSubtitle(text) }
    }

    // endregion

    private fun publish(newState: CarPlaybackState) {
        if (newState == state) return
        state = newState
        playbackListeners.toList().forEach { it(newState) }
    }

    private fun publishSubtitle(text: String) {
        if (text == subtitleText) return
        subtitleText = text
        subtitleListeners.toList().forEach { it(text) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "CarVideoOutput must be used on the main thread" }
    }
}
