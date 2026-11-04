package com.local.ktv.player

import android.content.Context

object PlaybackDecoderSettings {
    private const val PREFERENCES = "playback_decoder"
    private const val KEY = "engine"
    fun load(context: Context): PlaybackDecoderMode =
        PlaybackDecoderMode.fromStored(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))
    fun save(context: Context, mode: PlaybackDecoderMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
}
