package com.local.ktv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test fun fitPreservesFourByThreeSourceOnWideScreen() {
        assertEquals(VideoGeometry.Size(1440, 1080),
            VideoGeometry.measure(1920, 1080, 640, 480, 1f, "适应屏幕"))
    }

    @Test fun fitUsesAnamorphicPixelRatio() {
        assertEquals(VideoGeometry.Size(1920, 1080),
            VideoGeometry.measure(1920, 1080, 720, 576, 64f / 45f, "适应屏幕"))
    }

    @Test fun explicitRatioOverridesSourceAspect() {
        assertEquals(VideoGeometry.Size(1440, 1080),
            VideoGeometry.measure(1920, 1080, 1920, 1080, 1f, "4 : 3"))
        assertEquals(VideoGeometry.Size(1920, 1080),
            VideoGeometry.measure(1920, 1080, 640, 480, 1f, "16 : 9"))
    }

    @Test fun fullScreenUsesEntireNonWideContainer() {
        assertEquals(VideoGeometry.Size(800, 600),
            VideoGeometry.measure(800, 600, 1920, 1080, 1f, "全屏"))
    }

    @Test fun invalidMetadataAndSmallBoundsRemainValid() {
        assertEquals(VideoGeometry.Size(1440, 1080),
            VideoGeometry.measure(1920, 1080, 640, 480, Float.NaN, "适应屏幕"))
        assertEquals(VideoGeometry.Size(1, 1),
            VideoGeometry.measure(0, 0, 0, 0, Float.POSITIVE_INFINITY, "适应屏幕"))
    }
}
