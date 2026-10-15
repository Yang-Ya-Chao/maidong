package com.local.ktv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class DecoderRecoveryStateTest {
    private val failure = PlaybackException.ERROR_CODE_DECODING_FAILED

    @Test fun audioFailurePreservesVideoPathAndCannotLoop() {
        val state = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
        val next = state.recover(failure, DecoderFailureTrack.AUDIO)!!
        assertTrue(next.softwareAudio)
        assertFalse(next.softwareVideo)
        assertNull(next.recover(failure, DecoderFailureTrack.AUDIO))
    }

    @Test fun videoFailurePreservesAudioPathAndCannotLoop() {
        val next = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
            .recover(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, DecoderFailureTrack.VIDEO)!!
        assertFalse(next.softwareAudio)
        assertTrue(next.softwareVideo)
        assertNull(next.recover(failure, DecoderFailureTrack.VIDEO))
    }

    @Test fun independentFailuresCanRecoverBothTracksOnce() {
        val audio = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
            .recover(failure, DecoderFailureTrack.AUDIO)!!
        val both = audio.recover(failure, DecoderFailureTrack.VIDEO)!!
        assertTrue(both.softwareAudio && both.softwareVideo)
        assertNull(both.recover(failure, DecoderFailureTrack.UNKNOWN))
    }

    @Test fun unknownDecoderFailureHasOneBoundedFallback() {
        val next = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
            .recover(failure, DecoderFailureTrack.UNKNOWN)!!
        assertTrue(next.softwareAudio && next.softwareVideo)
        assertNull(next.recover(failure, DecoderFailureTrack.UNKNOWN))
    }

    @Test fun networkStorageParserAndAudioDeviceErrorsDoNotChangeDecoders() {
        val state = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )) assertNull("error=$code", state.recover(code, DecoderFailureTrack.UNKNOWN))
    }

    @Test fun explicitModesNeverSilentlySwitch() {
        for (mode in listOf(PlaybackDecoderMode.SYSTEM, PlaybackDecoderMode.SOFTWARE)) {
            assertNull(DecoderRecoveryState(mode).recover(failure, DecoderFailureTrack.UNKNOWN))
        }
    }

    @Test fun nextMediaStartsWithFreshAutomaticState() {
        val recovered = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
            .recover(failure, DecoderFailureTrack.UNKNOWN)!!
        val fresh = DecoderRecoveryState(recovered.mode)
        assertFalse(fresh.softwareAudio || fresh.softwareVideo)
    }

    @Test fun invalidOrMissingStoredModesUseAutomatic() {
        assertEquals(PlaybackDecoderMode.AUTO, PlaybackDecoderMode.fromStored(null))
        assertEquals(PlaybackDecoderMode.AUTO, PlaybackDecoderMode.fromStored("unknown"))
        assertEquals(PlaybackDecoderMode.SYSTEM, PlaybackDecoderMode.fromStored("SYSTEM"))
        assertEquals(PlaybackDecoderMode.SOFTWARE, PlaybackDecoderMode.fromStored("SOFTWARE"))
    }
    @Test fun invalidInitialMatroskaCueCanRecoverOnceInEveryMode() {
        for (mode in PlaybackDecoderMode.entries) {
            val initial = DecoderRecoveryState(mode)
            val next = initial.recoverContainer(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, true, 0L, false)!!
            assertEquals(mode, next.mode)
            assertTrue(next.ignoreMatroskaCues)
            assertNull(next.recoverContainer(
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, true, 0L, false))
            assertFalse(DecoderRecoveryState(mode).ignoreMatroskaCues)
        }
    }

    @Test fun cueRecoveryDoesNotHideMidPlaybackOrOtherSourceErrors() {
        val state = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
        val range = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        assertNull(state.recoverContainer(range, false, 0L, false))
        assertNull(state.recoverContainer(range, true, 1L, false))
        assertNull(state.recoverContainer(range, true, 0L, true))
        assertNull(state.recoverContainer(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED, true, 0L, false))
        assertNull(state.recoverContainer(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, true, 0L, false))
    }
    @Test fun ffmpegFailureTriesSystemOnceWithoutLoopingBack() {
        for (track in listOf(DecoderFailureTrack.AUDIO, DecoderFailureTrack.VIDEO)) {
            val next = DecoderRecoveryState(PlaybackDecoderMode.AUTO)
                .recover(failure, track, failedSoftware = true)!!
            if (track == DecoderFailureTrack.AUDIO) {
                assertTrue(next.systemAudioOnly)
                assertFalse(next.systemVideoOnly || next.softwareAudio)
            } else {
                assertTrue(next.systemVideoOnly)
                assertFalse(next.systemAudioOnly || next.softwareVideo)
            }
            assertNull(next.recover(failure, track))
            assertNull(next.recover(failure, track, failedSoftware = true))
        }
    }

    @Test fun systemThenFfmpegFailureStopsAndKeepsContainerRecovery() {
        val state = DecoderRecoveryState(PlaybackDecoderMode.AUTO, ignoreMatroskaCues = true)
        val next = state.recover(failure, DecoderFailureTrack.VIDEO)!!
        assertTrue(next.ignoreMatroskaCues)
        assertNull(next.recover(failure, DecoderFailureTrack.VIDEO, failedSoftware = true))
    }
}
