package com.local.ktv

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsbMountDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun discoversAllMountedFilesystemFormats() {
        val formats = listOf("vfat", "ntfs", "exfat", "ntfs3", "fuseblk")
        val mounts = formats.mapIndexed { index, fs ->
            "${index + 10} 1 8:${index + 1} / /storage/disk$index rw shared:1 - $fs /dev/block/disk$index rw"
        }.joinToString("\n")
        assertEquals(formats.indices.map { "/storage/disk$it" }, UsbMountDiscovery.candidates(mounts))
    }

    @Test fun rejectsInternalMountsAndPrefersPublicAlias() {
        val mounts = """
            10 1 8:1 / /mnt/media_rw/USB rw - exfat /dev/block/sda1 rw
            11 1 8:1 / /storage/USB rw - exfat /dev/block/sda1 rw
            12 1 0:2 / /storage/emulated rw - fuse /dev/fuse rw
            13 1 0:3 / /mnt/runtime/default/USB rw - sdcardfs /dev/fuse rw
            14 1 8:4 / /system rw - ext4 /dev/block/system rw
            15 1 0:5 / /mnt/usb_storage/My\040Disk rw - fuse vendor-service rw
        """.trimIndent()
        assertEquals(listOf("/storage/USB", "/mnt/usb_storage/My Disk"), UsbMountDiscovery.candidates(mounts))
    }

    @Test fun scansOnlyDownloadDirectoryAndRecognizesUppercaseExtension() {
        val root = temporary.newFolder("usb")
        val folder = UsbMountDiscovery.songDirectory(root).apply { assertTrue(mkdirs()) }
        val song = File(folder, "周杰伦-晴天.MKV").apply { writeText("media") }
        val nested = File(folder, "合集").apply { mkdir() }
        val second = File(nested, "陈奕迅-十年.mp4").apply { writeText("media") }
        File(root, "其他歌手-根目录.mkv").writeText("media")
        File(root, "maidongktv/import").apply { mkdirs() }
            .let { File(it, "其他歌手-其他目录.mkv").writeText("media") }
        File(folder, "说明.txt").writeText("text")
        assertEquals(setOf(song.canonicalFile, second.canonicalFile), UsbMountDiscovery.songs(root).toSet())
    }

    @Test fun scanningDoesNotCreateAnyFolder() {
        val root = temporary.newFolder("empty-usb")
        assertTrue(UsbMountDiscovery.songs(root).isEmpty())
        assertFalse(File(root, "maidongktv").exists())
    }
}
