package com.local.ktv.player

import org.junit.Assert.*
import org.junit.Test

class DecoderRecoveryStateTest {
    @Test fun decoderErrorsAreNotRetriedAsNetworkFailures() {
        for (code in 4001..4005) assertTrue(DecoderRecoveryState.isDecoderFailure(code))
        for (code in listOf(2000, 2001, 2008, 5001)) assertFalse(DecoderRecoveryState.isDecoderFailure(code))
    }
}
