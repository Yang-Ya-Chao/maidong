package com.local.ktv

import org.junit.Assert.*
import org.junit.Test

class SongSearchTest {
    @Test fun singerFirstFilenameKeepsLongSingerAndShortTitle() {
        val song = Song.local("/usb/maidongktv/让歌手名字很长的组合-爱.MKV", "让歌手名字很长的组合-爱.MKV")
        assertEquals("让歌手名字很长的组合", song.singer)
        assertEquals("爱", song.title)
        assertEquals("让歌手名字很长的组合-爱.MKV", song.filename)
    }

    @Test fun titleKeepsLaterHyphensAndFilenamePrefixIsRemoved() {
        val song = Song.local("/usb/song.MKV", "001. 周杰伦 - 稻香-现场版.MKV")
        assertEquals("周杰伦", song.singer)
        assertEquals("稻香-现场版", song.title)
    }

    @Test fun importedSongMatchesNameSingerInitialsFullPinyinAndUppercaseExtension() {
        val song = Song.local("/usb/周杰伦-稻香.MKV", "周杰伦-稻香.MKV").apply {
            pinyin = "DX ZJL"
            pinyinFull = "DAOXIANG ZHOUJIELUN"
            language = "国语"
        }
        for (query in listOf("稻香", "周杰伦", "dx", "zjl", "daoxiang", "周杰伦-稻香.MKV")) {
            assertTrue(query, SongSearch.matches(song, query))
        }
        assertFalse(SongSearch.matches(song, "稻香", "粤语"))
        assertTrue(SongSearch.matches(song, "周杰伦", singerOnly = true))
        assertFalse(SongSearch.matches(song, "稻香", singerOnly = true))
    }

    @Test fun localAndCatalogPagesHaveNoMissingOrRepeatedRowsAcrossBoundary() {
        for (localCount in listOf(0, 2, 6, 8, 12)) {
            for (catalogCount in listOf(0, 3, 11)) {
                val local = (0 until localCount).map { Song().apply { id = "local:$it" } }
                val catalog = (0 until catalogCount).map { Song().apply { id = "catalog:$it" } }
                val combined = local + catalog
                for (offset in 0..combined.size + 6 step 6) {
                    val page = SongSearch.page(local, catalog.size, offset, 6) { from, size ->
                        catalog.drop(from).take(size)
                    }
                    assertEquals(combined.size, page.total)
                    assertEquals(combined.drop(offset).take(6).map { it.id }, page.songs.map { it.id })
                }
            }
        }
    }

    @Test fun pageWithinImportedSongsDoesNotLoadCatalog() {
        val local = (0 until 8).map { Song() }
        val page = SongSearch.page(local, 100, 0, 6) { _, _ -> error("不应查询目录") }
        assertEquals(6, page.songs.size)
        assertEquals(108, page.total)
    }
}
