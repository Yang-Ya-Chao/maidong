package com.local.ktv

import java.io.File
import java.util.Locale
import org.json.JSONObject

/** 扫描应用下载文件时，优先使用完成记录和歌曲编号恢复曲目信息。 */
internal object LocalSongResolver {
    data class Entry(val path: String, val name: String, val metadata: Song? = null,
                     val number: String? = null)

    fun resolve(files: List<File>, byFilenames: (List<String>) -> List<Song>,
                byNumbers: (List<String>) -> List<Song>): List<Song> =
        resolveEntries(files.distinctBy { it.absolutePath }.map { file ->
            val marker = completion(file)
            val number = marker?.optString("SongNumber")?.takeIf { it.isNotBlank() }
            val metadata = marker?.optJSONObject("song")?.let { json ->
                Song.fromJson(json).takeIf {
                    !it.title.isNullOrBlank() && (marker.optString("provider") == "openlist" ||
                        it.sourceSongNumber == number)
                }
            }
            Entry(file.absolutePath, file.name, metadata, number)
        }, byFilenames, byNumbers)

    /** 本地、U盘、网盘都优先匹配实际文件名，再匹配源编号；保持数字编号中的前导零。 */
    fun resolveEntries(entries: List<Entry>, byFilenames: (List<String>) -> List<Song>,
                       byNumbers: (List<String>) -> List<Song>): List<Song> {
        val unique = entries.distinctBy { it.path }
        val names = byFilenames(unique.map { it.name })
            .associateBy { it.filename.orEmpty().lowercase(Locale.ROOT) }
        fun number(entry: Entry): String? = entry.number ?: entry.metadata?.sourceSongNumber
            ?: entry.name.substringBeforeLast('.', entry.name).takeIf { it.matches(Regex("[0-9]+")) }
        val numbers = byNumbers(unique.mapNotNull(::number).distinct()).associateBy { it.sourceSongNumber }
        return unique.map { entry ->
            val local = Song.local(entry.path, entry.name)
            val matched = names[entry.name.lowercase(Locale.ROOT)] ?: number(entry)?.let { numbers[it] }
                ?: entry.metadata
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
            it.optString("provider") in setOf("igeba", "openlist") &&
                it.optLong("bytes", -1) == file.length() &&
                (it.optString("provider") == "openlist" || !it.optString("SongNumber").isBlank())
        }
    }.getOrNull()
}
