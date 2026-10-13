package com.local.ktv

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSongResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun catalogSong(number: String, name: String) = Song().apply {
        sourceSongNumber = number
        id = "igeba:song:$number"
        title = "晴天"
        singer = "周杰伦"
        filename = name
        sourceVoiceChannel = 2
        pinyin = "QT"
    }

    @Test fun appNumberedDownloadIsSearchableWithoutRenaming() {
        val root = temporary.newFolder("usb")
        val folder = UsbMountDiscovery.songDirectory(root).apply { mkdirs() }
        val file = File(folder, "00522920.MKV").apply { writeText("media") }
        val catalog = catalogSong("00522920", "供应商文件名.mkv")
        val songs = LocalSongResolver.resolve(UsbMountDiscovery.songs(root), { emptyList() }, { numbers ->
            assertEquals(listOf("00522920"), numbers)
            listOf(catalog)
        })
        val song = songs.single()
        assertEquals("晴天", song.title)
        assertEquals("周杰伦", song.singer)
        assertEquals(2, song.sourceVoiceChannel)
        assertEquals("igeba:song:00522920", song.id)
        assertEquals(file.canonicalPath, song.path)
        assertTrue(SongSearch.matches(song, "晴天", null, false))
    }

    @Test fun completionRecordRestoresMetadataOfflineAndRebasesMovedPaths() {
        val file = temporary.newFile("vendor_hash.mkv").apply { writeText("media") }
        val saved = catalogSong("00522920", file.name).apply {
            path = "/storage/OLD/vendor_hash.mkv"
            originalPath = "/storage/OLD/vendor_hash.original.mp3"
            downloadUrl = "https://example.invalid/expired"
        }
        File(file.parentFile, file.name + ".complete.json").writeText(
            JSONObject().put("provider", "igeba").put("SongNumber", "00522920")
                .put("bytes", file.length()).put("song", saved.toJson()).toString())
        val song = LocalSongResolver.resolve(listOf(file), { emptyList() }, { emptyList() }).single()
        assertEquals("晴天", song.title)
        assertEquals("00522920", song.sourceSongNumber)
        assertEquals(file.absolutePath, song.path)
        assertEquals("", song.originalPath)
        assertNull(song.downloadUrl)
    }

    @Test fun invalidCompletionRecordDoesNotMisidentifyManualSong() {
        val file = temporary.newFile("陈奕迅-十年.mkv").apply { writeText("media") }
        File(file.parentFile, file.name + ".complete.json").writeText(
            JSONObject().put("provider", "igeba").put("SongNumber", "00522920")
                .put("bytes", file.length() + 1).put("song", catalogSong("00522920", file.name).toJson()).toString())
        val song = LocalSongResolver.resolve(listOf(file), { emptyList() }, { emptyList() }).single()
        assertEquals("十年", song.title)
        assertEquals("陈奕迅", song.singer)
        assertNull(song.sourceSongNumber)
    }

    @Test fun catalogFilenameMatchIgnoresExtensionCase() {
        val file = temporary.newFile("vendor_hash.MKV")
        val catalog = catalogSong("00522920", "vendor_hash.mkv")
        val song = LocalSongResolver.resolve(listOf(file), { listOf(catalog) }, { emptyList() }).single()
        assertEquals("晴天", song.title)
        assertEquals(file.name, song.filename)
        assertEquals("vendor_hash.mkv", catalog.filename)
    }

    @Test fun malformedMarkerAndUnmatchedFilesRetainManualImportBehavior() {
        val file = temporary.newFile("陈奕迅-十年.mpg")
        File(file.parentFile, file.name + ".complete.json").writeText("{broken")
        val songs = LocalSongResolver.resolve(listOf(file, file), { emptyList() }, { emptyList() })
        assertEquals(1, songs.size)
        assertEquals("十年", songs.single().title)
    }
}
