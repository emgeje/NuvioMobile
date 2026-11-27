package com.nuvio.app.features.player.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows the playing video on a car display surface (from Android Auto).
 *
 * The surface backs a private virtual display. A [Presentation] on it lays out a [TextureView] in
 * the video's aspect ratio, either fitted inside the screen or filling it (cropping the edges),
 * and the player engine renders into that TextureView through [CarVideoOutput].
 */
class CarVideoRenderer(
    context: Context,
    surface: Surface,
    width: Int,
    height: Int,
    densityDpi: Int,
    fill: Boolean,
    videoVisible: Boolean,
) {
    private val virtualDisplay: VirtualDisplay
    private val presentation: CarVideoPresentation
    private var released = false

    init {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        virtualDisplay = displayManager.createVirtualDisplay(
            "NuvioCarVideo",
            width,
            height,
            densityDpi,
            surface,
            0,
        )
        presentation = CarVideoPresentation(context, virtualDisplay, fill, videoVisible)
        presentation.show()
    }

    var fill: Boolean
        get() = presentation.fill
        set(value) {
            presentation.fill = value
        }

    /**
     * Whether the video is shown. When false the screen is drawn black and the player goes back to
     * the phone; the display stays alive so that no stale video frame is left on the car screen.
     */
    var videoVisible: Boolean
        get() = presentation.videoVisible
        set(value) {
            presentation.videoVisible = value
        }

    /** The part of the screen not covered by the car's own UI; used to keep the fitted video uncovered. */
    fun setVisibleArea(area: Rect?) {
        presentation.visibleArea = area
    }

    fun release() {
        if (released) return
        released = true
        runCatching { presentation.dismiss() }.onFailure { Log.w(TAG, "Failed to dismiss car presentation", it) }
        virtualDisplay.release()
    }

    private companion object {
        const val TAG = "CarVideoRenderer"
    }
}

private class CarVideoPresentation(
    context: Context,
    virtualDisplay: VirtualDisplay,
    initialFill: Boolean,
    initialVideoVisible: Boolean,
) : Presentation(context, virtualDisplay.display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private lateinit var videoLayout: VideoAspectLayout
    private lateinit var subtitleView: TextView
    private val playbackListener: (CarPlaybackState) -> Unit = { state ->
        if (::videoLayout.isInitialized) videoLayout.setVideoSize(state.videoWidth, state.videoHeight)
    }
    private val subtitleListener: (String) -> Unit = { text ->
        if (::subtitleView.isInitialized) {
            subtitleView.text = text
            subtitleView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    var fill: Boolean = initialFill
        set(value) {
            field = value
            if (::videoLayout.isInitialized) videoLayout.fill = value
        }

    var visibleArea: Rect? = null
        set(value) {
            field = value
            if (::videoLayout.isInitialized) videoLayout.visibleArea = value
        }

    var videoVisible: Boolean = initialVideoVisible
        set(value) {
            if (field == value) return
            field = value
            if (::videoLayout.isInitialized) syncVideoVisibility()
        }

    /** Adds or removes the TextureView; removing it takes the surface away from the player at once. */
    private fun syncVideoVisibility() {
        if (videoVisible) {
            if (videoLayout.childCount == 0) videoLayout.addView(createTextureView())
            subtitleView.alpha = 1f
        } else {
            videoLayout.removeAllViews()
            subtitleView.alpha = 0f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        videoLayout = VideoAspectLayout(context).apply {
            fill = this@CarVideoPresentation.fill
            visibleArea = this@CarVideoPresentation.visibleArea
        }
        root.addView(videoLayout, FrameLayout.LayoutParams(MATCH, MATCH))
        subtitleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setBackgroundColor(0x80000000.toInt())
            val padding = (8 * context.resources.displayMetrics.density).roundToInt()
            setPadding(padding * 2, padding, padding * 2, padding)
            visibility = View.GONE
        }
        root.addView(
            subtitleView,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (32 * context.resources.displayMetrics.density).roundToInt()
            },
        )
        setContentView(root)
        syncVideoVisibility()
    }

    override fun onStart() {
        super.onStart()
        CarVideoOutput.addPlaybackListener(playbackListener)
        CarVideoOutput.addSubtitleListener(subtitleListener)
    }

    override fun onStop() {
        CarVideoOutput.removePlaybackListener(playbackListener)
        CarVideoOutput.removeSubtitleListener(subtitleListener)
        super.onStop()
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private var output: Surface? = null

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(texture)
                output = surface
                CarVideoOutput.offerTarget(CarVideoTarget(surface, width, height))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                CarVideoOutput.resizeTarget(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val surface = output
                output = null
                // The engine may still be drawing; release the texture only once it has let go.
                CarVideoOutput.withdrawTarget {
                    surface?.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Sizes its single child to the video's aspect ratio: fitted inside, or filling and cropped. */
private class VideoAspectLayout(context: Context) : FrameLayout(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    var fill: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var visibleArea: Rect? = null
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        clipChildren = true
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width == videoWidth && height == videoHeight) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    private fun bounds(width: Int, height: Int): Rect {
        // Fitted video stays inside the area the car's UI does not cover; filled video uses the whole screen.
        val area = visibleArea
        return if (!fill && area != null && !area.isEmpty) Rect(area) else Rect(0, 0, width, height)
    }

    private fun childRect(width: Int, height: Int): Rect {
        val box = bounds(width, height)
        if (videoWidth <= 0 || videoHeight <= 0 || box.isEmpty) return box
        val videoAspect = videoWidth.toFloat() / videoHeight
        val boxAspect = box.width().toFloat() / box.height()
        val widthLimited = if (fill) videoAspect < boxAspect else videoAspect > boxAspect
        val childWidth: Int
        val childHeight: Int
        if (widthLimited) {
            childWidth = box.width()
            childHeight = (box.width() / videoAspect).roundToInt()
        } else {
            childHeight = box.height()
            childWidth = (box.height() * videoAspect).roundToInt()
        }
        val left = box.left + (box.width() - childWidth) / 2
        val top = box.top + (box.height() - childHeight) / 2
        return Rect(left, top, left + childWidth, top + childHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val rect = childRect(width, height)
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rect = childRect(right - left, bottom - top)
        for (index in 0 until childCount) {
            getChildAt(index).layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }
}
