package com.local.ktv

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/** 仅查询 i歌霸目录缓存；保留页面调用契约，不打开旧 muse.db。 */
class IgebaCatalog {
    private var database: SQLiteDatabase? = null
    fun open(): Boolean {
        if (isAvailable()) return true
        if (!AppPaths.databaseFile.isFile) return false
        return runCatching {
            database = SQLiteDatabase.openDatabase(AppPaths.databaseFile.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            database!!.rawQuery("SELECT value FROM meta WHERE key='provider'", null).use {
                check(it.moveToFirst() && it.getString(0) == "igeba") { "目录来源不匹配" }
            }
            true
        }.getOrElse { close(); false }
    }
    fun close() { database?.close(); database = null }
    fun isAvailable(): Boolean = database?.isOpen == true
    fun dbFile(): File = AppPaths.databaseFile

    fun songCount(): Int = count("SELECT COUNT(*) FROM source_songs")
    fun singerCount(): Int = count("SELECT COUNT(*) FROM source_singers")

    private fun textCondition(keyword: String?, args: MutableList<String>, singer: Boolean = false): String {
        val value = keyword?.trim().orEmpty()
        if (value.isEmpty()) return "1=1"
        val columns = if (singer) listOf("sg.name", "sg.pinyin", "sg.pinyin_full")
            else listOf("s.name", "s.pinyin", "s.pinyin_full")
        columns.forEach { args += "%${value.uppercase(Locale.ROOT)}%" }
        return columns.joinToString(" OR ", "(", ")") { "$it LIKE ?" }
    }
    private fun songQuery(where: String, args: List<String>, offset: Int, limit: Int, order: String = "s.sort_no"): MutableList<Song> =
        songs("$SELECT WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
            (args + listOf(limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString())).toTypedArray())

    fun searchSongs(keyword: String?, offset: Int, limit: Int, language: String? = null): MutableList<Song> {
        val args = mutableListOf<String>()
        var where = textCondition(keyword, args)
        if (!language.isNullOrBlank() && language != "全部") { where += " AND s.language=?"; args += language }
        return songQuery(where, args, offset, limit)
    }
    fun searchSongCount(keyword: String?, language: String? = null): Int {
        val args = mutableListOf<String>()
        var where = textCondition(keyword, args)
        if (!language.isNullOrBlank() && language != "全部") { where += " AND s.language=?"; args += language }
        return count("SELECT COUNT(*) FROM source_songs s WHERE $where", args.toTypedArray())
    }
    fun searchSongsBySingerKeyword(keyword: String?, offset: Int, limit: Int): MutableList<Song> =
        songsBySingerName(keyword.orEmpty(), offset, limit)
    fun searchSongsBySingerKeywordCount(keyword: String?): Int = countSongsBySingerName(keyword.orEmpty())

    fun hotSongs(offset: Int, limit: Int): MutableList<Song> = songQuery("1=1", emptyList(), offset, limit,
        "COALESCE((SELECT ps.sort_no FROM source_playlist_songs ps JOIN source_playlists p ON p.number=ps.playlist_number AND p.package_type=ps.package_type WHERE ps.song_number=s.number AND p.package_type=4 AND p.name LIKE '热歌榜%' LIMIT 1),1000000),s.sort_no")
    fun hdSongs(offset: Int, limit: Int): MutableList<Song> = songQuery("1=1", emptyList(), offset, limit)
    fun quickSongs(limit: Int): MutableList<Song> = hotSongs(0, limit)
    fun songsByLanguage(language: String?, offset: Int, limit: Int): MutableList<Song> =
        if (language.isNullOrBlank() || language == "全部") hotSongs(offset, limit)
        else songQuery("s.language=?", listOf(language), offset, limit)
    fun countByLanguage(language: String?): Int =
        if (language.isNullOrBlank() || language == "全部") songCount()
        else count("SELECT COUNT(*) FROM source_songs WHERE language=?", arrayOf(language))
    fun songsByWordCount(wordCount: Int, offset: Int, limit: Int): MutableList<Song> =
        songQuery(if (wordCount <= 0) "1=1" else if (wordCount >= 6) "s.name_len>=6" else "s.name_len=$wordCount",
            emptyList(), offset, limit)
    fun countByWordCount(wordCount: Int): Int = count("SELECT COUNT(*) FROM source_songs WHERE " +
        if (wordCount <= 0) "1=1" else if (wordCount >= 6) "name_len>=6" else "name_len=$wordCount")
    fun languages(): MutableList<String> = rows("SELECT DISTINCT language FROM source_songs WHERE language!='' ORDER BY language", emptyArray()) { it.getString(0) }

    private fun singerWhere(keyword: String?, area: String?, type: String?, args: MutableList<String>): String {
        var where = textCondition(keyword, args, true)
        if (!area.isNullOrBlank() && area != "全部") {
            where += " AND sg.region=?"; args += when (area) { "内地" -> "大陆"; else -> area }
        }
        if (!type.isNullOrBlank() && type != "全部") {
            where += " AND sg.type=?"
            args += when { "男" in type -> "男"; "女" in type -> "女"; else -> "组合" }
        }
        return where
    }
    fun singers(area: String?, type: String?, offset: Int, limit: Int): MutableList<Array<String?>> =
        searchSingers("", area, type, offset, limit)
    fun searchSingers(keyword: String?, area: String?, type: String?, offset: Int, limit: Int): MutableList<Array<String?>> {
        val args = mutableListOf<String>()
        val where = singerWhere(keyword, area, type, args)
        return rows("SELECT sg.number,sg.name,sg.type,sg.region,sg.sort_no,sg.image_url FROM source_singers sg WHERE $where ORDER BY sg.sort_no LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray()) { c ->
            arrayOf(singerId(c.getString(0)), c.getString(1), c.getString(2), c.getString(3),
                (4807 - c.getInt(4)).toString(), SingerPortraitStore.imageFor(c.getString(1)) ?: c.getString(5))
        }
    }
    fun singerCount(area: String?, type: String?): Int = searchSingerCount("", area, type)
    fun searchSingerCount(keyword: String?, area: String?, type: String?): Int {
        val args = mutableListOf<String>()
        return count("SELECT COUNT(*) FROM source_singers sg WHERE " + singerWhere(keyword, area, type, args), args.toTypedArray())
    }
    fun songsBySinger(singerId: String, offset: Int, limit: Int): MutableList<Song> =
        searchSongsBySinger(singerId, "", offset, limit)
    fun countSongsBySinger(singerId: String): Int = countSearchSongsBySinger(singerId, "")
    fun searchSongsBySinger(singerId: String, keyword: String?, offset: Int, limit: Int): MutableList<Song> {
        val args = mutableListOf<String>(rawId(singerId))
        val where = "s.number IN (SELECT song_number FROM source_singer_songs WHERE singer_number=?) AND " + textCondition(keyword, args)
        return songQuery(where, args, offset, limit)
    }
    fun countSearchSongsBySinger(singerId: String, keyword: String?): Int {
        val args = mutableListOf<String>(rawId(singerId))
        val where = "s.number IN (SELECT song_number FROM source_singer_songs WHERE singer_number=?) AND " + textCondition(keyword, args)
        return count("SELECT COUNT(*) FROM source_songs s WHERE $where", args.toTypedArray())
    }
    private fun singerSongCondition(singerName: String): Pair<String, List<String>> {
        val value = "%${singerName.uppercase(Locale.ROOT)}%"
        val where = "s.singer LIKE ? OR s.number IN (SELECT r.song_number FROM source_singer_songs r " +
            "JOIN source_singers sg ON sg.number=r.singer_number " +
            "WHERE sg.name LIKE ? OR sg.pinyin LIKE ? OR sg.pinyin_full LIKE ?)"
        return where to listOf(value,value,value,value)
    }
    fun songsBySingerName(singerName: String, offset: Int, limit: Int): MutableList<Song> {
        val (where,args) = singerSongCondition(singerName)
        return songQuery("($where)",args,offset,limit)
    }
    fun countSongsBySingerName(singerName: String): Int {
        val (where,args) = singerSongCondition(singerName)
        return count("SELECT COUNT(*) FROM source_songs s WHERE ($where)",args.toTypedArray())
    }
    fun playlists(offset: Int, limit: Int): MutableList<Array<String?>> = categoryPlaylists("", offset, limit)
    fun categoryPlaylists(keyword: String?, offset: Int, limit: Int): MutableList<Array<String?>> =
        rows("SELECT number,name,song_count,sort_no FROM source_playlists WHERE package_type=3 AND name LIKE ? ORDER BY sort_no LIMIT ? OFFSET ?",
            arrayOf("%${keyword.orEmpty()}%", limit.toString(), offset.toString())) { c ->
            arrayOf(playlistId(c.getString(0), 3), c.getString(1), c.getInt(2).toString(), (1000-c.getInt(3)).toString())
        }
    fun categoryPlaylistCount(keyword: String?): Int =
        count("SELECT COUNT(*) FROM source_playlists WHERE package_type=3 AND name LIKE ?", arrayOf("%${keyword.orEmpty()}%"))
    fun rankPlaylists(): MutableList<Array<String?>> =
        rows("SELECT number,name,song_count FROM source_playlists WHERE package_type=4 ORDER BY sort_no", emptyArray()) { c ->
            arrayOf(playlistId(c.getString(0), 4), c.getString(1), "4", c.getInt(2).toString())
        }
    fun rankSongs(playlistId: String, offset: Int, limit: Int): MutableList<Song> = songsInPlaylist(playlistId, offset, limit)
    fun rankSongCount(playlistId: String): Int = playlistSongCount(playlistId)
    fun playlistSongCount(playlistId: String): Int = countSearchSongsInPlaylist(playlistId, "")
    fun songsInPlaylist(playlistId: String, offset: Int, limit: Int): MutableList<Song> =
        searchSongsInPlaylist(playlistId, "", offset, limit)
    fun searchSongsInPlaylist(playlistId: String, keyword: String?, offset: Int, limit: Int): MutableList<Song> {
        val args = mutableListOf(rawId(playlistId), packageType(playlistId).toString())
        val where = "ps.playlist_number=? AND ps.package_type=? AND " + textCondition(keyword, args)
        return songs("$SELECT JOIN source_playlist_songs ps ON ps.song_number=s.number WHERE $where ORDER BY ps.sort_no LIMIT ? OFFSET ?",
            (args + limit.toString() + offset.toString()).toTypedArray())
    }
    fun countSearchSongsInPlaylist(playlistId: String, keyword: String?): Int {
        val args = mutableListOf(rawId(playlistId), packageType(playlistId).toString())
        val where = "ps.playlist_number=? AND ps.package_type=? AND " + textCondition(keyword, args)
        return count("SELECT COUNT(*) FROM source_playlist_songs ps JOIN source_songs s ON s.number=ps.song_number WHERE $where", args.toTypedArray())
    }
    fun songById(songId: String?): Song? {
        if (songId.isNullOrBlank()) return null
        val number = rawId(songId)
        return songs("$SELECT WHERE s.number=? LIMIT 1", arrayOf(number)).firstOrNull()
            ?: PersonalMediaStore.find(songId)
    }
    fun matchLegacySong(legacy: Song): Song? {
        val title = legacy.title.orEmpty().trim()
            .replace(Regex("(?i)\\s*[(（]HD[)）]\\s*$"), "")
        val candidates = songs("$SELECT WHERE s.name=? LIMIT 100", arrayOf(title))
        fun singerKey(value: String?): String = java.text.Normalizer.normalize(value.orEmpty(),
            java.text.Normalizer.Form.NFKC).replace(Regex("[\\s/、&]+"), "").lowercase(Locale.ROOT)
        return candidates.filter { singerKey(it.singer) == singerKey(legacy.singer) }.singleOrNull()
    }

    fun songsByFilenames(filenames: Collection<String>): MutableList<Song> {
        val result = mutableListOf<Song>()
        filenames.distinct().chunked(300).forEach { names ->
            result += songs("$SELECT WHERE s.filename IN (${names.joinToString(",") { "?" }})", names.toTypedArray())
        }
        return result
    }
    fun localPathSongs(): MutableList<Song> = mutableListOf()

    private fun songs(sql: String, args: Array<String>): MutableList<Song> = rows(sql, args) { c ->
        Song().apply {
            sourceSongNumber = c.getString(0); id = songId(sourceSongNumber!!); dbId = id
            title = c.getString(1); singer = c.getString(2); singerNames = singer
            language = c.getString(3); category = language; filename = c.getString(4)
            sourceVersion = c.getString(5); sourceDurationSeconds = c.getInt(6)
            sourceVoiceChannel = c.getInt(7); pinyin = c.getString(8); pinyinFull = c.getString(9)
            album = c.getString(10); remote = true
            path = resolveSongFilePath(this)
        }
    }
    private fun count(sql: String, args: Array<String> = emptyArray()): Int =
        rows(sql, args) { it.getInt(0) }.firstOrNull() ?: 0
    private fun <T> rows(sql: String, args: Array<String>, read: (Cursor) -> T): MutableList<T> {
        val db = database?.takeIf { it.isOpen } ?: return mutableListOf()
        return db.rawQuery(sql, args).use { c -> mutableListOf<T>().apply { while (c.moveToNext()) add(read(c)) } }
    }

    companion object {
        const val PAGE_SIZE = 10
        const val VIDEO_ROOT = AppPaths.VIDEO_PATH
        const val CLOUD_SONG_DIR = "cloud-song"
        const val LOCAL_SONG_DIR = "local"
        private const val SELECT = "SELECT s.number,s.name,s.singer,s.language,s.filename,s.version,s.duration,s.voice_channel,s.pinyin,s.pinyin_full,s.album FROM source_songs s"
        fun rawId(id: String): String = id.substringAfterLast(':')
        fun songId(number: String): String = "igeba:song:$number"
        fun singerId(number: String): String = "igeba:singer:$number"
        fun playlistId(number: String, type: Int): String = "igeba:playlist:$type:$number"
        private fun packageType(id: String): Int = id.split(':').getOrNull(2)?.toIntOrNull() ?: 3
        @JvmStatic fun resolveSongFilePath(song: Song?): String =
            song?.filename?.takeIf(String::isNotBlank)?.let { File(AppPaths.cloudSongsDir, it).absolutePath }.orEmpty()
        @JvmStatic fun getStandardLocalPath(filename: String?): String =
            filename?.takeIf(String::isNotBlank)?.let { File(AppPaths.cloudSongsDir, it).absolutePath }.orEmpty()
        @JvmStatic fun songDirectories(): List<File> = listOf(AppPaths.cloudSongsDir)
        @JvmStatic fun defaultDbFile(): File = AppPaths.databaseFile
    }
}
