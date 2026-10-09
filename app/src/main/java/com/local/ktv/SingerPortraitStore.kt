package com.local.ktv

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** 旧数据仅保留这一张歌星名称到图片地址的独立表。 */
object SingerPortraitStore {
    private var database: SQLiteDatabase? = null
    private fun normalize(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .replace(Regex("[\\s·•・、,/\\\\&]+"), "").lowercase(Locale.ROOT)
    @Synchronized fun importLegacy(legacy: SQLiteDatabase) {
        database?.close()
        database = null
        val target = SQLiteDatabase.openDatabase(AppPaths.singerPortraitFile.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE)
        try {
            val cdn = runCatching {
                legacy.rawQuery("SELECT cdn_path FROM global_confs WHERE cdn_path!='' LIMIT 1", null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: "https://pub.mcdn.cherryonline.cn/"
            target.beginTransaction()
            try {
                legacy.rawQuery("SELECT name,image FROM singers WHERE name!='' AND image!=''", null).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0)
                        val image = c.getString(1)
                        val url = when {
                            image.startsWith("http://") || image.startsWith("https://") -> image
                            File(image).isFile -> image
                            else -> cdn.trimEnd('/') + "/" + android.net.Uri.encode(image.trimStart('/'), "/")
                        }
                        val stored = if (url.startsWith("http") && '?' !in url)
                            "$url?imageView2/1/w/100/h/100/q/95!/format/webp" else url
                        check(target.insertWithOnConflict("singer_portraits", null,
                            android.content.ContentValues().apply {
                                put("name_key", normalize(name)); put("name", name); put("image_url", stored)
                            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "歌星图片迁移失败" }
                    }
                }
                target.setTransactionSuccessful()
            } finally { target.endTransaction() }
        } finally { target.close() }
    }
    @Synchronized fun imageFor(name: String): String? = runCatching {
        if (database?.isOpen != true) {
            val file = AppPaths.singerPortraitFile
            if (!file.isFile) return@runCatching null
            database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        database!!.rawQuery("SELECT image_url FROM singer_portraits WHERE name_key=?",
            arrayOf(normalize(name))).use { c ->
            if (!c.moveToFirst()) null else c.getString(0).takeIf {
                it.startsWith("http://") || it.startsWith("https://") || File(it).isFile
            }
        }
    }.getOrNull()
}
