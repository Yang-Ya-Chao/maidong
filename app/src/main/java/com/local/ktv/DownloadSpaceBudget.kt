package com.local.ktv

import java.io.IOException

/** Accounts for bytes not yet written by all concurrent jobs on the same volume. */
internal class DownloadSpaceBudget {
    private data class Claim(val volume: String, val remaining: Long)
    private val claims = linkedMapOf<String, Claim>()

    class InsufficientSpace(val requiredAvailable: Long) :
        IOException("保存位置空间不足，请清理缓存或在设置中选择保存到U盘")

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    @Synchronized
    fun reserve(id: String, volume: String, remaining: Long, available: Long, reserve: Long) {
        require(remaining >= 0 && available >= 0 && reserve >= 0)
        var room = (available - reserve).coerceAtLeast(0)
        claims.filterKeys { it != id }.values.filter { it.volume == volume }.forEach {
            room = (room - it.remaining).coerceAtLeast(0)
        }
        if (remaining > room) {
            val other = claims.filterKeys { it != id }.values.filter { it.volume == volume }
                .fold(0L) { total, claim -> saturatedAdd(total, claim.remaining) }
            throw InsufficientSpace(saturatedAdd(saturatedAdd(reserve, other), remaining))
        }
        claims[id] = Claim(volume, remaining)
    }

    @Synchronized
    fun release(id: String) { claims.remove(id) }
}
