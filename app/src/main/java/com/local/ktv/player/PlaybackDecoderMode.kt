package com.local.ktv.player

import androidx.media3.common.PlaybackException

/** The automatic path uses platform decoders first and FFmpeg where needed. */
enum class PlaybackDecoderMode(val title: String) {
    AUTO("自动（系统优先，兼容解码兜底）"),
    SYSTEM("系统解码"),
    SOFTWARE("兼容解码（FFmpeg 优先）");

    companion object {
        fun fromStored(value: String?): PlaybackDecoderMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** Immutable per-media recovery state; a failed decoder is retried only once. */
data class DecoderRecoveryState(
    val mode: PlaybackDecoderMode,
    val softwareAudio: Boolean = mode == PlaybackDecoderMode.SOFTWARE,
    val softwareVideo: Boolean = mode == PlaybackDecoderMode.SOFTWARE,
    val ignoreMatroskaCues: Boolean = false,
    val systemAudioOnly: Boolean = false,
    val systemVideoOnly: Boolean = false,
) {
    /** Only recover an invalid initial cue seek; never mask mid-play I/O failures. */
    fun recoverContainer(errorCode: Int, isMatroska: Boolean, positionMs: Long,
        hasFirstFrame: Boolean): DecoderRecoveryState? =
        if (!ignoreMatroskaCues && isMatroska && positionMs == 0L && !hasFirstFrame &&
            errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            copy(ignoreMatroskaCues = true)
        } else null
    fun recover(errorCode: Int, track: DecoderFailureTrack,
        failedSoftware: Boolean = false): DecoderRecoveryState? {
        if (mode != PlaybackDecoderMode.AUTO || errorCode !in DECODER_ERRORS) return null
        val audioTried = softwareAudio || systemAudioOnly
        val videoTried = softwareVideo || systemVideoOnly
        return when (track) {
            DecoderFailureTrack.AUDIO -> if (audioTried) null else
                copy(softwareAudio = !failedSoftware, systemAudioOnly = failedSoftware)
            DecoderFailureTrack.VIDEO -> if (videoTried) null else
                copy(softwareVideo = !failedSoftware, systemVideoOnly = failedSoftware)
            DecoderFailureTrack.UNKNOWN -> if (audioTried || videoTried) null else
                copy(softwareAudio = !failedSoftware, softwareVideo = !failedSoftware,
                    systemAudioOnly = failedSoftware, systemVideoOnly = failedSoftware)
        }
    }
    companion object {
        fun isDecoderFailure(errorCode: Int): Boolean = errorCode in DECODER_ERRORS

        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}

enum class DecoderFailureTrack { AUDIO, VIDEO, UNKNOWN }
