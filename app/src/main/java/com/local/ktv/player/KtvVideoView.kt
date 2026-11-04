package com.local.ktv.player

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
/**
 * Kotlin port of the original MuseVideoView display structure: a FrameLayout
 * containing a ResizeSurfaceView. Playback lives in one shared engine, so page
 * and fullscreen changes only redirect the decoder output surface.
 */
class KtvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var surfaceView = KtvSurfaceView(context)
    private var mediaOverlay = false
    private var engine: KtvPlaybackEngine? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    fun bind(engine: KtvPlaybackEngine) {
        this.engine = engine
    }

    fun setMediaOverlay(enabled: Boolean) {
        mediaOverlay = enabled
        surfaceView.setZOrderMediaOverlay(enabled)
    }

    fun setVideoURI(uri: Uri) = engine?.setVideoUri(uri) ?: Unit
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) = engine?.setOnPreparedListener(listener) ?: Unit
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) = engine?.setOnCompletionListener(listener) ?: Unit
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) = engine?.setOnErrorListener(listener) ?: Unit
    fun start() = engine?.start() ?: Unit
    fun pause() = engine?.pause() ?: Unit
    fun seekTo(positionMs: Int) = engine?.seekTo(positionMs) ?: Unit
    fun stopPlayback() = engine?.stop() ?: Unit
    fun setPlaybackVolume(left: Float, right: Float) = engine?.setVolume(left, right) ?: Unit
    fun setTone(step: Int) = engine?.setTone(step) ?: Unit
    fun selectAudioTrack(original: Boolean): Boolean = engine?.selectAudioTrack(original) == true
    fun audioTrackCount(): Int = engine?.audioTrackCount() ?: 0
    fun selectAudioChannel(channel: Int, volume: Float) = engine?.selectAudioChannel(channel, volume) ?: Unit
    val isPlaying: Boolean get() = engine?.isPlaying == true
    val currentPosition: Int get() = engine?.currentPosition ?: 0
    val duration: Int get() = engine?.duration ?: 0

    internal fun updateVideoSize(width: Int, height: Int) {
        surfaceView.setVideoSize(width, height)
    }

    internal fun installSurfaceCallback(callback: SurfaceHolder.Callback) {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        surfaceCallback = callback
        surfaceView.holder.addCallback(callback)
    }

    internal fun currentHolder(): SurfaceHolder = surfaceView.holder

    /** Recreate the output surface when the host requests a fresh video layer. */
    internal fun recreateDecoderSurface() {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        removeView(surfaceView)
        surfaceView = KtvSurfaceView(context).also { it.setZOrderMediaOverlay(mediaOverlay) }
        addView(surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surfaceCallback?.let(surfaceView.holder::addCallback)
    }
}

/** Resize behavior ported from the original ResizeSurfaceView, scale mode 1. */
private class KtvSurfaceView(context: Context) : SurfaceView(context) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        if (width > 0 && height > 0) holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = getDefaultSize(videoWidth, widthMeasureSpec).coerceAtLeast(1)
        val maxHeight = getDefaultSize(videoHeight, heightMeasureSpec).coerceAtLeast(1)
        var measuredWidth = maxWidth
        var measuredHeight = measuredWidth * 9 / 16
        if (measuredHeight > maxHeight) {
            measuredHeight = maxHeight
            measuredWidth = measuredHeight * 16 / 9
        }
        setMeasuredDimension(measuredWidth.coerceAtLeast(1), measuredHeight.coerceAtLeast(1))
    }
}

/** Values are internal to the shared player and its existing controls. */
object AudioChannels {
    const val AUDIO_CHANNEL_LEFT = 0
    const val AUDIO_CHANNEL_RIGHT = 1
    const val AUDIO_CHANNEL_STEREO = 2
}
