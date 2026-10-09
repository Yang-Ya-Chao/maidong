package com.local.ktv

import org.junit.Assert.*
import org.junit.Test

class DownloadSpaceBudgetTest {
    @Test fun reservesCompleteRemainingSongAndUserReserve() {
        val budget = DownloadSpaceBudget()
        budget.reserve("one", "local", 40, 100, 20)
        assertTrue(runCatching { budget.reserve("two", "local", 41, 100, 20) }.isFailure)
        budget.reserve("two", "local", 40, 100, 20)
    }

    @Test fun partialWriteUpdatesClaimInsteadOfCountingAlreadyWrittenBytesTwice() {
        val budget = DownloadSpaceBudget()
        budget.reserve("one", "usb", 60, 100, 10)
        budget.reserve("one", "usb", 20, 60, 10)
        budget.reserve("two", "usb", 30, 60, 10)
        assertTrue(runCatching { budget.reserve("three", "usb", 1, 60, 10) }.isFailure)
    }

    @Test fun differentVolumesDoNotConsumeEachOthersSpace() {
        val budget = DownloadSpaceBudget()
        budget.reserve("one", "local", 80, 100, 20)
        budget.reserve("two", "usb", 80, 100, 20)
    }

    @Test fun completedOrCancelledJobReleasesClaim() {
        val budget = DownloadSpaceBudget()
        budget.reserve("one", "usb", 80, 100, 20)
        budget.release("one")
        budget.reserve("two", "usb", 80, 100, 20)
    }

    @Test fun failedReservationDoesNotReduceExistingCapacity() {
        val budget = DownloadSpaceBudget()
        assertTrue(runCatching { budget.reserve("one", "usb", 81, 100, 20) }.isFailure)
        budget.reserve("two", "usb", 80, 100, 20)
    }

    @Test fun largeValuesDoNotOverflowWhenMultipleSongsAreReserved() {
        val budget = DownloadSpaceBudget()
        budget.reserve("one", "usb", Long.MAX_VALUE - 1, Long.MAX_VALUE, 0)
        assertTrue(runCatching { budget.reserve("two", "usb", 2, Long.MAX_VALUE, 0) }.isFailure)
        budget.reserve("two", "usb", 1, Long.MAX_VALUE, 0)
    }
    @Test fun shortageReportsFullTargetIncludingOtherVolumeClaims() {
        val budget = DownloadSpaceBudget()
        budget.reserve("existing", "local", 35, 100, 20)
        budget.reserve("usb", "usb", 80, 100, 20)
        val shortage = runCatching { budget.reserve("new", "local", 50, 100, 20) }.exceptionOrNull()
        assertTrue(shortage is DownloadSpaceBudget.InsufficientSpace)
        assertEquals(105L, (shortage as DownloadSpaceBudget.InsufficientSpace).requiredAvailable)
        budget.reserve("new", "local", 50, 105, 20)
    }

    @Test fun shortageTargetSaturatesRatherThanOverflowing() {
        val budget = DownloadSpaceBudget()
        budget.reserve("existing", "local", Long.MAX_VALUE - 20, Long.MAX_VALUE, 0)
        val shortage = runCatching { budget.reserve("new", "local", 40, Long.MAX_VALUE, 10) }.exceptionOrNull()
        assertEquals(Long.MAX_VALUE, (shortage as DownloadSpaceBudget.InsufficientSpace).requiredAvailable)
    }

}
