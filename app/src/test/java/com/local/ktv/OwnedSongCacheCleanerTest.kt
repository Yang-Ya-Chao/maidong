package com.local.ktv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnedSongCacheCleanerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearsMediaPartialsAndSidecarsButKeepsImportAndDatabase() {
        val root = temporary.newFolder("MaidongKTV")
        val cache = File(root, "video/cloud-song").apply { mkdirs() }
        File(cache, "old.mkv").writeBytes(ByteArray(128))
        File(cache, "old.mkv.download").writeBytes(ByteArray(32))
        File(cache, "old.mkv.resume.json").writeText("{}")
        File(cache, "nested").mkdir()
        File(cache, "nested/old.complete.json").writeText("{}")
        val imported = File(root, "import/keep.mp4").apply { parentFile!!.mkdirs(); writeText("keep") }
        val database = File(root, "database/singer_portraits.db").apply { parentFile!!.mkdirs(); writeText("images") }

        val result = OwnedSongCacheCleaner.clear(cache)

        assertEquals(4, result.files)
        assertEquals(164L, result.bytes)
        assertTrue(cache.isDirectory)
        assertTrue(cache.listFiles()!!.isEmpty())
        assertEquals("keep", imported.readText())
        assertEquals("images", database.readText())
        assertEquals(OwnedSongCacheCleaner.Result(0, 0), OwnedSongCacheCleaner.clear(cache))
    }

    @Test fun pathOutsideCacheIsRejected() {
        val cache = temporary.newFolder("cloud-song")
        val outside = temporary.newFile("keep.mp4").apply { writeText("keep") }
        val escaped = object : File(cache, "escape") {
            override fun getCanonicalFile(): File = outside.canonicalFile
        }
        val view = object : File(cache.path) {
            override fun listFiles(): Array<File> = arrayOf(escaped)
        }
        assertTrue(runCatching { OwnedSongCacheCleaner.clear(view) }.isFailure)
        assertEquals("keep", outside.readText())
    }

    @Test fun absentCacheIsSafe() {
        assertEquals(OwnedSongCacheCleaner.Result(0, 0),
            OwnedSongCacheCleaner.clear(File(temporary.root, "missing")))
    }

    @Test fun nonDirectoryIsRejectedWithoutDeletingIt() {
        val file = temporary.newFile("song.mkv").apply { writeText("keep") }
        assertTrue(runCatching { OwnedSongCacheCleaner.clear(file) }.isFailure)
        assertEquals("keep", file.readText())
    }
}
