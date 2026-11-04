package com.local.ktv.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

internal interface PlaybackBackend {
    val hasRenderedFirstFrame: Boolean
    val currentPosition: Int
    val duration: Int
    val isPlaying: Boolean
    val decoderSummary: String
    val renderedVideoFrames: Long
    val renderedVideoPosition: Long
    val decodedNonSilentSamples: Long
    fun attach(view: KtvVideoView)
    fun setVideoUri(uri: Uri)
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?)
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?)
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?)
    fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?)
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    fun stop()
    fun setVolume(left: Float, right: Float)
    fun setTone(step: Int)
    fun audioTrackCount(): Int
    fun selectAudioTrack(original: Boolean): Boolean
    fun selectAudioChannel(channel: Int, volume: Float)
    fun release()

}

/** Selects the playback engine per song; automatic recovery is one-way and bounded. */
class KtvPlaybackEngine(context: Context, private val audioOnly: Boolean = false) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var backend: PlaybackBackend? = null
    private var compatibilityProbe: NativePlaybackEngine? = null
    private var target: KtvVideoView? = null
    private var uri: Uri? = null
    private var generation = 0L
    private var released = false
    private var native = false
    private var readyNotified = false
    private var recovering = false
    private var pendingPosition = 0
    private var playIntent = false
    private var left = 1f
    private var right = 1f
    private var channel = AudioChannels.AUDIO_CHANNEL_STEREO
    private var tone = 0
    private var original: Boolean? = null
    private var prepared: MediaPlayer.OnPreparedListener? = null
    private var completed: MediaPlayer.OnCompletionListener? = null
    private var error: MediaPlayer.OnErrorListener? = null
    private var recovery: ((Boolean) -> Unit)? = null
    var decoderMode = PlaybackDecoderSettings.load(appContext)
        private set
    val hasRenderedFirstFrame: Boolean get() = backend?.hasRenderedFirstFrame == true
    val isPlaying: Boolean get() = backend?.isPlaying == true
    val currentPosition: Int get() = backend?.currentPosition ?: pendingPosition
    val duration: Int get() = backend?.duration ?: 0
    val decoderSummary: String get() = "播放内核：" + (if (native) "FFmpeg" else "ExoPlayer") +
        "\n" + (backend?.decoderSummary ?: "等待播放")
    internal val renderedVideoFrames: Long get() = backend?.renderedVideoFrames ?: 0
    internal val renderedVideoPosition: Long get() = backend?.renderedVideoPosition ?: -1
    internal val decodedNonSilentSamples: Long get() = backend?.decodedNonSilentSamples ?: 0
    fun isSoftwareDecoderAvailable(): Boolean = true
    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) { prepared = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) { completed = listener }
    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) { error = listener }
    fun setOnDecoderRecoveryListener(listener: ((Boolean) -> Unit)?) { recovery = listener }
    fun attach(view: KtvVideoView) {
        target = view
        view.bind(this)
        backend?.attach(view)
    }
    fun setVideoUri(value: Uri) {
        check(!released) { "播放器已释放" }
        generation++
        uri = value
        readyNotified = false
        recovering = false
        pendingPosition = 0
        playIntent = false
        original = null
        decoderMode = PlaybackDecoderSettings.load(appContext)
        open(decoderMode == PlaybackDecoderMode.SOFTWARE)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun open(useNative: Boolean) {
        val media = uri ?: return
        compatibilityProbe?.release()
        compatibilityProbe = null
        val changedEngine = backend != null && native != useNative
        backend?.release()
        backend = null
        if (changedEngine && !audioOnly) target?.recreateDecoderSurface()
        native = useNative
        val token = generation
        val created = try {
            if (native) NativePlaybackEngine(appContext, audioOnly) else ExoPlaybackEngine(appContext, audioOnly)
        } catch (failure: Exception) {
            if (!native && decoderMode == PlaybackDecoderMode.AUTO) { open(true); return }
            recovering = false
            recovery?.invoke(false)
            error?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 4001)
            return
        } catch (failure: LinkageError) {
            if (!native && decoderMode == PlaybackDecoderMode.AUTO) { open(true); return }
            recovering = false
            recovery?.invoke(false)
            error?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 4001)
            return
        }
        backend = created
        fun current() = !released && token == generation && backend === created
        fun completePrepared() {
            if (!current()) return
            if (pendingPosition > 0) created.seekTo(pendingPosition)
            original?.let(created::selectAudioTrack)
            if (playIntent) created.start() else created.pause()
            if (!readyNotified) {
                readyNotified = true
                prepared?.onPrepared(null)
            }
            if (recovering) {
                recovering = false
                recovery?.invoke(false)
            }
        }
        fun failed(what: Int, extra: Int) {
            if (!current()) return
            if (!native && decoderMode == PlaybackDecoderMode.AUTO) {
                if (recovering) return
                pendingPosition = created.currentPosition.coerceAtLeast(0)
                recovering = true
                recovery?.invoke(true)
                handler.post { if (current()) open(true) }
            } else {
                playIntent = false
                created.pause()
                recovering = false
                recovery?.invoke(false)
                error?.onError(null, what, extra)
            }
        }
        created.setOnPreparedListener {
            if (current()) {
                if (!native && created.audioTrackCount() == 0) {
                    // Some extractors silently omit unsupported audio (for example MP1 in MKV).
                    // Probe only this ambiguous case, without playing PCM or opening a video surface.
                    val probe = NativePlaybackEngine(appContext, audioOnly = true)
                    compatibilityProbe = probe
                    probe.setOnPreparedListener {
                        val hiddenAudio = probe.audioTrackCount() > 0
                        if (compatibilityProbe === probe) compatibilityProbe = null
                        probe.release()
                        if (current()) {
                            if (hiddenAudio) failed(MediaPlayer.MEDIA_ERROR_UNKNOWN, 4005)
                            else completePrepared()
                        }
                    }
                    probe.setOnErrorListener { _, _, _ ->
                        if (compatibilityProbe === probe) compatibilityProbe = null
                        probe.release()
                        // Failed probing is not evidence that an audio track exists.
                        if (current()) completePrepared()
                        true
                    }
                    probe.setVideoUri(media)
                } else completePrepared()
            }
        }
        created.setOnCompletionListener { if (current()) { playIntent = false; completed?.onCompletion(null) } }
        created.setOnDecoderRecoveryListener { if (current()) recovery?.invoke(it) }
        created.setOnErrorListener { _, what, extra -> failed(what, extra); true }
        created.setVolume(left, right)
        created.selectAudioChannel(channel, 1f)
        created.setVolume(left, right)
        created.setTone(tone)
        target?.let(created::attach)
        created.setVideoUri(media)
    }
    fun setDecoderMode(mode: PlaybackDecoderMode) {
        if (released) return
        PlaybackDecoderSettings.save(appContext, mode)
        if (decoderMode == mode) return
        decoderMode = mode
        if (uri != null) {
            pendingPosition = currentPosition
            generation++
            recovering = true
            recovery?.invoke(true)
            backend?.release()
            backend = null
            target?.recreateDecoderSurface()
            open(mode == PlaybackDecoderMode.SOFTWARE)
        }
    }
    fun start() { if (!released) { playIntent = true; backend?.start() } }
    fun pause() { if (!released) { playIntent = false; backend?.pause() } }
    fun seekTo(positionMs: Int) {
        if (!released) { pendingPosition = positionMs.coerceAtLeast(0); backend?.seekTo(pendingPosition) }
    }
    fun stop() {
        generation++
        compatibilityProbe?.release()
        compatibilityProbe = null
        backend?.release()
        backend = null
        uri = null
        pendingPosition = 0
        playIntent = false
        recovering = false
    }
    fun setVolume(left: Float, right: Float) {
        this.left = left.coerceIn(0f, 1f); this.right = right.coerceIn(0f, 1f)
        backend?.setVolume(this.left, this.right)
    }
    fun setTone(step: Int) { tone = step.coerceIn(-5, 5); backend?.setTone(tone) }
    fun audioTrackCount(): Int = backend?.audioTrackCount() ?: 0
    fun selectAudioTrack(original: Boolean): Boolean {
        this.original = original
        return backend?.selectAudioTrack(original) == true
    }
    fun selectAudioChannel(channel: Int, volume: Float) {
        this.channel = channel
        left = volume.coerceIn(0f, 1f); right = left
        backend?.selectAudioChannel(channel, volume)
    }
    fun release() {
        if (released) return
        stop()
        released = true
        handler.removeCallbacksAndMessages(null)
        target = null
        prepared = null; completed = null; error = null; recovery = null
    }
}