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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

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

    /** NextLib 0.9 can leave its CPU producer connected after release. */
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

/** PCM channel selection keeps the selected karaoke channel audible on both speakers. */
@UnstableApi
private class KaraokeAudioProcessor : BaseAudioProcessor() {
    @Volatile var leftGain = 1f
    @Volatile var rightGain = 1f
    @Volatile var channel = AudioChannels.AUDIO_CHANNEL_STEREO

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(format)
        }
        return format
    }

    override fun queueInput(input: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(input.remaining())
        input.order(ByteOrder.nativeOrder())
        val left = leftGain
        val right = rightGain
        val mode = channel
        fun scaled(sample: Short, gain: Float): Short =
            (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        while (input.remaining() >= channels * 2) {
            if (channels == 2) {
                val l = input.short
                val r = input.short
                val selected = when (mode) {
                    AudioChannels.AUDIO_CHANNEL_LEFT -> l
                    AudioChannels.AUDIO_CHANNEL_RIGHT -> r
                    else -> null
                }
                output.putShort(scaled(selected ?: l, left))
                output.putShort(scaled(selected ?: r, right))
            } else {
                repeat(channels) { index ->
                    output.putShort(scaled(input.short, if (index == 1) right else left))
                }
            }
        }
        output.flip()
    }
}

/** One Media3 engine retains the page/fullscreen surface and control contracts. */
@UnstableApi
class KtvPlaybackEngine(context: Context, private val audioOnly: Boolean = false) {
    private val appContext = context.applicationContext
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var audio = KaraokeAudioProcessor()
    private var targetView: KtvVideoView? = null
    private var prepared = false
    private var released = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var mediaGeneration = 0L
    private var recoveryPending = false
    private var recovering = false
    private var recoveryListener: ((Boolean) -> Unit)? = null
    private var selectedOriginal: Boolean? = null
    private var recovery = DecoderRecoveryState(PlaybackDecoderSettings.load(appContext))
    private var audioDecoder = ""
    private var videoDecoder = ""
    var hasRenderedFirstFrame = false
        private set
    private var player: ExoPlayer = createPlayer()

    val decoderMode: PlaybackDecoderMode get() = recovery.mode
    val decoderSummary: String get() =
        "视频：${videoDecoder.ifBlank { if (audioOnly) "不启用" else "等待播放" }}\n" +
            "音频：${audioDecoder.ifBlank { "等待播放" }}" +
            if (recovery.ignoreMatroskaCues) "\n解析：顺序读取（索引异常，暂不支持跳转）" else ""

    fun isSoftwareDecoderAvailable(): Boolean = FfmpegLibrary.isAvailable()

    private fun createPlayer(): ExoPlayer {
        val instance = ExoPlayer.Builder(appContext,
            KtvRenderersFactory(appContext, audio, recovery,
                recovery.mode != PlaybackDecoderMode.SYSTEM && isSoftwareDecoderAvailable()))
            .setTrackSelector(KtvTrackSelector(appContext))
            .setMediaSourceFactory(DefaultMediaSourceFactory(appContext,
                DefaultExtractorsFactory().setMatroskaExtractorFlags(
                    if (recovery.ignoreMatroskaCues) MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES else 0)))
            .build()
        if (audioOnly) instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        instance.addListener(object : Player.Listener {
            private val firstFrameTimeout = Runnable {
                if (released || player !== instance || hasRenderedFirstFrame || audioOnly ||
                    !instance.playWhenReady || instance.playbackState != Player.STATE_READY) return@Runnable
                val group = instance.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                    ?: return@Runnable
                val format = group.getTrackFormat(0)
                onPlayerError(ExoPlaybackException.createForRenderer(
                    IllegalStateException("解码器就绪后仍未输出视频首帧"),
                    if (videoDecoder.startsWith("ffmpeg", true)) "FfmpegVideoRenderer" else "MediaCodecVideoRenderer",
                    0, format, C.FORMAT_HANDLED, false, PlaybackException.ERROR_CODE_DECODING_FAILED))
            }

            private fun armFirstFrameCheck() {
                handler.removeCallbacks(firstFrameTimeout)
                if (!audioOnly && !hasRenderedFirstFrame && instance.playWhenReady &&
                    instance.playbackState == Player.STATE_READY) {
                    handler.postDelayed(firstFrameTimeout, 5000L)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!released && player === instance) armFirstFrameCheck()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (released || player !== instance) return
                armFirstFrameCheck()
                Log.d("KtvPlaybackEngine", "State=$state mode=${recovery.mode} playing=${instance.playWhenReady}")
                if (state == Player.STATE_READY && recovering) {
                    recovering = false
                    recoveryListener?.invoke(false)
                }
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    preparedListener?.onPrepared(null)
                } else if (state == Player.STATE_ENDED) {
                    completionListener?.onCompletion(null)
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!released && player === instance) selectedOriginal?.let(::selectAudioTrack)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (released || player !== instance) return
                videoWidth = size.width
                videoHeight = size.height
                targetView?.updateVideoSize(videoWidth, videoHeight)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released || player !== instance || recoveryPending) return
                val rendererError = error as? ExoPlaybackException
                val failedTrack = when (MimeTypes.getTrackType(rendererError?.rendererFormat?.sampleMimeType)) {
                    C.TRACK_TYPE_AUDIO -> DecoderFailureTrack.AUDIO
                    C.TRACK_TYPE_VIDEO -> DecoderFailureTrack.VIDEO
                    else -> DecoderFailureTrack.UNKNOWN
                }
                val path = instance.currentMediaItem?.localConfiguration?.uri?.path.orEmpty()
                val isMatroska = path.endsWith(".mkv", true) || path.endsWith(".webm", true)
                val containerRecovery = recovery.recoverContainer(error.errorCode, isMatroska,
                    instance.currentPosition, hasRenderedFirstFrame)
                val next = containerRecovery ?: recovery.recover(error.errorCode, failedTrack,
                    failedSoftware = rendererError?.rendererName?.startsWith("Ffmpeg") == true)
                if (next != null && (containerRecovery != null || isSoftwareDecoderAvailable())) {
                    // Post out of the player callback; reject recovery after stop/song changes.
                    recoveryPending = true
                    val generation = mediaGeneration
                    Log.w("KtvPlaybackEngine", "Playback recovery code=${error.errorCode} track=$failedTrack cueFallback=${next.ignoreMatroskaCues}")
                    handler.post {
                        if (released || generation != mediaGeneration || player !== instance) return@post
                        recoveryPending = false
                        try {
                            rebuildPlayer(next)
                        } catch (failure: RuntimeException) {
                            Log.e("KtvPlaybackEngine", "Decoder recovery failed", failure)
                            prepared = false
                            errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
                        }
                    }
                    return
                }
                prepared = false
                recovering = false
                instance.stop()
                Log.e("KtvPlaybackEngine", "Playback failed code=${error.errorCode}", error)
                errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
            }
        })
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (released || player !== instance) return
                hasRenderedFirstFrame = true
                Log.i("KtvPlaybackEngine", "First frame mode=${recovery.mode}")
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (released || player !== instance) return
                audioDecoder = decoderName
                Log.i("KtvPlaybackEngine", "Audio decoder=$decoderName")
            }

            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (released || player !== instance) return
                videoDecoder = decoderName
                Log.i("KtvPlaybackEngine", "Video decoder=$decoderName")
            }
        })
        return instance
    }

    /** Rebuild only the decoder chain, retaining user playback intent and karaoke controls. */
    private fun rebuildPlayer(next: DecoderRecoveryState) {
        val old = player
        val item = old.currentMediaItem
        val position = old.currentPosition.coerceAtLeast(0)
        val playWhenReady = old.playWhenReady
        val parameters = old.playbackParameters
        val selection = old.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        val replacementAudio = KaraokeAudioProcessor().also {
            it.leftGain = audio.leftGain
            it.rightGain = audio.rightGain
            it.channel = audio.channel
        }
        // Construct first, so native-load/construction errors leave the old player usable.
        val previousRecovery = recovery
        val previousAudio = audio
        recovery = next
        audio = replacementAudio
        val replacement = try {
            createPlayer()
        } catch (failure: RuntimeException) {
            recovery = previousRecovery
            audio = previousAudio
            throw failure
        }
        if (item != null) recoveryListener?.invoke(true)
        recovering = item != null
        old.clearVideoSurface()
        old.release()
        player = replacement
        if (!audioOnly) {
            targetView?.recreateDecoderSurface()
            targetView?.updateVideoSize(videoWidth, videoHeight)
        }
        audioDecoder = ""
        videoDecoder = ""
        hasRenderedFirstFrame = false
        replacement.trackSelectionParameters = selection
        replacement.playbackParameters = parameters
        attachCurrentSurface()
        if (item != null) {
            replacement.setMediaItem(item, position)
            replacement.prepare()
            replacement.playWhenReady = playWhenReady
        }
    }

    fun setDecoderMode(mode: PlaybackDecoderMode) {
        if (released) return
        check(mode != PlaybackDecoderMode.SOFTWARE || isSoftwareDecoderAvailable()) {
            "兼容解码库未能加载，请使用系统解码"
        }
        mediaGeneration++
        recoveryPending = false
        rebuildPlayer(DecoderRecoveryState(mode, ignoreMatroskaCues = recovery.ignoreMatroskaCues))
    }

    val isPlaying: Boolean get() = !released && player.isPlaying
    val currentPosition: Int get() = if (released) 0 else
        player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val duration: Int get() = if (released || player.duration == C.TIME_UNSET) 0 else
        player.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completionListener = listener }
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { errorListener = listener }
    fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?) { recoveryListener = listener }

    fun attach(view: KtvVideoView) {
        targetView = view
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(videoWidth, videoHeight)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view && !released) player.setVideoSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view && !released) player.clearVideoSurface(holder.surface)
            }
        })
        attachCurrentSurface()
    }

    private fun attachCurrentSurface() {
        val surface = targetView?.currentHolder()?.surface
        if (surface?.isValid == true && !released) player.setVideoSurface(surface)
    }

    fun setVideoUri(uri: Uri) {
        check(!released) { "播放器已释放" }
        recovering = false
        mediaGeneration++
        recoveryPending = false
        // A software recovery is scoped to one media item, never to the next song.
        val initial = DecoderRecoveryState(PlaybackDecoderSettings.load(appContext))
        if (recovery != initial) {
            player.stop()
            player.clearMediaItems()
            rebuildPlayer(initial)
        }
        prepared = false
        selectedOriginal = null
        audioDecoder = ""
        videoDecoder = ""
        hasRenderedFirstFrame = false
        videoWidth = 0
        videoHeight = 0
        targetView?.updateVideoSize(0, 0)
        player.playWhenReady = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun start() { if (!released) player.play() }
    fun pause() { if (!released) player.pause() }
    fun seekTo(positionMs: Int) { if (!released) player.seekTo(positionMs.coerceAtLeast(0).toLong()) }
    fun stop() {
        if (released) return
        mediaGeneration++
        recoveryPending = false
        recovering = false
        player.stop()
        player.clearMediaItems()
        prepared = false
        selectedOriginal = null
    }

    fun setVolume(left: Float, right: Float) {
        audio.leftGain = left.coerceIn(0f, 1f)
        audio.rightGain = right.coerceIn(0f, 1f)
    }

    fun setTone(step: Int) {
        if (!released) player.playbackParameters =
            PlaybackParameters(1f, 2.0.pow(step.coerceIn(-5, 5) / 12.0).toFloat())
    }

    fun audioTrackCount(): Int = if (released) 0 else
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }

    fun selectAudioTrack(original: Boolean): Boolean {
        if (released) return false
        val tracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { index -> group to index } }
        if (tracks.size < 2) return false
        selectedOriginal = original
        val (group, index) = tracks[if (original) 0 else 1]
        if (!group.isTrackSupported(index)) return false
        val parameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
        return true
    }

    fun selectAudioChannel(channel: Int, volume: Float) {
        audio.channel = channel
        setVolume(volume, volume)
    }

    fun release() {
        if (released) return
        released = true
        mediaGeneration++
        recoveryPending = false
        handler.removeCallbacksAndMessages(null)
        player.release()
        targetView = null
        preparedListener = null
        completionListener = null
        errorListener = null
        recoveryListener = null
    }
}
