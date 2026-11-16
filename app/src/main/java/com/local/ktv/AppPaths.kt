package com.local.ktv

import android.os.Environment
import java.io.File

/** The only external-storage namespace used by the fresh Maidong KTV app. */
object AppPaths {
    // 主外部存储根目录。优先系统 API；部分厂家电视（如 TCL）不设置 EXTERNAL_STORAGE，
    // getExternalStorageDirectory() 会返回不存在的 /storage/emulated/0，因此失败时
    // 回退到解析 /proc/self/mountinfo 里的真实主存储挂载点（例如 /storage/sdcard0）。
    private val externalRoot: String by lazy {
        val api = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrNull()?.trimEnd('/')
        if (api != null && api.isNotEmpty() && canCreate(api)) return@lazy api
        primaryMountFromProc() ?: api ?: "/storage/emulated/0"
    }

    private fun canCreate(path: String): Boolean =
        runCatching { File(path).let { it.exists() || it.mkdirs() } }.getOrDefault(false)

    private fun primaryMountFromProc(): String? =
        runCatching { File("/proc/self/mountinfo").readText() }.getOrNull()
            ?.let { UsbMountDiscovery.candidates(it).firstOrNull() }

    val root: File by lazy { File(externalRoot, "MaidongKTV") }
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
            check(it.exists() || it.mkdirs()) { "Cannot create ${it.absolutePath}" }
        }
    }
}
