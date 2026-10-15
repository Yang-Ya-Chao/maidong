package com.local.ktv.player

import org.junit.Assert.*
import org.junit.Test

class KtvTrackSelectorTest {
    @Test fun rejectsTheNativeCrashProfileInBothCodecForms() {
        assertTrue(isUnsafeFfmpegAvcProfile("avc1.F40028"))
        assertTrue(isUnsafeFfmpegAvcProfile("avc3.f40028"))
        assertTrue(isUnsafeFfmpegAvcProfile("avc1.244.40"))
    }
    @Test fun keepsOrdinaryAvcAndOtherCodecsAvailable() {
        assertFalse(isUnsafeFfmpegAvcProfile("avc1.640028"))
        assertFalse(isUnsafeFfmpegAvcProfile("avc1.4D401F"))
        assertFalse(isUnsafeFfmpegAvcProfile("hvc1.1.6.L120.B0"))
        assertFalse(isUnsafeFfmpegAvcProfile(null))
    }
}
