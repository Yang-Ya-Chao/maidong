package com.local.ktv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/** NextLib 0.9's swscale path crashes on the selected High 4:4:4 stream. */
@UnstableApi
internal class KtvTrackSelector(context: Context) : DefaultTrackSelector(context) {
    override fun selectAllTracks(
        info: MappedTrackInfo,
        supports: Array<Array<IntArray>>,
        mixedMimeSupports: IntArray,
        parameters: Parameters,
    ): Array<ExoTrackSelection.Definition?> {
        val selections = super.selectAllTracks(info, supports, mixedMimeSupports, parameters)
        if (C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes) return selections
        for (renderer in selections.indices) {
            if (parameters.getRendererDisabled(renderer) ||
                !info.getRendererName(renderer).startsWith("FfmpegVideo")) continue
            val selection = selections[renderer] ?: continue
            for (track in selection.tracks) {
                val format = selection.group.getFormat(track)
                if (isUnsafeFfmpegAvcProfile(format.codecs)) {
                    throw ExoPlaybackException.createForRenderer(
                        IllegalStateException("当前兼容解码库暂不支持 H.264 High 4:4:4，请转换为 8 位 4:2:0 后播放"),
                        "FfmpegVideoRenderer", renderer, format,
                        C.FORMAT_UNSUPPORTED_SUBTYPE, false,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                }
            }
        }
        return selections
    }
}

/** AVC profile_idc 244 is High 4:4:4 Predictive; ordinary 8-bit High stays enabled. */
internal fun isUnsafeFfmpegAvcProfile(codecs: String?): Boolean =
    codecs.orEmpty().split(',').any {
        Regex("(?i)avc[13]\\.(f4[0-9a-f]{4}|244\\.[0-9]+)").matches(it.trim())
    }
