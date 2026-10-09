package com.local.ktv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 目录更新只读取公开接口，失败时保留上一份 i歌霸快照。 */
object IgebaCatalogBootstrapper {
    val manifestUrl: String get() = IgebaApiClient.CLOUD_URL
    private const val SIZE = 1000
    private val chars by lazy { JSONObject(CatalogAssets.pinyinChars()) }

    fun fetchRemoteVersion(): String? = runCatching {
        val r = IgebaApiClient.songs(1, 1)
        r.getJSONArray("List").getJSONObject(0).getString("SongNumber") + ":" +
            r.getJSONObject("Page").getString("RecordCount")
    }.getOrNull()
    fun getLocalDbVersion(): String? {
        CatalogAssets.install()
        return metadata(AppPaths.databaseFile, "remote_version")
    }
    private fun metadata(file: File, key: String): String? {
        if (!file.isFile) return null
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } finally { db.close() }
    }

    fun download(onProgress: (Int) -> Unit): Result<File> = runCatching {
        if (!AppPaths.databaseFile.isFile) {
            CatalogAssets.install(); onProgress(100); return@runCatching AppPaths.databaseFile
        }
        val version = fetchRemoteVersion() ?: error("无法连接新歌源目录")
        val target = AppPaths.databaseFile
        val temporary = File(target.parentFile, "igeba_catalog.sync")
        temporary.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(temporary, null)
        try {
            schema(db)
            val singerNames = linkedMapOf<String, String>()
            db.beginTransaction()
            try {
                pageAll({ page -> IgebaApiClient.songs(page, SIZE) }) { row, index, page, pages ->
                    putSong(db, row, index); onProgress((page * 55 / pages).coerceIn(0,55))
                }
                pageAll({ page -> IgebaApiClient.packages(1, page, SIZE) }) { row, index, _, _ ->
                    val name = row.optString("SingerName")
                    val mark = row.optString("Mark")
                    val number = row.getString("SingerId")
                    singerNames[name] = number
                    insert(db, "source_singers", ContentValues().apply {
                        put("number", number); put("name", name)
                        put("region", listOf("大陆","港台","日韩","欧美").firstOrNull { it in mark } ?: "其他")
                        put("type", when { "男" in mark -> "男"; "女" in mark -> "女"; else -> "组合" })
                        put("pinyin", initials(name)); put("pinyin_full", fullPinyin(name))
                        put("image_url", row.optString("MidPicture").ifBlank { row.optString("SmallPicture") })
                        put("sort_no", index)
                    })
                }
                onProgress(60)
                val packages = mutableListOf<Pair<Int, JSONObject>>()
                for (type in listOf(3,4)) pageAll({ p -> IgebaApiClient.packages(type, p, SIZE) }) { row, _, _, _ -> packages += type to row }
                packages.forEachIndexed { index, (type, row) ->
                    val number = row.getString(if (type == 3) "PlayId" else "RankId")
                    var count = 0
                    pageAll({ p -> IgebaApiClient.songs(p, SIZE, JSONObject().put("Package",
                        JSONObject().put("Type",type).put("Id",number))) }) { song, order, _, _ ->
                        putSong(db, song, 1_000_000 + index * SIZE + order)
                        insert(db, "source_playlist_songs", ContentValues().apply {
                            put("playlist_number",number); put("package_type",type)
                            put("song_number",song.getString("SongNumber")); put("sort_no",order)
                        })
                        count++
                    }
                    insert(db,"source_playlists",ContentValues().apply {
                        put("number",number); put("package_type",type)
                        put("name",row.optString(if(type==3) "PlayTitle" else "RankTitle"))
                        put("picture",row.optString("Picture")); put("description",row.optString("Description"))
                        put("sort_no",index); put("song_count",count)
                    })
                    onProgress(60 + (index + 1) * 30 / packages.size.coerceAtLeast(1))
                }
                db.rawQuery("SELECT number,singer FROM source_songs",null).use { c ->
                    while(c.moveToNext()) {
                        val name=c.getString(1)
                        (listOf(name) + name.split(Regex("[/、&]+"))).distinct().forEach { part ->
                            singerNames[part.trim()]?.let { id -> insert(db,"source_singer_songs",ContentValues().apply {
                                put("singer_number",id); put("song_number",c.getString(0))
                            }) }
                        }
                    }
                }
                insert(db,"meta",ContentValues().apply { put("key","provider"); put("value","igeba") })
                insert(db,"meta",ContentValues().apply { put("key","schema_version"); put("value","1") })
                insert(db,"meta",ContentValues().apply { put("key","remote_version"); put("value",version) })
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.rawQuery("PRAGMA integrity_check",null).use { check(it.moveToFirst() && it.getString(0)=="ok") { "新目录校验失败" } }
            onProgress(95)
        } catch (error: Throwable) {
            db.close(); temporary.delete(); throw error
        } finally { if(db.isOpen) db.close() }
        val backup=File(target.parentFile,"igeba_catalog.previous")
        backup.delete()
        check(target.renameTo(backup)) { "无法暂存上一份新目录" }
        if(!temporary.renameTo(target)) { backup.renameTo(target); error("无法安装新目录") }
        backup.delete(); onProgress(100); target
    }

    private fun pageAll(request: (Int)->JSONObject, consume: (JSONObject,Int,Int,Int)->Unit) {
        var page=1; var pages=1
        do {
            val result=request(page)
            pages=result.optJSONObject("Page")?.optInt("PageCount",1)?.coerceAtLeast(1) ?: 1
            val list=result.getJSONArray("List")
            repeat(list.length()) { consume(list.getJSONObject(it),(page-1)*SIZE+it,page,pages) }
            page++
        } while(page<=pages)
    }
    private fun insert(db: SQLiteDatabase, table: String, values: ContentValues) {
        db.insertWithOnConflict(table,null,values,SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun fullPinyin(name: String): String = name.map { chars.optString(it.toString(),it.toString()) }.joinToString("").uppercase(Locale.ROOT)
    private fun initials(name: String): String = name.map { chars.optString(it.toString(),it.toString()).take(1) }.joinToString("").uppercase(Locale.ROOT)
    private fun putSong(db: SQLiteDatabase, row: JSONObject, order: Int) {
        val name=row.optString("SongName")
        insert(db,"source_songs",ContentValues().apply {
            put("number",row.getString("SongNumber")); put("name",name); put("singer",row.optString("SingerName"))
            put("language",row.optString("LanguageName")); put("filename",row.optString("FileName"))
            put("version",row.optString("Version")); put("duration",row.optInt("Duration"))
            put("voice_channel",row.optInt("VoiceChannel")); put("pinyin",initials(name)); put("pinyin_full",fullPinyin(name))
            put("name_len",name.count { it.isLetterOrDigit() }); put("sort_no",order); put("album",row.optString("Album"))
        })
    }
    private fun schema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE source_songs(number TEXT PRIMARY KEY,name TEXT NOT NULL,singer TEXT NOT NULL,language TEXT NOT NULL,filename TEXT NOT NULL,version TEXT NOT NULL,duration INTEGER NOT NULL,voice_channel INTEGER NOT NULL,pinyin TEXT NOT NULL,pinyin_full TEXT NOT NULL,name_len INTEGER NOT NULL,sort_no INTEGER NOT NULL,album TEXT NOT NULL)",
            "CREATE TABLE source_singers(number TEXT PRIMARY KEY,name TEXT NOT NULL,region TEXT NOT NULL,type TEXT NOT NULL,pinyin TEXT NOT NULL,pinyin_full TEXT NOT NULL,image_url TEXT NOT NULL,sort_no INTEGER NOT NULL)",
            "CREATE TABLE source_playlists(number TEXT NOT NULL,package_type INTEGER NOT NULL,name TEXT NOT NULL,picture TEXT NOT NULL,description TEXT NOT NULL,sort_no INTEGER NOT NULL,song_count INTEGER NOT NULL,PRIMARY KEY(number,package_type))",
            "CREATE TABLE source_playlist_songs(playlist_number TEXT NOT NULL,package_type INTEGER NOT NULL,song_number TEXT NOT NULL,sort_no INTEGER NOT NULL,PRIMARY KEY(playlist_number,package_type,song_number))",
            "CREATE TABLE source_singer_songs(singer_number TEXT NOT NULL,song_number TEXT NOT NULL,PRIMARY KEY(singer_number,song_number))",
            "CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
            "CREATE INDEX song_name ON source_songs(name)",
            "CREATE INDEX singer_name ON source_singers(name)",
            "CREATE INDEX singer_song ON source_singer_songs(song_number)",
            "CREATE INDEX song_order ON source_songs(sort_no)",
            "CREATE INDEX song_language ON source_songs(language,sort_no)",
            "CREATE INDEX song_length ON source_songs(name_len,sort_no)",
            "CREATE INDEX song_filename ON source_songs(filename)",
            "CREATE INDEX playlist_song ON source_playlist_songs(song_number,package_type)"
        ).forEach(db::execSQL)
    }
}
