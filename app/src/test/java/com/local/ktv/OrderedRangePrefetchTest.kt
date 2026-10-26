package com.local.ktv

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OrderedRangePrefetchTest {
    @Test fun outOfOrderRepliesStillCommitContiguouslyAndKeepTwoReaders() {
        val workers = Executors.newFixedThreadPool(4)
        val secondFinished = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val committed = mutableListOf<Long>()
        try {
            OrderedRangePrefetch.transfer(0, 25, 10, workers, { false }, { start, stop ->
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                try {
                    if (start == 0L) { firstStarted.countDown(); assertTrue(secondFinished.await(2, TimeUnit.SECONDS)) }
                    if (start == 10L) { assertTrue(firstStarted.await(2, TimeUnit.SECONDS)); secondFinished.countDown() }
                    ByteArray((stop - start).toInt()) { start.toByte() }
                } finally { active.decrementAndGet() }
            }, { start, stop, data ->
                committed += start
                assertEquals(stop - start, data.size.toLong())
                assertTrue(data.all { it == start.toByte() })
            })
            assertEquals(listOf(0L, 10L, 20L), committed)
            assertEquals(2, maximum.get())
        } finally { workers.shutdownNow() }
    }

    @Test fun failedRangeNeverCommitsLaterData() {
        val workers = Executors.newFixedThreadPool(2)
        val committed = mutableListOf<Long>()
        try {
            val failure = assertThrows(IOException::class.java) {
                OrderedRangePrefetch.transfer(0, 40, 10, workers, { false },
                    { start, _ -> if (start == 10L) throw IOException("network"); start },
                    { start, _, _ -> committed += start })
            }
            assertEquals("network", failure.message)
            assertEquals(listOf(0L), committed)
        } finally { workers.shutdownNow() }
    }

    @Test fun cancelAfterFirstCommitStopsAllFurtherCommits() {
        val workers = Executors.newFixedThreadPool(2)
        val cancelled = AtomicBoolean()
        val committed = mutableListOf<Long>()
        try {
            assertThrows(IOException::class.java) {
                OrderedRangePrefetch.transfer(0, 100, 10, workers, cancelled::get,
                    { start, _ -> start }, { start, _, _ -> committed += start; cancelled.set(true) })
            }
            assertEquals(listOf(0L), committed)
        } finally { workers.shutdownNow() }
    }

    @Test fun resumesFromExistingPrefixAndIncludesOneByteTail() {
        val workers = Executors.newFixedThreadPool(2)
        val spans = mutableListOf<Pair<Long, Long>>()
        try {
            OrderedRangePrefetch.transfer(63, 84, 10, workers, { false },
                { start, stop -> start to stop }, { start, stop, data ->
                    assertEquals(start to stop, data)
                    spans += data
                })
            assertEquals(listOf(63L to 73L, 73L to 83L, 83L to 84L), spans)
        } finally { workers.shutdownNow() }
    }

    @Test fun failedDiskWriteDoesNotCommitFollowingPrefetch() {
        val workers = Executors.newFixedThreadPool(2)
        val writes = AtomicInteger()
        try {
            assertThrows(IOException::class.java) {
                OrderedRangePrefetch.transfer(0, 30, 10, workers, { false },
                    { start, _ -> start }, { _, _, _ -> writes.incrementAndGet(); throw IOException("disk") })
            }
            assertEquals(1, writes.get())
        } finally { workers.shutdownNow() }
    }
}
