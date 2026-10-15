package com.local.ktv.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/** Both decoder paths feed the existing karaoke PCM processor and SurfaceView. */
@UnstableApi
internal class KtvRenderersFactory(
    context: Context,
    private val audioProcessor: AudioProcessor,
    private val recovery: DecoderRecoveryState,
    ffmpegAvailable: Boolean,
) : NextRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(
            if (recovery.mode == PlaybackDecoderMode.SYSTEM || !ffmpegAvailable) {
                EXTENSION_RENDERER_MODE_OFF
            } else if (recovery.mode == PlaybackDecoderMode.SOFTWARE) {
                EXTENSION_RENDERER_MODE_PREFER
            } else {
                EXTENSION_RENDERER_MODE_ON
            },
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .setAudioProcessors(arrayOf(audioProcessor))
            .build()
        // Reject encoded output even when an HDMI receiver advertises passthrough support.
        // Both decoders must produce PCM before karaoke channel/volume/pitch processing.
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean =
                getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
                AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        if (recovery.mode == PlaybackDecoderMode.AUTO && recovery.softwareAudio) {
            out.removeAll { it.trackType == C.TRACK_TYPE_AUDIO && it !is FfmpegAudioRenderer }
        } else if (recovery.systemAudioOnly) {
            out.removeAll { it is FfmpegAudioRenderer }
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        if (recovery.mode == PlaybackDecoderMode.AUTO && recovery.softwareVideo) {
            out.removeAll { it.trackType == C.TRACK_TYPE_VIDEO && it !is FfmpegVideoRenderer }
        } else if (recovery.systemVideoOnly) {
            out.removeAll { it is FfmpegVideoRenderer }
        }
    }
}
