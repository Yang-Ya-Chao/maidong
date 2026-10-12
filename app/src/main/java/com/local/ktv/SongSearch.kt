package com.local.ktv

import java.util.Locale

/** 本地导入排在目录结果前，跨边界分页时保留正确的目录偏移量。 */
internal object SongSearch {
    data class Page(val songs: List<Song>, val total: Int)

    fun matches(song: Song, query: String, language: String? = null, singerOnly: Boolean = false): Boolean {
        if (!language.isNullOrBlank() && language != "全部" && song.language != language) return false
        val fields = if (singerOnly) listOf(song.singer, song.singerNames)
            else listOf(song.title, song.singer, song.pinyin, song.pinyinFull, song.filename)
        return fields.any { it.orEmpty().uppercase(Locale.ROOT).contains(query.trim().uppercase(Locale.ROOT)) }
    }

    fun page(local: List<Song>, catalogCount: Int, offset: Int, limit: Int,
             catalogPage: (Int, Int) -> List<Song>): Page {
        val start = offset.coerceAtLeast(0)
        val size = limit.coerceAtLeast(0)
        val songs = local.drop(start).take(size).toMutableList()
        val remaining = size - songs.size
        if (remaining > 0 && catalogCount > 0) {
            songs += catalogPage((start - local.size).coerceAtLeast(0), remaining)
        }
        return Page(songs, local.size + catalogCount)
    }
}
