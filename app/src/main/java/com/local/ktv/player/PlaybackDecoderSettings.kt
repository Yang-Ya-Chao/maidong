package com.local.ktv.player

import android.content.Context

/** Decoder selection is automatic; legacy saved choices no longer affect playback. */
object PlaybackDecoderSettings {
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context): PlaybackDecoderMode = PlaybackDecoderMode.AUTO
}
