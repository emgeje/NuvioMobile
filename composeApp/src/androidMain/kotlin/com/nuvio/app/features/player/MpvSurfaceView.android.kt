package com.nuvio.app.features.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal abstract class MpvSurfaceView(context: Context, private val vo: String) : FrameLayout(context) {
    private val mpvInstance = lazy { MPV(context) }
    val mpv by mpvInstance
    protected val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    protected val surfaceReady = CompletableDeferred<Unit>()
    protected val released = AtomicBoolean(false)
    private var destroyJob: Job? = null
    private var surfaceAttached = false

    protected abstract fun initOptions()
    protected abstract fun observeProperties()

    fun initialize(configDir: String, cacheDir: String) {
        mpv.setConfigDir(configDir)
        mpv.setCacheDir(cacheDir)
        mpv.setOptionString("vo", vo)
        initOptions()
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        observeProperties()
        addView(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createSurfaceView() else createTextureView())
    }

    protected fun executeMpv(block: () -> Unit) {
        if (released.get()) return
        mpvScope.launch {
            if (!released.get()) runCatching(block).onFailure(::logFailure)
        }
    }

    // Read and written on the mpv thread only.
    private var viewSurface: Surface? = null
    private var viewSurfaceSize: Pair<Int, Int>? = null
    private var externalSurface: Surface? = null

    /** Runs on the mpv thread when the video moves to or from an external (car) surface. */
    protected open fun onExternalSurfaceChanged(active: Boolean) = Unit

    private fun attach(surface: Surface) = executeMpv {
        viewSurface = surface
        if (externalSurface == null) attachSurface(surface)
    }

    private fun attachSurface(surface: Surface) {
        mpv.attachSurface(surface)
        surfaceAttached = true
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", vo)
        surfaceReady.complete(Unit)
    }

    private fun resize(width: Int, height: Int) = executeMpv {
        viewSurfaceSize = width to height
        if (externalSurface == null) setSurfaceSize(width, height)
    }

    private fun setSurfaceSize(width: Int, height: Int) {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    private fun detach(releaseSurface: () -> Unit) {
        val job = destroyJob ?: mpvScope.launch {
            // While the video is on an external surface the view's surface is not attached to mpv.
            if (externalSurface == null) detachSurface()
            viewSurface = null
            viewSurfaceSize = null
        }
        job.invokeOnCompletion { releaseSurface() }
    }

    /** Renders the video into [surface] (on the car display) instead of this view until [detachExternalSurface]. */
    fun attachExternalSurface(surface: Surface, width: Int, height: Int) = executeMpv {
        if (externalSurface === surface) return@executeMpv
        detachSurface()
        externalSurface = surface
        attachSurface(surface)
        setSurfaceSize(width, height)
        onExternalSurfaceChanged(true)
    }

    fun resizeExternalSurface(width: Int, height: Int) = executeMpv {
        if (externalSurface != null) setSurfaceSize(width, height)
    }

    /** Moves the video back to this view; [onDetached] runs once mpv no longer uses the external surface. */
    fun detachExternalSurface(onDetached: () -> Unit) {
        if (released.get()) {
            val job = destroyJob
            if (job == null) onDetached() else job.invokeOnCompletion { onDetached() }
            return
        }
        val job = mpvScope.launch {
            if (externalSurface == null) return@launch
            detachSurface()
            externalSurface = null
            runCatching {
                onExternalSurfaceChanged(false)
                if (released.get()) return@runCatching
                viewSurface?.let { surface ->
                    attachSurface(surface)
                    viewSurfaceSize?.let { (width, height) -> setSurfaceSize(width, height) }
                }
            }.onFailure(::logFailure)
        }
        job.invokeOnCompletion { onDetached() }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        runCatching {
            try {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
            } finally {
                mpv.detachSurface()
            }
        }.onFailure(::logFailure)
    }

    fun releaseMpv() {
        if (!released.compareAndSet(false, true)) return
        surfaceReady.cancel()
        destroyJob = mpvScope.launch {
            try {
                detachSurface()
                if (mpvInstance.isInitialized()) runCatching { mpv.close() }.onFailure(::logFailure)
            } finally {
                mpvDispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSurfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private lateinit var output: Surface

            override fun surfaceCreated(holder: SurfaceHolder) {
                val frame = holder.surfaceFrame
                layer = SurfaceControl.Builder()
                    .setName("MPV video")
                    .setParent(surfaceControl)
                    .setBufferSize(frame.width(), frame.height())
                    .build()
                output = Surface(layer)
                SurfaceControl.Transaction().use {
                    it.setLayer(layer, 1).setVisibility(layer, true).apply()
                }
                attach(output)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val oldLayer = layer
                val oldOutput = output
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                detach {
                    oldOutput.release()
                    oldLayer.release()
                }
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                output = Surface(texture)
                attach(output)
                resize(width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldOutput = output
                detach {
                    oldOutput.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun logFailure(error: Throwable) {
        Log.w("MpvSurfaceView", "libmpv operation failed", error)
    }
}
