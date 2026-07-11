package com.local.ktv.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo

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
    private val surfaceView = KtvSurfaceView(context)
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
    fun selectAudioTrack(original: Boolean): Boolean = engine?.selectAudioTrack(original) == true
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

class KtvPlaybackEngine(context: Context) {
    private var ijkPlayer: IjkMediaPlayer? = null
    private var targetView: KtvVideoView? = null
    private var prepared = false
    private var startWhenPrepared = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    private var videoWidth = 0
    private var videoHeight = 0

    val isPlaying: Boolean get() = runCatching { ijkPlayer?.isPlaying == true }.getOrDefault(false)
    val currentPosition: Int get() = runCatching {
        (ijkPlayer?.currentPosition ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)
    val duration: Int get() = runCatching {
        (ijkPlayer?.duration ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }.getOrDefault(0)

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
        preparedListener = listener
    }

    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
        completionListener = listener
    }

    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
        errorListener = listener
    }

    fun attach(view: KtvVideoView) {
        targetView = view
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(videoWidth, videoHeight)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view) setOutputHolder(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view) {
                    runCatching { ijkPlayer?.setDisplay(null) }
                }
            }
        })
        view.currentHolder().takeIf { it.surface?.isValid == true }?.let(::setOutputHolder)
    }

    fun setVideoUri(uri: Uri) {
        prepared = false
        startWhenPrepared = false
        val ijk = ensureIjkPlayer()
        if (ijk != null) {
            val firstAttempt = runCatching { prepareIjkPlayer(ijk, uri) }
            if (firstAttempt.isFailure) {
                android.util.Log.e("KtvPlaybackEngine", "IJK prepare failed; recreating player", firstAttempt.exceptionOrNull())
                releaseIjk()
                val retry = ensureIjkPlayer()
                val retryAttempt = retry?.let { runCatching { prepareIjkPlayer(it, uri) } }
                if (retryAttempt == null || retryAttempt.isFailure) {
                    android.util.Log.e(
                        "KtvPlaybackEngine",
                        "IJK recreate failed; platform fallback is disabled",
                        retryAttempt?.exceptionOrNull(),
                    )
                    prepared = false
                    errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                }
            }
        } else {
            prepared = false
            android.util.Log.e("KtvPlaybackEngine", "IJK unavailable; platform fallback is disabled")
            errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun prepareIjkPlayer(player: IjkMediaPlayer, uri: Uri) {
        player.reset()
        configure(player)
        targetView?.currentHolder()?.takeIf { it.surface?.isValid == true }?.let(player::setDisplay)
        val rawSource = if (uri.scheme.equals("file", true)) uri.path.orEmpty() else uri.toString()
        val source = rawSource.takeIf { uri.scheme.isNullOrEmpty() || uri.scheme.equals("file", true) }
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
            ?.let(ThunderLocalProxy::urlFor)
            ?: rawSource
        player.setDataSource(source)
        player.prepareAsync()
    }

    fun start() {
        if (!prepared) {
            startWhenPrepared = true
            return
        }
        runCatching { ijkPlayer?.start() }
    }

    fun pause() {
        startWhenPrepared = false
        runCatching {
            if (ijkPlayer?.isPlaying == true) ijkPlayer?.pause()
        }
    }

    fun seekTo(positionMs: Int) {
        val target = positionMs.coerceAtLeast(0)
        runCatching {
            ijkPlayer?.seekTo(target.toLong())
        }
    }

    fun stop() {
        startWhenPrepared = false
        prepared = false
        runCatching { ijkPlayer?.stop() }
    }

    fun setVolume(left: Float, right: Float) {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        runCatching { ijkPlayer?.setVolume(safeLeft, safeRight) }
    }

    fun selectAudioTrack(original: Boolean): Boolean = runCatching {
        val ijk = ijkPlayer
        if (ijk != null) {
            val audioTracks = ijk.trackInfo.indices.filter {
                ijk.trackInfo[it].trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
            }
            if (audioTracks.size < 2) return@runCatching false
            ijk.selectTrack(if (original) audioTracks[1] else audioTracks[0])
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    fun selectAudioChannel(channel: Int, volume: Float) {
        val safeVolume = volume.coerceIn(0f, 1f)
        val ijk = ijkPlayer
        if (ijk != null) {
            runCatching {
                ijk.setVolume(safeVolume, safeVolume)
                ijk.seletcAudioChannel(channel)
            }
            return
        }
        when (channel) {
            IjkMediaPlayer.AUDIO_CHANNEL_LEFT -> setVolume(safeVolume, 0f)
            IjkMediaPlayer.AUDIO_CHANNEL_RIGHT -> setVolume(0f, safeVolume)
            else -> setVolume(safeVolume, safeVolume)
        }
    }

    fun release() {
        startWhenPrepared = false
        prepared = false
        releaseIjk()
        targetView = null
    }

    private fun ensureIjkPlayer(): IjkMediaPlayer? {
        ijkPlayer?.let { return it }
        return runCatching { IjkMediaPlayer().also { ijkPlayer = it } }
            .onFailure { android.util.Log.e("KtvPlaybackEngine", "Cannot create IJK player", it) }
            .getOrNull()
    }

    private fun configure(player: IjkMediaPlayer) {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 2L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV16.toLong())
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 6_291_456L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1_048_576L)
        // Old KTV MPEG-TS files corrupt reference frames in emulator/device OMX decoders.
        // Keep the whole playback path on IJK's bundled FFmpeg decoder.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
        player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        player.setScreenOnWhilePlaying(true)
        player.setOnPreparedListener { preparedPlayer: IMediaPlayer ->
            prepared = true
            videoWidth = preparedPlayer.videoWidth
            videoHeight = preparedPlayer.videoHeight
            targetView?.updateVideoSize(videoWidth, videoHeight)
            preparedListener?.onPrepared(null)
            if (startWhenPrepared && !preparedPlayer.isPlaying) runCatching { preparedPlayer.start() }
        }
        player.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            videoWidth = width
            videoHeight = height
            targetView?.updateVideoSize(width, height)
        }
        player.setOnCompletionListener { completionListener?.onCompletion(null) }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            errorListener?.onError(null, what, extra) ?: true
        }
    }

    private fun setOutputHolder(holder: SurfaceHolder) {
        runCatching { ijkPlayer?.setDisplay(holder) }
    }

    private fun releaseIjk() {
        runCatching { ijkPlayer?.setDisplay(null) }
        runCatching { ijkPlayer?.release() }
        ijkPlayer = null
    }
}
