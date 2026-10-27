package com.local.ktv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 网盘索引与登录配置分离；断线时保留上次完整索引，切换服务时不混入旧歌曲。 */
internal class OpenListCatalog(private val cacheFile: File? = null) {
    private val cache get() = cacheFile ?: File(KtvApplication.getInstance().filesDir, "openlist-songs.json")

    @Synchronized fun cached(config: OpenListConfig): List<Song> = runCatching {
        val data = JSONObject(cache.readText())
        if (data.optString("server") != config.server || data.optString("root") != config.root) return emptyList()
        val rows = data.getJSONArray("songs")
        (0 until rows.length()).map { Song.fromJson(rows.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun scan(config: OpenListConfig, catalog: IgebaCatalog): List<Song> {
        if (config.server.isBlank()) return emptyList()
        val client = OpenListClient(config)
        val pending = java.util.ArrayDeque<String>()
        val visited = HashSet<String>()
        val files = linkedMapOf<String, String>()
        pending.add(config.root)
        while (pending.isNotEmpty()) {
            check(visited.size < 10000) { "网盘目录过多，请缩小歌曲目录" }
            val path = pending.removeFirst()
            if (!visited.add(path)) continue
            check(path.split('/').size <= 66) { "网盘目录过深，请缩小歌曲目录" }
            var page = 1
            do {
                val result = client.list(path, page)
                result.entries.forEach { entry ->
                    val child = OpenListPaths.child(path, entry.name)
                    if (entry.directory) pending.add(child)
                    else if (OpenListPaths.media(entry.name)) files[child] = entry.name
                }
                check(files.size <= 100000 && page <= 10000) { "网盘曲库过大，请缩小歌曲目录" }
                val more = page.toLong() * OpenListClient.PAGE_SIZE < result.total
                check(!more || result.entries.isNotEmpty()) { "网盘分页不完整，请重试同步" }
                page++
            } while (more)
        }
        val previous = cached(config).associateBy { it.openListPath }
        val songs = resolve(config, files.map { (path, name) ->
            (previous[path]?.let { Song.fromJson(it.toJson()) } ?: Song.local(path, name)).apply {
                openListPath = path
                filename = name
            }
        }, catalog)
        save(config, songs)
        return songs
    }

    fun resolve(config: OpenListConfig, entries: List<Song>, catalog: IgebaCatalog): List<Song> =
        LocalSongResolver.resolveEntries(entries.map { entry ->
            val path = checkNotNull(entry.openListPath)
            LocalSongResolver.Entry(path, entry.filename ?: path.substringAfterLast('/'), entry)
        }, catalog::songsByFilenames, catalog::songsByNumbers).map { song ->
            val path = checkNotNull(song.path)
            song.id = OpenListPaths.songId(config.server, path)
            song.path = null
            song.originalPath = null; song.accompanyPath = null; song.lyricPath = null
            song.remote = true
            song.openListServer = config.server
            song.openListPath = path
            song.pinyin = IgebaCatalogBootstrapper.initials("${song.title.orEmpty()} ${song.singer.orEmpty()}")
            song.pinyinFull = IgebaCatalogBootstrapper.fullPinyin("${song.title.orEmpty()} ${song.singer.orEmpty()}")
            song
        }

    @Synchronized fun remove(config: OpenListConfig, path: String): List<Song> {
        // 删除请求期间可能已切换服务；不能覆盖新服务的索引。
        if (cache.exists()) {
            val current = JSONObject(cache.readText())
            if (current.optString("server") != config.server || current.optString("root") != config.root) {
                return emptyList()
            }
        }
        val remaining = cached(config).filterNot {
            it.openListServer == config.server && it.openListPath == path
        }
        save(config, remaining)
        return remaining
    }

    @Synchronized fun save(config: OpenListConfig, songs: List<Song>) {
        val rows = JSONArray().apply { songs.forEach { song ->
            put(Song.fromJson(song.toJson()).apply {
                path = null; downloadUrl = null; videoUrl = null
                originalUrl = null; accompanyUrl = null; lyricUrl = null
            }.toJson())
        } }
        val temporary = File(cache.parentFile, cache.name + ".tmp")
        temporary.writeText(JSONObject().put("server", config.server).put("root", config.root)
            .put("songs", rows).toString())
        check(temporary.renameTo(cache)) { "网盘索引保存失败" }
    }
}
