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
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
    fun setTone(step: Int) = engine?.setTone(step) ?: Unit
    fun selectAudioTrack(original: Boolean): Boolean = engine?.selectAudioTrack(original) == true
    fun audioTrackCount(): Int = engine?.audioTrackCount() ?: 0
    fun selectAudioChannel(channel: Int, volume: Float) = engine?.selectAudioChannel(channel, volume) ?: Unit
    val isPlaying: Boolean get() = engine?.isPlaying == true
    val currentPosition: Int get() = engine?.currentPosition ?: 0
    val duration: Int get() = engine?.duration ?: 0

    fun setScaleMode(mode: String) = surfaceView.setScaleMode(mode)

    internal fun updateVideoSize(width: Int, height: Int, pixelRatio: Float = 1f) {
        surfaceView.setVideoSize(width, height, pixelRatio)
    }

    internal fun installSurfaceCallback(callback: SurfaceHolder.Callback) {
        surfaceCallback?.let(surfaceView.holder::removeCallback)
        surfaceCallback = callback
        surfaceView.holder.addCallback(callback)
    }

    internal fun currentHolder(): SurfaceHolder = surfaceView.holder
}

/** Surface buffers retain source resolution; view bounds apply the selected display ratio. */
private class KtvSurfaceView(context: Context) : SurfaceView(context) {
    private var videoWidth = 0
    private var videoHeight = 0
    private var pixelRatio = 1f
    private var scaleMode = "适应屏幕"

    fun setVideoSize(width: Int, height: Int, pixelRatio: Float) {
        videoWidth = width
        videoHeight = height
        this.pixelRatio = pixelRatio
        if (width > 0 && height > 0) holder.setFixedSize(width, height)
        requestLayout()
    }

    fun setScaleMode(mode: String) {
        scaleMode = mode
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = getDefaultSize(videoWidth, widthMeasureSpec).coerceAtLeast(1)
        val maxHeight = getDefaultSize(videoHeight, heightMeasureSpec).coerceAtLeast(1)
        val size = VideoGeometry.measure(maxWidth, maxHeight, videoWidth, videoHeight, pixelRatio, scaleMode)
        setMeasuredDimension(size.width, size.height)
        Log.d("KtvVideoView", "Video layout mode=$scaleMode source=${videoWidth}x$videoHeight " +
            "pixelRatio=$pixelRatio bounds=${size.width}x${size.height}")
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
class KtvPlaybackEngine(context: Context, audioOnly: Boolean = false) {
    private val audio = KaraokeAudioProcessor()
    private var targetView: KtvVideoView? = null
    private var prepared = false
    private var released = false
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelRatio = 1f
    private val player: ExoPlayer

    init {
        val factory = object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                        enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessors(arrayOf(audio))
                    .build()
        }.setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context.applicationContext, factory).build()
        if (audioOnly) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    preparedListener?.onPrepared(null)
                } else if (state == Player.STATE_ENDED) {
                    completionListener?.onCompletion(null)
                }
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width
                videoHeight = size.height
                videoPixelRatio = size.pixelWidthHeightRatio
                targetView?.updateVideoSize(videoWidth, videoHeight, videoPixelRatio)
            }
            override fun onPlayerError(error: PlaybackException) {
                prepared = false
                Log.e("KtvPlaybackEngine", "Media3 playback failed code=${error.errorCode}", error)
                errorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
            }
        })
    }

    val isPlaying: Boolean get() = !released && player.isPlaying
    val currentPosition: Int get() = if (released) 0 else
        player.currentPosition.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val duration: Int get() = if (released || player.duration == C.TIME_UNSET) 0 else
        player.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { preparedListener = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completionListener = listener }
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { errorListener = listener }

    fun attach(view: KtvVideoView) {
        targetView = view
        view.bind(this)
        view.keepScreenOn = true
        view.updateVideoSize(videoWidth, videoHeight, videoPixelRatio)
        view.installSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (targetView === view && !released) player.setVideoSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (targetView === view && !released) player.clearVideoSurface(holder.surface)
            }
        })
        if (view.currentHolder().surface.isValid && !released) {
            player.setVideoSurface(view.currentHolder().surface)
        }
    }

    fun setVideoUri(uri: Uri) {
        check(!released) { "播放器已释放" }
        prepared = false
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
        player.stop()
        player.clearMediaItems()
        prepared = false
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
        val (group, index) = tracks[if (original) 0 else 1]
        if (!group.isTrackSupported(index)) return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        return true
    }
    fun selectAudioChannel(channel: Int, volume: Float) {
        audio.channel = channel
        setVolume(volume, volume)
    }
    fun release() {
        if (released) return
        released = true
        player.release()
        targetView = null
        preparedListener = null
        completionListener = null
        errorListener = null
    }
}
