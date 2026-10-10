package com.local.ktv.player

import kotlin.math.roundToInt

/** Display geometry without decoder or Android dependencies. */
object VideoGeometry {
    data class Size(val width: Int, val height: Int)

    fun measure(maxWidth: Int, maxHeight: Int, videoWidth: Int, videoHeight: Int,
                pixelRatio: Float, mode: String): Size {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        if (mode == "全屏") return Size(width, height)
        val safePixelRatio = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ratio = when (mode) {
            "16 : 9" -> 16.0 / 9.0
            "4 : 3" -> 4.0 / 3.0
            else -> if (videoWidth > 0 && videoHeight > 0)
                videoWidth.toDouble() * safePixelRatio / videoHeight else 16.0 / 9.0
        }
        return if (width.toDouble() / height > ratio) {
            Size((height * ratio).roundToInt().coerceIn(1, width), height)
        } else {
            Size(width, (width / ratio).roundToInt().coerceIn(1, height))
        }
    }
}
