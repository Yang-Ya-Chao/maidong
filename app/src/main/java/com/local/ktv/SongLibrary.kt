package com.local.ktv

import org.json.JSONArray
import java.io.File
import java.util.Locale

class SongLibrary {
    @JvmField val rootDir = AppPaths.songsDir
    @JvmField val importDir = AppPaths.importDir
    private val indexFile = AppPaths.catalogFile
    private val localSongs = mutableListOf<Song>()
    private val remoteSongs = mutableListOf<Song>()
    private val cloudSongs = mutableListOf<Song>()
    private val cloudCatalog = OpenListCatalog()
    @Volatile var openListScanError: String? = null

    fun loadCachedOpenList(config: OpenListConfig) {
        openListScanError = null
        val cached = cloudCatalog.resolve(config, cloudCatalog.cached(config), muse)
        synchronized(this) { cloudSongs.clear(); cloudSongs.addAll(cached) }
    }

    fun syncOpenList(config: OpenListConfig) {
        loadCachedOpenList(config)
        openListScanError = null
        if (config.server.isBlank()) return
        runCatching { cloudCatalog.scan(config, muse) }.onSuccess { songs ->
            synchronized(this) { cloudSongs.clear(); cloudSongs.addAll(songs) }
        }.onFailure { openListScanError = "网盘同步失败，已保留上次索引；请检查连接和目录权限" }
    }

    @Synchronized fun rememberOpenList(config: OpenListConfig, song: Song) {
        val saved = Song.fromJson(song.toJson())
        cloudSongs.removeAll { it.openListPath == saved.openListPath && it.openListServer == saved.openListServer }
        cloudSongs.add(saved)
        cloudCatalog.save(config, cloudSongs)
    }
    @JvmField val muse = IgebaCatalog()

    fun ensureDirs() {
        rootDir.mkdirs()
        importDir.mkdirs()
        indexFile.parentFile?.mkdirs()
    }

    fun catalogFile(): File = indexFile

    @Synchronized fun scanLocal(): List<Song> {
        ensureDirs()
        localSongs.clear()
        val files = mutableListOf<File>()
        scanDir(rootDir, files)
        scanDir(importDir, files)
        scanDir(AppPaths.cloudSongsDir, files)
        SongStorage.usbScanError = null
        if (SongStorage.includeUsb) {
            runCatching {
                val usbFiles = SongStorage.usbMedia().mapNotNull { (it as? SongStorage.FileMedia)?.file }
                files += usbFiles
            }.onFailure {
                SongStorage.usbScanError = it.message ?: "U盘歌曲扫描失败"
                android.util.Log.w("SongLibrary", "U盘歌曲扫描失败", it)
            }
        }
        localSongs += LocalSongResolver.resolve(files, muse::songsByFilenames, muse::songsByNumbers)
        localSongs.filter { it.sourceSongNumber.isNullOrBlank() }.forEach { song ->
            val text = "${song.title.orEmpty()} ${song.singer.orEmpty()}"
            song.pinyin = IgebaCatalogBootstrapper.initials(text)
            song.pinyinFull = IgebaCatalogBootstrapper.fullPinyin(text)
        }
        localSongs.sortBy { it.title.orEmpty().lowercase(Locale.ROOT) }
        return localSongs.toList()
    }

    @Synchronized fun loadCachedRemote(): List<Song> {
        remoteSongs.clear()
        runCatching {
            if (indexFile.exists()) {
                val array = JSONArray(indexFile.readText())
                repeat(array.length()) {
                    val song = Song.remote(array.getJSONObject(it))
                    if (!song.sourceSongNumber.isNullOrBlank()) {
                        song.downloadUrl = null; song.videoUrl = null
                        song.originalUrl = null; song.accompanyUrl = null; song.lyricUrl = null
                        remoteSongs += song
                    }
                }
                val cleaned = JSONArray().apply { remoteSongs.forEach { put(it.toJson()) } }
                indexFile.writeText(cleaned.toString(2))
            }
        }
        return remoteSongs.toList()
    }

    @Synchronized fun saveRemote(array: JSONArray) {
        ensureDirs()
        indexFile.writeText(array.toString(2))
        loadCachedRemote()
    }

    @Synchronized fun clearRemoteCache(): Boolean {
        remoteSongs.clear()
        return indexFile.exists() && indexFile.delete()
    }

    @Synchronized fun allSongs(): List<Song> = (localSongs + cloudSongs + remoteSongs).distinctBy { KtvStore.stableId(it) }

    internal fun localCatalogNumbers(): Set<String> =
        allSongs().asSequence().filter { it.hasLocalFile() }
            .mapNotNull { it.sourceSongNumber?.takeIf(String::isNotBlank) }.toSet()

    internal fun search(query: String, offset: Int, limit: Int, language: String? = null,
                        singerOnly: Boolean = false, localFirst: Boolean = false): SongSearch.Page {
        val snapshot = synchronized(this) { (localSongs + cloudSongs).distinctBy { KtvStore.stableId(it) } }
        val available = muse.isAvailable()
        // 已按目录编号识别的文件由目录结果返回，避免重复；保留其本地播放路径。
        val imported = snapshot.filter {
            (!available || it.sourceSongNumber.isNullOrBlank()) &&
                SongSearch.matches(it, query, language, singerOnly)
        }
        val count = if (!available) 0 else if (singerOnly) muse.searchSongsBySingerKeywordCount(query)
            else muse.searchSongCount(query, language)
        val localByNumber = snapshot.filter { !it.sourceSongNumber.isNullOrBlank() }
            .groupBy { it.sourceSongNumber }.mapValues { (_, copies) ->
                copies.firstOrNull { it.hasLocalFile() } ?: copies.first()
            }
        fun withLocalPaths(songs: List<Song>): List<Song> {
            songs.forEach { song -> localByNumber[song.sourceSongNumber]?.let {
                song.path = it.path
                song.openListServer = it.openListServer
                song.openListPath = it.openListPath
            } }
            return songs
        }
        if (localFirst) {
            val numbers = snapshot.asSequence().filter { it.hasLocalFile() }
                .mapNotNull { it.sourceSongNumber?.takeIf(String::isNotBlank) }.toSet()
            val localCatalog = if (!available || numbers.isEmpty()) emptyList() else
                withLocalPaths(muse.phoneSearchSongs(query, singerOnly, 0, numbers.size, numbers, true))
            return SongSearch.localFirstPage(imported, localCatalog, count - localCatalog.size, offset, limit,
                { it.hasLocalFile() }) { from, size ->
                withLocalPaths(muse.phoneSearchSongs(query, singerOnly, from, size, numbers, false))
            }
        }
        return SongSearch.page(imported, count, offset, limit) { from, size ->
            withLocalPaths(if (singerOnly) muse.searchSongsBySingerKeyword(query, from, size)
                else muse.searchSongs(query, from, size, language))
        }
    }

    fun categories(): List<String> = linkedSetOf("全部").apply {
        allSongs().forEach { add(it.category?.takeIf(String::isNotEmpty) ?: "其他") }
    }.toList()

    fun languages(): List<String> = linkedSetOf("全部").apply {
        allSongs().forEach { add(it.language?.takeIf(String::isNotEmpty) ?: "其他") }
    }.toList()

    fun singers(): List<String> = linkedSetOf<String>().apply {
        allSongs().forEach { add(it.singer?.takeIf(String::isNotEmpty) ?: "未知歌手") }
    }.toList()

    fun targetFor(song: Song): File {
        val clean = "${song.singer}-${song.title}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val raw = song.downloadUrl.orEmpty()
        val dot = raw.lastIndexOf('.')
        val query = raw.indexOf('?', dot.coerceAtLeast(0))
        val candidate = if (dot >= 0) raw.substring(dot, if (query > dot) query else raw.length) else ".mp4"
        val extension = candidate.takeIf { it.length <= 8 && '/' !in it } ?: ".mp4"
        return File(rootDir, clean + extension)
    }

    private fun scanDir(dir: File, files: MutableList<File>, visited: MutableSet<String> = HashSet()) {
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonical)) return
        dir.listFiles().orEmpty().forEach { file ->
            when {
                file.isDirectory -> scanDir(file, files, visited)
                isMedia(file.name) -> files += file
            }
        }
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return OpenListPaths.media(lower)
    }
}
