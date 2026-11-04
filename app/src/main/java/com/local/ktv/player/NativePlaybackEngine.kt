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
/** FFmpeg owns probing, demuxing and both decoders; Android only presents decoded PCM/RGBA. */
internal class NativePlaybackEngine(context: Context, private val audioOnly: Boolean = false) : PlaybackBackend {
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
    override val renderedVideoFrames: Long get() = renderedFrames.get()
    override val renderedVideoPosition: Long get() = session?.renderedVideoPosition ?: -1L
    @Volatile override var hasRenderedFirstFrame = false
        private set

    val decoderMode: PlaybackDecoderMode get() = PlaybackDecoderMode.SOFTWARE
    override val decodedNonSilentSamples: Long get() = session?.nonSilentSamples ?: 0
    override val decoderSummary: String get() =
        "视频：${videoDecoder.ifBlank { if (audioOnly) "不启用" else "等待播放" }}\n" +
            "音频：${audioDecoder.ifBlank { "等待播放" }}\n解析：FFmpeg"
    fun isSoftwareDecoderAvailable(): Boolean = true
    override val isPlaying: Boolean get() = !released && playWhenReady &&
        session?.let { it.prepared && !it.completed } == true
    override val currentPosition: Int get() = session?.clockMillis()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    override val duration: Int get() = session?.duration?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0

    override fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    override fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completionListener = listener }
    override fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { errorListener = listener }
    override fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?) { recoveryListener = listener }

    override fun attach(view: KtvVideoView) {
        targetView = view
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

    override fun setVideoUri(uri: Uri) {
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
                    this@NativePlaybackEngine.width = width
                    this@NativePlaybackEngine.height = height
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

    override fun start() { if (!released) { playWhenReady = true; session?.play(true) } }
    override fun pause() { if (!released) { playWhenReady = false; session?.play(false) } }
    override fun seekTo(positionMs: Int) { if (!released) session?.seek(positionMs.coerceAtLeast(0).toLong()) }
    override fun stop() {
        if (released) return
        playWhenReady = false
        session?.close()
        session = null
        hasRenderedFirstFrame = false
    }
    override fun setVolume(left: Float, right: Float) {
        volumeLeft = left.coerceIn(0f, 1f); volumeRight = right.coerceIn(0f, 1f)
        session?.let { it.leftGain = volumeLeft; it.rightGain = volumeRight }
    }
    override fun setTone(step: Int) {
        tone = step.coerceIn(-5, 5)
        if (!released) session?.setPitch(tone)
    }
    override fun audioTrackCount(): Int = session?.audioTracks ?: 0
    override fun selectAudioTrack(original: Boolean): Boolean {
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
    override fun selectAudioChannel(channel: Int, volume: Float) {
        this.channel = channel
        session?.channel = channel
        setVolume(volume, volume)
    }
    override fun release() {
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