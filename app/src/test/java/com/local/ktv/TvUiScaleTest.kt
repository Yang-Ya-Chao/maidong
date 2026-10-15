package com.local.ktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUiScaleTest {
    @Test fun standardTvResolutionsKeepTheSameLayoutAndTextProportions() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160, 7680 to 4320)) {
            val scale = TvUiScale.densityDpi(width, height) / 160f
            assertEquals(1280f, width / scale, 0.01f)
            assertEquals(720f, height / scale, 0.01f)
            assertEquals(64f / 720f, 64f * scale / height, 0.0001f)
            assertEquals(22f / 720f, 22f * scale / height, 0.0001f)
        }
    }

    @Test fun unusualAspectRatiosFitTheWholeDesignWithoutStretching() {
        for ((width, height) in listOf(1024 to 768, 2560 to 1080, 1366 to 768)) {
            val scale = TvUiScale.densityDpi(width, height) / 160f
            // densityDpi 为整数，允许不足一个密度单位的取整误差。
            assertTrue(1280f * scale <= width + 4f)
            assertTrue(720f * scale <= height + 4f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDisplaySizeIsRejected() {
        TvUiScale.densityDpi(0, 1080)
    }
}
