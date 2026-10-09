package com.local.ktv

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Read the retired catalog only once to rescue personal references, never to browse or download. */
object PersonalMediaMigration {
    fun migrate(store: KtvStore, catalog: IgebaCatalog, current: Song?): Song? {
        val legacyFile = File(AppPaths.databaseDir, "muse.db")
        val retired = legacyFile.takeIf { it.isFile }?.let {
            SQLiteDatabase.openDatabase(it.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        val known = (store.orderQueue + store.sangHistory + listOfNotNull(current))
            .associateBy(KtvStore::stableId)
        val ids = linkedSetOf<String>().apply {
            addAll(store.favoriteIds); addAll(store.hiddenSongIds)
            store.playlistSongs.values.forEach(::addAll)
            addAll(known.keys)
        }
        val replacement = linkedMapOf<String, Song>()
        var missing = 0
        try {
            retired?.let(SingerPortraitStore::importLegacy)
            for (id in ids) {
                if (id.startsWith("igeba:song:")) {
                    catalog.songById(id)?.let(PersonalMediaStore::remember)
                    continue
                }
                val original = known[id] ?: PersonalMediaStore.find(id) ?: retired?.let { lookup(it, id) }
                    ?: Song().apply {
                        this.id = id
                        title = "已失效歌曲（$id）"
                        singer = "原曲库记录缺失"
                        remote = true
                    }.also { missing++ }
                val localPath = original.path?.takeIf { File(it).isFile }
                    ?: original.filename?.let { File(AppPaths.cloudSongsDir, it) }?.takeIf { it.isFile }?.absolutePath
                val mapped = if (original.remote || original.dbId != null) catalog.matchLegacySong(original) else null
                val saved = mapped ?: original
                if (localPath != null) saved.path = localPath
                saved.downloadUrl = null
                saved.videoUrl = null
                saved.originalUrl = null
                saved.accompanyUrl = null
                saved.lyricUrl = null
                PersonalMediaStore.remember(saved)
                check(PersonalMediaStore.find(KtvStore.stableId(saved)) != null) { "个人歌曲记录保存失败" }
                replacement[id] = saved
            }
        } finally { retired?.close() }
        fun remap(set: MutableSet<String>) {
            val updated = set.map { replacement[it]?.let(KtvStore::stableId) ?: it }
            set.clear(); set.addAll(updated)
        }
        remap(store.favoriteIds); remap(store.hiddenSongIds)
        store.playlistSongs.values.forEach(::remap)
        fun remapSongs(list: MutableList<Song>) {
            list.indices.forEach { index -> replacement[KtvStore.stableId(list[index])]?.let { list[index] = it } }
        }
        remapSongs(store.orderQueue); remapSongs(store.sangHistory)
        // Save and verify before retiring any app-owned legacy catalog.
        store.save()
        val persisted = JSONObject(store.stateFile().readText())
        fun verify(key: String, expected: Collection<String>, root: JSONObject = persisted) {
            val actual = root.getJSONArray(key)
            check((0 until actual.length()).map(actual::getString) == expected.toList()) { "个人记录保存校验失败" }
        }
        verify("favoriteIds", store.favoriteIds)
        verify("hiddenSongIds", store.hiddenSongIds)
        val playlistState = persisted.getJSONObject("playlistSongs")
        store.playlistSongs.forEach { (name, values) -> verify(name, values, playlistState) }
        if (missing > 0) android.util.Log.w("PersonalMediaMigration", "已独立保留 $missing 条失效个人引用")
        if (legacyFile.isFile && retired != null) {
            check(SQLiteDatabase.deleteDatabase(legacyFile)) { "旧曲库已停用，文件清理失败" }
        }
        return current?.let { replacement[KtvStore.stableId(it)] ?: it }
    }

    private fun lookup(db: SQLiteDatabase, id: String): Song? {
        return db.rawQuery("SELECT * FROM songs WHERE id=? LIMIT 1", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return@use null
            fun text(column: String): String? = c.getColumnIndex(column).takeIf { it >= 0 }?.let(c::getString)
            val singer = text("singer_names")?.takeIf { it.isNotBlank() } ?: runCatching {
                db.rawQuery("SELECT group_concat(sg.name,'、') FROM song_singer_relations r " +
                    "JOIN singers sg ON sg.id=r.singer_id WHERE r.song_id=?", arrayOf(id)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            Song().apply {
                this.id = id; dbId = id; title = text("name"); this.singer = singer
                language = text("lang"); filename = text("filename"); remote = true
                pinyin = text("name_cap"); pinyinFull = text("name_full")
                val relative = text("path")?.trimStart('/')
                path = if (!relative.isNullOrBlank()) File(File(AppPaths.root, relative), filename.orEmpty()).absolutePath
                    else filename?.let { File(AppPaths.cloudSongsDir, it).absolutePath }
            }
        }
    }
}
