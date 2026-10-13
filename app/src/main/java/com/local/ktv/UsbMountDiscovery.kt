package com.local.ktv

import java.io.File

/** 解析当前进程可见的挂载点，不根据 FAT32/NTFS/exFAT 设置文件系统白名单。 */
internal object UsbMountDiscovery {
    const val SONG_DIRECTORY = "maidongktv/video/cloud-song"
    fun songDirectory(root: File): File {
        val canonicalRoot = root.canonicalFile
        val folder = File(canonicalRoot, SONG_DIRECTORY).canonicalFile
        check(folder.path.startsWith(canonicalRoot.path + File.separator)) { "U盘歌曲目录无效" }
        return folder
    }

    fun songs(root: File): List<File> {
        val folder = songDirectory(root)
        if (!folder.exists()) return emptyList()
        val pending = java.util.ArrayDeque<File>().apply { add(folder) }
        val visited = HashSet<String>()
        val result = ArrayList<File>()
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst().canonicalFile
            if (directory != folder && !directory.path.startsWith(folder.path + File.separator)) continue
            if (!visited.add(directory.path)) continue
            checkNotNull(directory.listFiles()) { "无法读取U盘歌曲保存目录，请检查存储权限" }.forEach { file ->
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(folder.path + File.separator)) return@forEach
                if (canonical.isDirectory) pending.add(canonical)
                else if (canonical.extension.lowercase() in setOf("mkv", "mp4", "mpg", "mpeg", "avi", "mp3", "flac", "wav")) {
                    result += canonical
                }
            }
        }
        return result
    }

    fun publicVolumeRoot(uuid: String?): File? = uuid?.takeIf {
        it.matches(Regex("[A-Za-z0-9_-]+")) &&
            it.lowercase() !in setOf("primary", "emulated", "self")
    }?.let { File("/storage", it) }

    /** 厂家可能不向应用专属目录或 StorageVolume 列表报告 SD 卡。 */
    fun publicRoots(storage: File, primary: File?): List<File> =
        storage.listFiles().orEmpty().filter { candidate ->
            if (candidate.name.lowercase() in setOf("emulated", "self", "primary", "enc_emulated")) return@filter false
            runCatching {
                val folder = candidate.canonicalFile
                val internal = primary?.canonicalFile
                folder.isDirectory && folder.canRead() && folder.listFiles() != null &&
                    (internal == null || folder != internal &&
                        !folder.path.startsWith(internal.path + File.separator) &&
                        !internal.path.startsWith(folder.path + File.separator))
            }.getOrDefault(false)
        }.map { it.canonicalFile }.distinct()

    private val internalMnt = setOf("runtime", "user", "installer", "pass_through", "expand",
        "obb", "asec", "secure", "shell", "vendor", "product", "misc", "androidwritable")
    private fun decode(value: String): String =
        Regex("""\\([0-7]{3})""").replace(value) { it.groupValues[1].toInt(8).toChar().toString() }

    fun candidates(mountInfo: String): List<String> {
        val mounts = linkedMapOf<String, String>()
        mountInfo.lineSequence().forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            val separator = fields.indexOf("-")
            if (fields.size < 10 || separator < 6 || fields.size <= separator + 2) return@forEach
            val path = decode(fields[4]).trimEnd('/')
            val fs = fields[separator + 1]
            val source = decode(fields[separator + 2])
            val parts = path.split('/').filter(String::isNotEmpty)
            if (parts.size < 2 || "Android" in parts) return@forEach
            when (parts[0]) {
                "storage" -> if (parts.size != 2 || parts[1] in setOf("emulated", "self")) return@forEach
                "mnt" -> if (parts[1] in internalMnt || !source.startsWith("/dev/") &&
                    !fs.startsWith("fuse") && fs !in setOf("sdcardfs", "esdfs")) return@forEach
                else -> return@forEach
            }
            // 同一挂载在不同可见路径下的别名优先使用 /storage。
            val identity = fields[2] + ":" + fields[3]
            val previous = mounts[identity]
            if (previous == null || path.startsWith("/storage/") && !previous.startsWith("/storage/")) {
                mounts[identity] = path
            }
        }
        return mounts.values.sortedWith(compareBy<String> { !it.startsWith("/storage/") }.thenBy { it })
    }
}
