package com.local.ktv

import android.os.Environment
import java.io.File

/** The only external-storage namespace used by the fresh Maidong KTV app. */
object AppPaths {
    val root: File by lazy {
        val primary = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        primary?.let(::writableAppDirectory)?.let { return@lazy it }

        // 旧电视可能把主存储挂在 sdcard0。只回退到已挂载的主存储路径。
        val mounts = runCatching { File("/proc/self/mountinfo").readText() }
            .getOrNull()?.let(UsbMountDiscovery::candidates).orEmpty()
        listOf("/storage/sdcard0", "/mnt/sdcard")
            .asSequence()
            .filter { it in mounts }
            .mapNotNull { writableAppDirectory(File(it)) }
            .firstOrNull() ?: error("本机存储不可写")
    }

    private fun writableAppDirectory(volume: File): File? = runCatching {
        if (!volume.isDirectory) return@runCatching null
        val directory = File(volume, "MaidongKTV")
        if (!directory.isDirectory && !directory.mkdir()) return@runCatching null
        val probe = File.createTempFile(".maidong-write-", ".tmp", directory)
        try {
            probe.outputStream().use { it.write(1) }
        } finally {
            check(probe.delete()) { "本机存储写入检查文件清理失败" }
        }
        directory
    }.getOrNull()
    val stateFile: File by lazy { File(root, "state.json") }
    val songsDir: File by lazy { File(root, "songs") }
    val importDir: File by lazy { File(root, "import") }
    val catalogFile: File by lazy { File(root, "catalog.json") }
    val recordsDir: File by lazy { File(root, "records") }
    val moviesDir: File by lazy { File(root, "movies") }
    val appsDir: File by lazy { File(root, "apps") }
    val backupDir: File by lazy { File(root, "backup") }
    val databaseDir: File by lazy { File(root, "database") }
    val databaseFile: File by lazy { File(databaseDir, "igeba_catalog.db") }
    val singerPortraitFile: File by lazy { File(databaseDir, "singer_portraits.db") }
    val igebaSessionFile: File by lazy { File(root, "igeba_session.json") }
    val videoDir: File by lazy { File(root, "video") }
    val cloudSongsDir: File by lazy { File(videoDir, "cloud-song") }

    // 字符串形式保留给旧代码引用（不再是编译期常量）。
    val ROOT_PATH: String get() = root.absolutePath
    val VIDEO_PATH: String get() = videoDir.absolutePath

    fun ensureDirectories() {
        listOf(root, songsDir, importDir, recordsDir, moviesDir, appsDir, backupDir,
            databaseDir, videoDir, cloudSongsDir).forEach {
            check((it.isDirectory || it.mkdirs()) && it.canWrite()) { "本机目录不可写：${it.absolutePath}" }
        }
    }
}
