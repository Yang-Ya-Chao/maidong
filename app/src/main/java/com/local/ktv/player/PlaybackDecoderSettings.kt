package com.local.ktv.player

import android.content.Context

/** Legacy saved system/automatic choices no longer affect the FFmpeg-only engine. */
object PlaybackDecoderSettings {
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context): PlaybackDecoderMode = PlaybackDecoderMode.SOFTWARE
}
