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

/** FFmpeg owns probing, demuxing and both decoders; Android only presents decoded PCM/RGBA. */
class KtvPlaybackEngine(context: Context, private val audioOnly: Boolean = false) {
    private val appContext = context.applicationContext
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KtvFFmpeg-control").apply { isDaemon = true }
    }
    private var targetView: KtvVideoView? = null
    private var session: NativeFfmpegSession? = null
    private var released = false
    private var playWhenReady = false
    private var volumeLeft = 1f
    private var volumeRight = 1f
    private var channel = AudioChannels.AUDIO_CHANNEL_STEREO
    private var tone = 0
    private var width = 0
    private var height = 0
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    private var recoveryListener: ((Boolean) -> Unit)? = null
    private val renderedFrames = java.util.concurrent.atomic.AtomicLong()
    internal val renderedVideoFrames: Long get() = renderedFrames.get()
    @Volatile var hasRenderedFirstFrame = false
        private set

    val decoderMode: PlaybackDecoderMode get() = PlaybackDecoderMode.SOFTWARE
    internal val decodedNonSilentSamples: Long get() = session?.nonSilentSamples ?: 0
    val decoderSummary: String get() =
        "视频：${videoDecoder.ifBlank { if (audioOnly) "不启用" else "等待播放" }}\n" +
            "音频：${audioDecoder.ifBlank { "等待播放" }}\n解析：FFmpeg"
    fun isSoftwareDecoderAvailable(): Boolean = true
    val isPlaying: Boolean get() = !released && playWhenReady &&
        session?.let { it.prepared && !it.completed } == true
    val currentPosition: Int get() = session?.clockMillis()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    val duration: Int get() = session?.duration?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completionListener = listener }
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { errorListener = listener }
    fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?) { recoveryListener = listener }

    fun attach(view: KtvVideoView) {
        targetView = view
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(width, height)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view && !released) session?.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view && !released) session?.setSurface(null)
            }
        })
        attachCurrentSurface()
    }

    private fun attachCurrentSurface() {
        val surface = targetView?.currentHolder()?.surface
        if (surface?.isValid == true && !released && !audioOnly) session?.setSurface(surface)
    }

    fun setVideoUri(uri: Uri) {
        check(!released) { "播放器已释放" }
        session?.close()
        session = null
        playWhenReady = false
        hasRenderedFirstFrame = false
        renderedFrames.set(0)
        width = 0; height = 0
        videoDecoder = ""; audioDecoder = ""
        targetView?.updateVideoSize(0, 0)
        lateinit var created: NativeFfmpegSession
        fun postCurrent(block: () -> Unit) {
            handler.post { if (!released && session === created) block() }
        }
        created = NativeFfmpegSession(appContext, audioOnly, executor, object : NativeFfmpegSession.Listener {
            override fun prepared(duration: Long, width: Int, height: Int, tracks: Int, video: String, audio: String) {
                postCurrent {
                    this@KtvPlaybackEngine.width = width
                    this@KtvPlaybackEngine.height = height
                    videoDecoder = if (video.isBlank()) "无视频轨道" else "FFmpeg / $video"
                    audioDecoder = if (audio.isBlank()) "无音频轨道" else "FFmpeg / $audio"
                    targetView?.updateVideoSize(width, height)
                    preparedListener?.onPrepared(null)
                }
            }
            override fun firstFrame() {
                renderedFrames.incrementAndGet()
                if (!hasRenderedFirstFrame) postCurrent { hasRenderedFirstFrame = true }
            }
            override fun completed() {
                postCurrent {
                    playWhenReady = false
                    completionListener?.onCompletion(null)
                }
            }
            override fun error(code: Int, message: String) {
                postCurrent {
                    playWhenReady = false
                    created.play(false)
                    errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, code)
                }
            }
        })
        session = created
        created.leftGain = volumeLeft; created.rightGain = volumeRight; created.channel = channel
        created.setPitch(tone)
        attachCurrentSurface()
        created.open(uri)
    }

    fun start() { if (!released) { playWhenReady = true; session?.play(true) } }
    fun pause() { if (!released) { playWhenReady = false; session?.play(false) } }
    fun seekTo(positionMs: Int) { if (!released) session?.seek(positionMs.coerceAtLeast(0).toLong()) }
    fun stop() {
        if (released) return
        playWhenReady = false
        session?.close()
        session = null
        hasRenderedFirstFrame = false
    }
    fun setVolume(left: Float, right: Float) {
        volumeLeft = left.coerceIn(0f, 1f); volumeRight = right.coerceIn(0f, 1f)
        session?.let { it.leftGain = volumeLeft; it.rightGain = volumeRight }
    }
    fun setTone(step: Int) {
        tone = step.coerceIn(-5, 5)
        if (!released) session?.setPitch(tone)
    }
    fun audioTrackCount(): Int = session?.audioTracks ?: 0
    fun selectAudioTrack(original: Boolean): Boolean {
        val current = session ?: return false
        if (current.audioTracks < 2) return false
        val index = if (original) 0 else 1
        if (selectedAudio != index || selectedSession !== current) {
            selectedAudio = index
            selectedSession = current
            current.seek(current.clockMillis(), index)
        }
        return true
    }
    private var selectedAudio = 0
    private var selectedSession: NativeFfmpegSession? = null
    fun selectAudioChannel(channel: Int, volume: Float) {
        this.channel = channel
        session?.channel = channel
        setVolume(volume, volume)
    }
    fun release() {
        if (released) return
        stop()
        released = true
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        targetView = null
        selectedSession = null
        preparedListener = null; completionListener = null; errorListener = null; recoveryListener = null
    }
}
