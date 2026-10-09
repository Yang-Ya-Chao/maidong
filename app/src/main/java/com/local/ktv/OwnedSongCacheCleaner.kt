package com.local.ktv

import java.io.File

/** Only clears the explicitly supplied app-owned cache, never an import or USB directory. */
internal object OwnedSongCacheCleaner {
    data class Result(val files: Int, val bytes: Long)

    fun clear(directory: File): Result {
        if (!directory.exists()) return Result(0, 0)
        check(directory.isDirectory) { "歌曲缓存目录不可用" }
        val root = directory.canonicalFile
        val visited = mutableSetOf(root.path)
        var files = 0
        var bytes = 0L
        fun remove(file: File) {
            val resolved = file.canonicalFile
            check(resolved != root && resolved.path.startsWith(root.path + File.separator)) { "缓存文件路径超出清理范围" }
            if (file.isDirectory) {
                check(visited.add(resolved.path)) { "歌曲缓存目录存在循环链接" }
                val children = checkNotNull(file.listFiles()) { "无法读取歌曲缓存目录" }
                children.forEach(::remove)
            } else {
                bytes += file.length()
                files++
            }
            check(file.delete() || !file.exists()) { "无法删除旧歌曲缓存：${file.name}" }
        }
        checkNotNull(directory.listFiles()) { "无法读取歌曲缓存目录" }.forEach(::remove)
        return Result(files, bytes)
    }
}
