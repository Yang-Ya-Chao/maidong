package com.local.ktv.player

/** All media probing and decoding runs in the native FFmpeg engine. */
enum class PlaybackDecoderMode(val title: String) {
    SOFTWARE("FFmpeg 完整解码")
}

/** Stable app error codes, independent of any third-party player. */
object DecoderRecoveryState {
    fun isDecoderFailure(errorCode: Int): Boolean = errorCode in 4001..4005
}
