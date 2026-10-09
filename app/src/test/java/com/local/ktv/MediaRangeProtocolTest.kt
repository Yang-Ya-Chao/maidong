package com.local.ktv

import org.junit.Assert.*
import org.junit.Test

class MediaRangeProtocolTest {
    @Test fun inclusiveNodeCountsEveryReturnedByte() {
        val probe = MediaRangeProtocol.parse("bytes 0-63/1000", 0)
        assertEquals(64, probe.byteCount)
        assertFalse(MediaRangeProtocol.exclusiveNode(probe))
        assertEquals(999L, MediaRangeProtocol.wireEnd(1000, false))
    }
    @Test fun exclusiveNodeDoesNotLoseOneBytePerBlock() {
        val probe = MediaRangeProtocol.parse("bytes 0-62/1000", 0)
        assertEquals(63, probe.byteCount)
        assertTrue(MediaRangeProtocol.exclusiveNode(probe))
        assertEquals(1000L, MediaRangeProtocol.wireEnd(1000, true))
    }
    @Test fun finalSingleByteBlockIsValid() {
        assertEquals(1, MediaRangeProtocol.parse("bytes 999-999/1000", 999).byteCount)
    }
    @Test fun validFullChunkIsBounded() {
        assertEquals(2 * 1024 * 1024,
            MediaRangeProtocol.parse("bytes 0-2097151/3000000", 0).byteCount)
    }
    @Test fun rejectsIgnoredRangeAndInvalidLengths() {
        for ((header, start) in listOf(
            "bytes 0-63/1000" to 64L,
            "bytes 64-63/1000" to 64L,
            "bytes 0-1000/1000" to 0L,
            "bytes 0-2097152/3000000" to 0L,
            "bytes 0-0/0" to 0L,
            "bytes 0-63/*" to 0L,
            "" to 0L)) {
            assertThrows(IllegalArgumentException::class.java) { MediaRangeProtocol.parse(header, start) }
        }
    }
    @Test fun refusesUnexpectedProbeDialect() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaRangeProtocol.exclusiveNode(MediaRangeProtocol.parse("bytes 0-31/1000", 0))
        }
    }
}
