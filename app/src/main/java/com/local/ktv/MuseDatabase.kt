package com.local.ktv

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale

class MuseDatabase @JvmOverloads constructor(initialSourceFile: File? = null) {
    private var sourceFile: File? = initialSourceFile
    private var database: SQLiteDatabase? = null
    private var available = false
    private var cdnPath = DEFAULT_CDN_PATH

    fun open(): Boolean {
        if (database != null) return isAvailable()
        val file = (sourceFile?.takeIf { it.exists() } ?: findDatabaseFile()) ?: return false
        sourceFile = file
        available = runCatching {
            database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            database?.isOpen == true
        }.onFailure { Log.e(TAG, "打开曲库数据库失败: ${file.absolutePath}", it) }.getOrDefault(false)
        if (available) cdnPath = readCdnPath()
        return available
    }

    fun close() {
        runCatching { database?.close() }
        database = null
        available = false
        cdnPath = DEFAULT_CDN_PATH
    }

    fun isAvailable(): Boolean = available && database?.isOpen == true

    fun dbFile(): File? = sourceFile

    fun songCount(): Int = count("SELECT COUNT(*) FROM songs WHERE deleted_at IS NULL")

    fun singerCount(): Int = count("SELECT COUNT(*) FROM singers WHERE deleted_at IS NULL AND name != ''")

    fun getCloudUrl(musicno: String): String? {
        val db = database?.takeIf { isAvailable() } ?: return null
        return runCatching {
            db.rawQuery(
                "SELECT cloud_url FROM songs WHERE (filename_prefix=? OR filename LIKE ?) AND cloud_url IS NOT NULL LIMIT 1",
                arrayOf(musicno, "$musicno.%")
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.onFailure { Log.e(TAG, "getCloudUrl: $musicno", it) }.getOrNull()
    }

    fun searchSongs(keyword: String?, offset: Int, limit: Int, language: String? = null): MutableList<Song> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return mutableListOf()
        val args = mutableListOf<String>()
        val condition = songKeywordCondition(query, args)
        val languageCondition = if (!language.isNullOrEmpty() && language != "全部") {
            args += language
            " AND s.lang=?"
        } else {
            ""
        }
        args += limit.toString()
        args += offset.toString()
        return songs(
            "$SONG_SELECT WHERE s.deleted_at IS NULL " +
                "AND $condition$languageCondition " +
                "$RECOMMEND_ORDER, s.name ASC LIMIT ? OFFSET ?",
            args.toTypedArray(),
        )
    }

    fun searchSongCount(keyword: String?, language: String? = null): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return 0
        val args = mutableListOf<String>()
        val condition = songKeywordCondition(query, args, tableAlias = "")
        val languageCondition = if (!language.isNullOrEmpty() && language != "全部") {
            args += language
            " AND lang=?"
        } else {
            ""
        }
        return count(
            "SELECT COUNT(*) FROM songs WHERE deleted_at IS NULL " +
                "AND $condition$languageCondition",
            args.toTypedArray(),
        )
    }

    fun searchSongsBySingerKeyword(keyword: String?, offset: Int, limit: Int): MutableList<Song> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return mutableListOf()
        val singerArgs = mutableListOf<String>()
        val singerCondition = textKeywordCondition(
            query,
            "sg.",
            listOf("name", "name_cap", "name_full", "name_trim"),
            singerArgs,
        )
        return songs(
            "$SONG_SELECT WHERE s.deleted_at IS NULL AND (" +
                "s.singer_names LIKE ? OR EXISTS (SELECT 1 FROM song_singer_relations ssr " +
                "INNER JOIN singers sg ON sg.id=ssr.singer_id " +
                "WHERE ssr.song_id=s.id AND sg.deleted_at IS NULL AND $singerCondition)) " +
                "$RECOMMEND_ORDER LIMIT ? OFFSET ?",
            (listOf("%$query%") + singerArgs + limit.toString() + offset.toString()).toTypedArray(),
        )
    }

    fun searchSongsBySingerKeywordCount(keyword: String?): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return 0
        val singerArgs = mutableListOf<String>()
        val singerCondition = textKeywordCondition(
            query,
            "sg.",
            listOf("name", "name_cap", "name_full", "name_trim"),
            singerArgs,
        )
        return count(
            "SELECT COUNT(*) FROM songs s WHERE s.deleted_at IS NULL AND (" +
                "s.singer_names LIKE ? OR EXISTS (SELECT 1 FROM song_singer_relations ssr " +
                "INNER JOIN singers sg ON sg.id=ssr.singer_id " +
                "WHERE ssr.song_id=s.id AND sg.deleted_at IS NULL AND $singerCondition))",
            (listOf("%$query%") + singerArgs).toTypedArray(),
        )
    }

    fun searchSingers(
        keyword: String?,
        area: String?,
        type: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Array<String?>> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return singers(area, type, offset, limit)
        val args = mutableListOf<String>()
        val condition = textKeywordCondition(query, "", listOf("name", "name_cap", "name_full", "name_trim"), args)
        val filters = StringBuilder()
        if (!area.isNullOrEmpty()) {
            filters.append(" AND area=?")
            args += area
        }
        if (!type.isNullOrEmpty()) {
            filters.append(" AND type=?")
            args += type
        }
        args += limit.toString()
        args += offset.toString()
        return rows(
            "SELECT id, name, type, area, hot_score, image FROM singers " +
                "WHERE deleted_at IS NULL AND name != '' AND name != '#0000FF' AND $condition$filters " +
                "ORDER BY local_hot_score DESC, hot_score DESC, name ASC LIMIT ? OFFSET ?",
            args.toTypedArray(),
        ) { cursor ->
            arrayOf(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getInt(4).toString(), resolveSingerImageUrl(cursor.getString(5)),
            )
        }
    }

    fun searchSingerCount(keyword: String?, area: String?, type: String?): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return singerCount(area, type)
        val args = mutableListOf<String>()
        val condition = textKeywordCondition(query, "", listOf("name", "name_cap", "name_full", "name_trim"), args)
        val filters = StringBuilder()
        if (!area.isNullOrEmpty()) {
            filters.append(" AND area=?")
            args += area
        }
        if (!type.isNullOrEmpty()) {
            filters.append(" AND type=?")
            args += type
        }
        return count(
            "SELECT COUNT(*) FROM singers WHERE deleted_at IS NULL AND name != '' AND name != '#0000FF' " +
                "AND $condition$filters",
            args.toTypedArray(),
        )
    }

    fun hotSongs(offset: Int, limit: Int): MutableList<Song> = songs(
        "$SONG_SELECT WHERE s.deleted_at IS NULL $RECOMMEND_ORDER LIMIT ? OFFSET ?",
        pageArgs(limit, offset),
    )

    fun hdSongs(offset: Int, limit: Int): MutableList<Song> = songs(
        "$SONG_SELECT WHERE s.deleted_at IS NULL AND s.name LIKE '%(HD)%' " +
            "$RECOMMEND_ORDER LIMIT ? OFFSET ?",
        pageArgs(limit, offset),
    )

    fun quickSongs(limit: Int): MutableList<Song> = songs(
        "$SONG_SELECT WHERE s.deleted_at IS NULL LIMIT ?",
        arrayOf(limit.toString()),
    )

    fun songsByLanguage(language: String?, offset: Int, limit: Int): MutableList<Song> {
        if (language.isNullOrEmpty() || language == "全部") return hotSongs(offset, limit)
        return songs(
            "$SONG_SELECT WHERE s.deleted_at IS NULL AND s.lang=? $RECOMMEND_ORDER LIMIT ? OFFSET ?",
            arrayOf(language, limit.toString(), offset.toString()),
        )
    }

    fun countByLanguage(language: String?): Int = if (language.isNullOrEmpty() || language == "全部") {
        songCount()
    } else {
        count(
            "SELECT COUNT(*) FROM songs WHERE deleted_at IS NULL AND lang=?",
            arrayOf(language),
        )
    }

    fun songsByWordCount(wordCount: Int, offset: Int, limit: Int): MutableList<Song> {
        if (wordCount <= 0) return hotSongs(offset, limit)
        val condition = if (wordCount >= 6) "s.name_len >= 6" else "s.name_len = $wordCount"
        return songs(
            "$SONG_SELECT WHERE s.deleted_at IS NULL AND $condition $RECOMMEND_ORDER LIMIT ? OFFSET ?",
            pageArgs(limit, offset),
        )
    }

    fun countByWordCount(wordCount: Int): Int {
        if (wordCount <= 0) return songCount()
        val condition = if (wordCount >= 6) "name_len >= 6" else "name_len = $wordCount"
        return count("SELECT COUNT(*) FROM songs WHERE deleted_at IS NULL AND $condition")
    }

    fun languages(): MutableList<String> = stringRows(
        "SELECT name FROM song_langs WHERE deleted_at IS NULL ORDER BY sort_no ASC",
    )

    fun singers(area: String?, type: String?, offset: Int, limit: Int): MutableList<Array<String?>> {
        val args = mutableListOf<String>()
        val sql = StringBuilder(
            "SELECT id, name, type, area, hot_score, image FROM singers " +
                "WHERE deleted_at IS NULL AND name != '' AND name != '#0000FF' ",
        )
        appendSingerFilters(sql, args, area, type)
        sql.append("ORDER BY local_hot_score DESC, hot_score DESC, name ASC LIMIT ? OFFSET ?")
        args += limit.toString()
        args += offset.toString()
        return rows(sql.toString(), args.toTypedArray()) { cursor ->
            arrayOf(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getInt(4).toString(), resolveSingerImageUrl(cursor.getString(5)),
            )
        }
    }

    fun singerCount(area: String?, type: String?): Int {
        val args = mutableListOf<String>()
        val sql = StringBuilder(
            "SELECT COUNT(*) FROM singers WHERE deleted_at IS NULL AND name != '' AND name != '#0000FF' ",
        )
        appendSingerFilters(sql, args, area, type)
        return count(sql.toString(), args.toTypedArray())
    }

    fun songsBySinger(singerId: String, offset: Int, limit: Int): MutableList<Song> = songs(
        "$SONG_SELECT INNER JOIN song_singer_relations ssr ON s.id=ssr.song_id " +
            "WHERE ssr.singer_id=? AND s.deleted_at IS NULL " +
            "$RECOMMEND_ORDER LIMIT ? OFFSET ?",
        arrayOf(singerId, limit.toString(), offset.toString()),
    )

    fun countSongsBySinger(singerId: String): Int = count(
        "SELECT COUNT(*) FROM song_singer_relations ssr INNER JOIN songs s ON s.id=ssr.song_id " +
            "WHERE ssr.singer_id=? AND s.deleted_at IS NULL",
        arrayOf(singerId),
    )

    fun searchSongsBySinger(
        singerId: String,
        keyword: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Song> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return songsBySinger(singerId, offset, limit)
        val args = mutableListOf(singerId)
        val condition = songKeywordCondition(query, args)
        args += limit.toString()
        args += offset.toString()
        return songs(
            "$SONG_SELECT INNER JOIN song_singer_relations ssr ON s.id=ssr.song_id " +
                "WHERE ssr.singer_id=? AND s.deleted_at IS NULL AND $condition " +
                "$RECOMMEND_ORDER LIMIT ? OFFSET ?",
            args.toTypedArray(),
        )
    }

    fun countSearchSongsBySinger(singerId: String, keyword: String?): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return countSongsBySinger(singerId)
        val args = mutableListOf(singerId)
        val condition = songKeywordCondition(query, args)
        return count(
            "SELECT COUNT(*) FROM songs s INNER JOIN song_singer_relations ssr ON s.id=ssr.song_id " +
                "WHERE ssr.singer_id=? AND s.deleted_at IS NULL AND $condition",
            args.toTypedArray(),
        )
    }

    fun songsBySingerName(singerName: String, offset: Int, limit: Int): MutableList<Song> {
        val pattern = "%$singerName%"
        return songs(
            "$SONG_SELECT WHERE s.deleted_at IS NULL " +
                "AND (s.singer_names LIKE ? OR s.id IN (SELECT ssr.song_id FROM song_singer_relations ssr " +
                "INNER JOIN singers sg ON ssr.singer_id=sg.id WHERE sg.name LIKE ?)) " +
                "$RECOMMEND_ORDER LIMIT ? OFFSET ?",
            arrayOf(pattern, pattern, limit.toString(), offset.toString()),
        )
    }

    fun countSongsBySingerName(singerName: String): Int {
        val pattern = "%$singerName%"
        return count(
            "SELECT COUNT(*) FROM songs s WHERE s.deleted_at IS NULL " +
                "AND (s.singer_names LIKE ? OR s.id IN (SELECT ssr.song_id FROM song_singer_relations ssr " +
                "INNER JOIN singers sg ON ssr.singer_id=sg.id WHERE sg.name LIKE ?))",
            arrayOf(pattern, pattern),
        )
    }

    fun playlists(offset: Int, limit: Int): MutableList<Array<String?>> = rows(
        "SELECT id, name, song_count, hot_score FROM playlists WHERE deleted_at IS NULL " +
            "ORDER BY hot_score DESC LIMIT ? OFFSET ?",
        pageArgs(limit, offset),
    ) { cursor ->
        arrayOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(), cursor.getLong(3).toString())
    }

    fun rankPlaylists(): MutableList<Array<String?>> = rows(
        "SELECT id, name, rank_type, song_count FROM playlists " +
            "WHERE deleted_at IS NULL AND type=2 " +
            "ORDER BY CASE WHEN rank_type IS NULL THEN 1 ELSE 0 END, rank_type ASC, rec_score DESC",
    ) { cursor ->
        arrayOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(), cursor.getInt(3).toString())
    }

    fun rankSongs(playlistId: String, offset: Int, limit: Int): MutableList<Song> =
        songsInPlaylist(playlistId, offset, limit)

    fun rankSongCount(playlistId: String): Int = count(
        "SELECT COUNT(*) FROM playlist_songs ps INNER JOIN songs s ON s.id=ps.song_id " +
            "WHERE ps.playlist_id=? AND s.deleted_at IS NULL",
        arrayOf(playlistId),
    )

    fun playlistSongCount(playlistId: String): Int = rankSongCount(playlistId)

    fun categoryPlaylists(keyword: String?, offset: Int, limit: Int): MutableList<Array<String?>> {
        val query = keyword?.trim().orEmpty()
        val args = mutableListOf<String>()
        val searchCondition = if (query.isEmpty()) {
            ""
        } else {
            " AND " + textKeywordCondition(query, "", listOf("name", "name_cap", "name_full"), args)
        }
        args += limit.toString()
        args += offset.toString()
        return rows(
            "SELECT id, name, song_count, image FROM playlists " +
                "WHERE deleted_at IS NULL AND type=1$searchCondition " +
                "ORDER BY CASE WHEN rec_score IS NULL THEN 1 ELSE 0 END, rec_score DESC, hot_score DESC " +
                "LIMIT ? OFFSET ?",
            args.toTypedArray(),
        ) { cursor ->
            arrayOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(), cursor.getString(3))
        }
    }

    fun categoryPlaylistCount(keyword: String?): Int {
        val query = keyword?.trim().orEmpty()
        val args = mutableListOf<String>()
        val searchCondition = if (query.isEmpty()) {
            ""
        } else {
            " AND " + textKeywordCondition(query, "", listOf("name", "name_cap", "name_full"), args)
        }
        return count(
            "SELECT COUNT(*) FROM playlists WHERE deleted_at IS NULL AND type=1$searchCondition",
            args.toTypedArray(),
        )
    }

    fun songsInPlaylist(playlistId: String, offset: Int, limit: Int): MutableList<Song> = songs(
        "$SONG_SELECT INNER JOIN playlist_songs ps ON s.id=ps.song_id " +
            "WHERE ps.playlist_id=? AND s.deleted_at IS NULL " +
            "ORDER BY ps.sort_no ASC LIMIT ? OFFSET ?",
        arrayOf(playlistId, limit.toString(), offset.toString()),
    )

    fun searchSongsInPlaylist(
        playlistId: String,
        keyword: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Song> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return songsInPlaylist(playlistId, offset, limit)
        val args = mutableListOf(playlistId)
        val condition = songKeywordCondition(query, args)
        args += limit.toString()
        args += offset.toString()
        return songs(
            "$SONG_SELECT INNER JOIN playlist_songs ps ON s.id=ps.song_id " +
                "WHERE ps.playlist_id=? AND s.deleted_at IS NULL AND $condition " +
                "ORDER BY ps.sort_no ASC LIMIT ? OFFSET ?",
            args.toTypedArray(),
        )
    }

    fun countSearchSongsInPlaylist(playlistId: String, keyword: String?): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return playlistSongCount(playlistId)
        val args = mutableListOf(playlistId)
        val condition = songKeywordCondition(query, args)
        return count(
            "SELECT COUNT(*) FROM songs s INNER JOIN playlist_songs ps ON s.id=ps.song_id " +
                "WHERE ps.playlist_id=? AND s.deleted_at IS NULL AND $condition",
            args.toTypedArray(),
        )
    }

    fun songById(songId: String?): Song? {
        if (songId == null) return null
        return songs(
            "$SONG_SELECT WHERE s.id=? AND s.deleted_at IS NULL LIMIT 1",
            arrayOf(songId),
        ).firstOrNull()
    }

    fun songsByFilenames(filenames: Collection<String>): MutableList<Song> {
        if (filenames.isEmpty()) return mutableListOf()
        val result = mutableListOf<Song>()
        filenames.distinct().chunked(400).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            result += songs(
                "$SONG_SELECT WHERE s.deleted_at IS NULL AND s.filename IN ($placeholders)",
                chunk.toTypedArray(),
            )
        }
        return result
    }

    /** Songs imported or downloaded by earlier app versions keep their directory in songs.path. */
    fun localPathSongs(): MutableList<Song> = songs(
        "$SONG_SELECT WHERE s.deleted_at IS NULL " +
            "AND s.path IS NOT NULL AND TRIM(s.path) != ''",
    )

    private fun songs(sql: String, args: Array<String>? = null): MutableList<Song> = rows(sql, args, ::songFromCursor)

    private fun songFromCursor(cursor: Cursor): Song = Song().apply {
        dbId = cursor.getString(0)
        title = cursor.getString(1)
        pinyin = cursor.getString(2)
        pinyinFull = cursor.getString(3)
        language = cursor.getString(4)
        filename = cursor.getString(5)
        dbPath = cursor.getString(6)
        accomp = cursor.getString(7)?.trim()?.toIntOrNull() ?: 0
        hotScore = cursor.getInt(8)
        localHotScore = cursor.getInt(9)
        lyricPath = cursor.getString(10)
        originalPath = cursor.getString(11)
        // Column 12 mirrors the numeric accomp mode in this schema, not a file path.
        accompanyPath = ""
        pOrigin = cursor.getString(13)
        pAccomp = cursor.getString(14)
        singerNames = cursor.getString(15)
        oModeChannels = cursor.getString(16)
        remote = false
        id = dbId
        singer = singerNames ?: "未知歌手"
        category = language ?: "国语"
        playCount = if (localHotScore > 0) localHotScore else hotScore
        path = resolveSongFilePath(this)
    }

    private fun count(sql: String, args: Array<String>? = null): Int {
        val db = database?.takeIf { isAvailable() } ?: return 0
        return runCatching {
            db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }.getOrDefault(0)
    }

    private fun stringRows(sql: String): MutableList<String> =
        rows(sql) { it.getString(0) }.filterNotNull().filter(String::isNotEmpty).toMutableList()

    private fun <T> rows(sql: String, args: Array<String>? = null, mapper: (Cursor) -> T): MutableList<T> {
        val db = database?.takeIf { isAvailable() } ?: return mutableListOf()
        return runCatching {
            db.rawQuery(sql, args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(mapper(cursor))
                }.toMutableList()
            }
        }.onFailure { Log.e(TAG, "曲库查询失败", it) }.getOrDefault(mutableListOf())
    }

    private fun appendSingerFilters(sql: StringBuilder, args: MutableList<String>, area: String?, type: String?) {
        if (!area.isNullOrEmpty() && area != "全部") {
            sql.append("AND area=? ")
            args += area
        }
        if (!type.isNullOrEmpty() && type != "全部") {
            sql.append("AND type=? ")
            args += type
        }
    }

    private fun songKeywordCondition(
        query: String,
        args: MutableList<String>,
        tableAlias: String = "s.",
    ): String = textKeywordCondition(
        query,
        tableAlias,
        listOf("name", "name_cap", "name_full", "name_trim"),
        args,
    )

    private fun textKeywordCondition(
        query: String,
        tableAlias: String,
        columns: List<String>,
        args: MutableList<String>,
    ): String {
        val latinQuery = query.all { it.code < 128 }
        return if (latinQuery) {
            val pattern = query.uppercase(Locale.ROOT).replace("[", "[[]") + "*"
            repeat(columns.size) { args += pattern }
            columns.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "$tableAlias$it GLOB ?"
            }
        } else {
            val pattern = "%$query%"
            repeat(columns.size) { args += pattern }
            columns.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "$tableAlias$it LIKE ?"
            }
        }
    }

    private fun readCdnPath(): String {
        val db = database ?: return DEFAULT_CDN_PATH
        return runCatching {
            db.rawQuery(
                "SELECT cdn_path FROM global_confs WHERE cdn_path IS NOT NULL AND TRIM(cdn_path) != '' LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else DEFAULT_CDN_PATH
            }
        }.getOrDefault(DEFAULT_CDN_PATH).trimEnd('/') + "/"
    }

    private fun resolveSingerImageUrl(image: String?): String? {
        val value = image?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("/")) return value
        val url = if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            cdnPath + Uri.encode(value, "/")
        }
        return if ('?' in url) url else "$url?imageView2/1/w/100/h/100/q/95!/format/webp"
    }

    companion object {
        private const val TAG = "MuseDatabase"
        private const val DEFAULT_CDN_PATH = "https://pub.mcdn.cherryonline.cn/"
        private const val DEFAULT_DB_PATH = "${AppPaths.ROOT_PATH}/database/muse.db"
        const val PAGE_SIZE = 10
        const val VIDEO_ROOT = AppPaths.VIDEO_PATH
        const val CLOUD_SONG_DIR = "cloud-song"
        const val LOCAL_SONG_DIR = "local"

        private const val SONG_SELECT =
            "SELECT s.id, s.name, s.name_cap, s.name_full, s.lang, s.filename, " +
                "s.path, s.accomp, s.hot_score, s.local_hot_score, s.lyrics, " +
                "s.original, s.accomp as accomp_path, s.p_origin, s.p_accomp, " +
                "COALESCE(NULLIF(s.singer_names,''), " +
                "(SELECT group_concat(sg.name,'、') FROM song_singer_relations ssr " +
                "INNER JOIN singers sg ON sg.id=ssr.singer_id WHERE ssr.song_id=s.id), '') AS singer_names, " +
                "s.o_mode_channels_str FROM songs s "
        // Matches the source database's rec/local-hot/hot composite indexes. DESC already puts NULL last.
        private const val RECOMMEND_ORDER =
            "ORDER BY s.rec_score DESC, s.local_hot_score DESC, s.hot_score DESC"

        @JvmStatic
        fun resolveSongFilePath(song: Song?): String {
            if (song == null) return ""
            song.dbPath?.takeIf(String::isNotEmpty)?.let { path ->
                val relative = path.trimStart('/')
                return File(File(AppPaths.root, relative), song.filename.orEmpty()).absolutePath
            }
            return song.filename?.takeIf(String::isNotEmpty)?.let { filename ->
                File(AppPaths.cloudSongsDir, filename).absolutePath
            }.orEmpty()
        }

        @JvmStatic
        fun getStandardLocalPath(filename: String?): String =
            filename?.takeIf(String::isNotEmpty)?.let { "$VIDEO_ROOT/$CLOUD_SONG_DIR/$it" }.orEmpty()

        @JvmStatic
        fun songDirectories(): List<File> = listOf(AppPaths.cloudSongsDir)

        @JvmStatic
        fun defaultDbFile(): File = File(DEFAULT_DB_PATH)

        private fun findDatabaseFile(): File? {
            return File(DEFAULT_DB_PATH).takeIf { it.exists() && it.canRead() }
        }

        private fun pageArgs(limit: Int, offset: Int) = arrayOf(limit.toString(), offset.toString())
    }
}
