package com.local.ktv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** APK只携带 i歌霸目录和歌星图片索引，首次使用原子安装。 */
object CatalogAssets {
    private lateinit var context: Context
    fun init(value: Context) { context = value.applicationContext }
    @Synchronized fun install() {
        AppPaths.databaseDir.mkdirs()
        installOne("igeba_catalog.db", AppPaths.databaseFile)
        installOne("singer_portraits.db", AppPaths.singerPortraitFile)
    }
    private fun installOne(asset: String, target: File) {
        if (target.isFile) return
        val temporary = File(target.parentFile, target.name + ".install")
        context.assets.open(asset).use { input -> temporary.outputStream().use { input.copyTo(it) } }
        val db = SQLiteDatabase.openDatabase(temporary.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") { "目录资源校验失败" } }
        } finally { db.close() }
        check(temporary.renameTo(target)) { "无法安装目录资源" }
    }
    fun pinyinChars(): String = context.assets.open("pinyin_chars.json").bufferedReader().use { it.readText() }
}
