package com.local.ktv

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** Fetch at most two ranges ahead; only the consumer commits contiguous verified data. */
internal object OrderedRangePrefetch {
    fun <T> transfer(start: Long, total: Long, chunk: Int, executor: ExecutorService,
                     cancelled: () -> Boolean, fetch: (Long, Long) -> T,
                     commit: (Long, Long, T) -> Unit) {
        require(start in 0..total && chunk > 0)
        data class Pending<T>(val start: Long, val stop: Long, val future: Future<T>)
        val pending = ArrayDeque<Pending<T>>()
        var scheduled = start
        fun fill() {
            while (pending.size < 2 && scheduled < total) {
                if (cancelled()) throw IOException("下载已暂停")
                val from = scheduled
                val until = minOf(total, from + chunk)
                pending.addLast(Pending(from, until, executor.submit<T> { fetch(from, until) }))
                scheduled = until
            }
        }
        try {
            fill()
            while (pending.isNotEmpty()) {
                if (cancelled()) throw IOException("下载已暂停")
                val next = pending.first
                val data = try { next.future.get() }
                catch (error: ExecutionException) {
                    throw (error.cause as? Exception ?: IOException("分段下载失败", error.cause))
                }
                if (cancelled()) throw IOException("下载已暂停")
                commit(next.start, next.stop, data)
                pending.removeFirst()
                fill()
            }
        } finally {
            pending.forEach { it.future.cancel(true) }
        }
    }
}
