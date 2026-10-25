package com.local.ktv

import java.util.Locale

/** 本地导入排在目录结果前，跨边界分页时保留正确的目录偏移量。 */
internal object SongSearch {
    data class Page(val songs: List<Song>, val total: Int)

    fun matches(song: Song, query: String, language: String? = null, singerOnly: Boolean = false): Boolean {
        if (!language.isNullOrBlank() && language != "全部" && song.language != language) return false
        val fields = if (singerOnly) listOf(song.singer, song.singerNames)
            else listOf(song.title, song.singer, song.singerNames, song.pinyin, song.pinyinFull, song.filename)
        val key = query.trim().uppercase(Locale.ROOT)
        if (fields.any { it.orEmpty().uppercase(Locale.ROOT).contains(key) }) return true
        // 目录歌曲的拼音字段通常只有歌名，歌手拼音需独立匹配。
        if (key.isNotEmpty() && key.all { it in 'A'..'Z' || it.isWhitespace() }) {
            val singer = listOfNotNull(song.singer, song.singerNames).joinToString(" ")
            return runCatching {
                IgebaCatalogBootstrapper.initials(singer).contains(key) ||
                    IgebaCatalogBootstrapper.fullPinyin(singer).contains(key)
            }.getOrDefault(false)
        }
        return false
    }

    fun localFirstPage(imported: List<Song>, localCatalog: List<Song>, onlineCatalogCount: Int,
                       offset: Int, limit: Int, isLocal: (Song) -> Boolean,
                       catalogPage: (Int, Int) -> List<Song>): Page {
        val (localImports, onlineImports) = imported.partition(isLocal)
        return page(localImports + localCatalog + onlineImports, onlineCatalogCount, offset, limit, catalogPage)
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
