package com.local.ktv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** 收藏、自建歌单引用的歌曲快照独立保存，不依赖远程目录是否仍有该条目。 */
object PersonalMediaStore {
    private var helper: Helper? = null
    private class Helper(context: Context) : SQLiteOpenHelper(context, "personal_media.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE media(stable_id TEXT PRIMARY KEY,song_json TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    @Synchronized fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }
    @Synchronized fun remember(song: Song) {
        val db = helper?.writableDatabase ?: return
        // 临时地址不能作为收藏中的持久播放凭据。
        val json = song.toJson().apply {
            listOf("downloadUrl", "videoUrl", "originalUrl", "accompanyUrl", "lyricUrl").forEach(::remove)
        }
        db.insertWithOnConflict("media", null, ContentValues().apply {
            put("stable_id", KtvStore.stableId(song)); put("song_json", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun find(id: String): Song? = helper?.readableDatabase?.rawQuery(
        "SELECT song_json FROM media WHERE stable_id=?", arrayOf(id))?.use { c ->
        if (c.moveToFirst()) Song.fromJson(JSONObject(c.getString(0))) else null
    }
}
