package com.local.ktv

import java.io.File
import java.util.Locale
import org.json.JSONObject

/** 扫描应用下载文件时，优先使用完成记录和歌曲编号恢复曲目信息。 */
internal object LocalSongResolver {
    private data class Entry(val file: File, val number: String?, val metadata: Song?)

    fun resolve(files: List<File>, byFilenames: (List<String>) -> List<Song>,
                byNumbers: (List<String>) -> List<Song>): List<Song> {
        val entries = files.distinctBy { it.absolutePath }.map { file ->
            val marker = completion(file)
            val number = marker?.optString("SongNumber")?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension.takeIf { it.matches(Regex("[0-9]+")) }
            val metadata = marker?.optJSONObject("song")?.let { json ->
                Song.fromJson(json).takeIf { it.sourceSongNumber == number && !it.title.isNullOrBlank() }
            }
            Entry(file, number, metadata)
        }
        val names = byFilenames(entries.map { it.file.name })
            .associateBy { it.filename.orEmpty().lowercase(Locale.ROOT) }
        val numbers = byNumbers(entries.mapNotNull { it.number }.distinct()).associateBy { it.sourceSongNumber }
        return entries.map { entry ->
            val local = Song.local(entry.file.absolutePath, entry.file.name)
            val matched = entry.number?.let { numbers[it] } ?: entry.metadata
                ?: names[entry.file.name.lowercase(Locale.ROOT)]
            val song = matched?.let { Song.fromJson(it.toJson()) } ?: local
            song.path = local.path
            song.filename = local.filename
            // 文件可能已搬到另一张卡或另一台设备，附属文件以当前位置为准。
            song.originalPath = local.originalPath
            song.accompanyPath = local.accompanyPath
            song.lyricPath = local.lyricPath
            song.downloadUrl = null
            song.videoUrl = null
            song.originalUrl = null
            song.accompanyUrl = null
            song.lyricUrl = null
            song
        }
    }

    private fun completion(file: File): JSONObject? = runCatching {
        val marker = File(file.parentFile, file.name + ".complete.json")
        if (!marker.isFile || marker.length() !in 1..1_048_576) return@runCatching null
        JSONObject(marker.readText()).takeIf {
            it.optString("provider") == "igeba" && it.optLong("bytes", -1) == file.length() &&
                !it.optString("SongNumber").isBlank()
        }
    }.getOrNull()
}
