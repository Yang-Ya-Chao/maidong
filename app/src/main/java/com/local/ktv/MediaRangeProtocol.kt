package com.local.ktv

/** Byte counts come from Content-Range, never from the endpoint's inclusive/exclusive wire convention. */
internal object MediaRangeProtocol {
    const val MAX_CHUNK = 2 * 1024 * 1024
    data class ResponseRange(val start: Long, val end: Long, val total: Long) {
        val byteCount: Int get() = (end - start + 1).toInt()
    }
    fun parse(header: String?, expectedStart: Long): ResponseRange {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
            .matchEntire(header.orEmpty().trim())
            ?: throw IllegalArgumentException("分段响应缺少有效 Content-Range")
        val values = match.groupValues.drop(1).map(String::toLong)
        val (start, end, total) = values
        require(start == expectedStart && end >= start && end < total &&
            end - start + 1 <= MAX_CHUNK) { "媒体分段范围不一致" }
        return ResponseRange(start, end, total)
    }
    fun exclusiveNode(probe: ResponseRange): Boolean {
        require(probe.start == 0L && probe.end in 62L..63L) { "无法识别歌源分段协议" }
        return probe.end == 62L
    }
    fun wireEnd(stopExclusive: Long, exclusiveNode: Boolean): Long =
        if (exclusiveNode) stopExclusive else stopExclusive - 1
}
