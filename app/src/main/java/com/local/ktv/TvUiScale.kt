package com.local.ktv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlin.math.min
import kotlin.math.roundToInt

/** 电视统一使用现有 1280 × 720 设计坐标，避免系统密度改变控件在画面中的占比。 */
internal object TvUiScale {
    fun isTelevision(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    fun densityDpi(widthPixels: Int, heightPixels: Int): Int {
        require(widthPixels > 0 && heightPixels > 0)
        return (min(widthPixels / 1280f, heightPixels / 720f) * 160f)
            .roundToInt().coerceAtLeast(1)
    }

    fun wrap(context: Context): Context {
        if (!isTelevision(context)) return context
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return context
        val dpi = densityDpi(metrics.widthPixels, metrics.heightPixels)
        val density = dpi / 160f
        val configuration = Configuration(context.resources.configuration).apply {
            densityDpi = dpi
            // 电视文字与控件一起缩放，避免系统字体倍率挤压固定比例的布局。
            fontScale = 1f
            screenWidthDp = (metrics.widthPixels / density).roundToInt()
            screenHeightDp = (metrics.heightPixels / density).roundToInt()
            smallestScreenWidthDp = min(screenWidthDp, screenHeightDp)
        }
        return context.createConfigurationContext(configuration)
    }
}
