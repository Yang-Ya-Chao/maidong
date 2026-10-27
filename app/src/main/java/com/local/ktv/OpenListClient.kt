package com.local.ktv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenList 文件接口；仅在后台线程调用，临时播放地址不写入歌曲状态。 */
class OpenListClient(private val config: OpenListConfig) {
    data class Entry(val name: String, val directory: Boolean, val size: Long)
    data class Page(val entries: List<Entry>, val total: Int)

    fun list(path: String, page: Int): Page {
        val data = request("list", JSONObject().put("path", path)
            .put("password", config.password).put("page", page).put("per_page", PAGE_SIZE)
            .put("refresh", false))
        val content = data.optJSONArray("content")
        val entries = (0 until (content?.length() ?: 0)).map { index ->
            val item = content!!.getJSONObject(index)
            Entry(item.getString("name"), item.optBoolean("is_dir"), item.optLong("size"))
        }
        return Page(entries, data.optInt("total", entries.size))
    }

    fun upload(path: String, size: Long, input: java.io.InputStream,
               cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {},
               onConnection: (HttpURLConnection?) -> Unit = {}) {
        require(size > 0) { "歌曲文件为空" }
        require(OpenListPaths.within(path, config.root) && path != config.root) { "保存路径超出歌曲目录" }
        val connection = URL(config.server + "/api/fs/put").openConnection() as HttpURLConnection
        onConnection(connection)
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("File-Path", java.net.URI(null, null, path, null).toASCIIString())
            connection.setRequestProperty("As-Task", "false")
            connection.setRequestProperty("Overwrite", "false")
            if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", config.token)
            var sent = 0L
            connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!cancelled()) { "保存已暂停" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    sent += count
                    check(sent <= size) { "歌曲长度发生变化" }
                    output.write(buffer, 0, count)
                    progress((sent * 100 / size).toInt().coerceAtMost(99))
                }
            }
            check(sent == size) { "歌曲读取不完整" }
            check(connection.responseCode in 200..299) { "网盘上传失败，请检查连接和写入权限" }
            val payload = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(payload.optInt("code") == 200) { "网盘保存失败，请检查写入权限、剩余空间或同名文件" }
            // 同步上传结束后再次验证落盘长度，不能把后台任务接收当作保存成功。
            val saved = request("get", JSONObject().put("path", path).put("password", config.password))
            check(!saved.optBoolean("is_dir") && saved.optLong("size", -1) == size) { "网盘文件长度校验失败" }
        } finally {
            connection.disconnect()
            onConnection(null)
        }
    }

    /** 仅删除配置目录内的一首媒体文件，不允许递归删除目录。 */
    fun remove(path: String) {
        val target = OpenListPaths.normalize(path)
        val root = OpenListPaths.normalize(config.root)
        require(target == path && target != root && OpenListPaths.within(target, root)) {
            "删除路径超出歌曲目录"
        }
        val name = target.substringAfterLast('/')
        require(OpenListPaths.media(name)) { "只能删除歌曲文件" }
        val file = request("get", JSONObject().put("path", target).put("password", config.password))
        check(!file.optBoolean("is_dir", true)) { "不能删除歌曲目录" }
        request("remove", JSONObject().put("dir", OpenListPaths.parent(target, root))
            .put("names", org.json.JSONArray().put(name)), expectData = false)
    }

    fun resolve(path: String): String {
        val data = request("get", JSONObject().put("path", path).put("password", config.password))
        check(!data.optBoolean("is_dir")) { "请选择歌曲文件" }
        val raw = data.optString("raw_url")
        check(raw.isNotBlank()) { "网盘未返回播放地址" }
        val resolved = URL(URL(config.server + "/"), raw)
        check(resolved.protocol in listOf("http", "https") && resolved.userInfo == null) {
            "网盘返回的播放地址无效"
        }
        return resolved.toExternalForm()
    }

    private fun request(action: String, body: JSONObject, expectData: Boolean = true): JSONObject {
        val connection = URL(config.server + "/api/fs/" + action).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            // 禁止携带访问令牌自动跟随到其他地址。
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", config.token)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            check(status in 200..299) {
                when (status) {
                    401, 403 -> "访问被拒绝，请检查令牌、目录密码和访问权限"
                    in 300..399 -> "服务地址发生跳转，请填写最终服务地址"
                    else -> "OpenList 请求失败（HTTP $status）"
                }
            }
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 4 * 1024 * 1024) { "目录响应过大，请缩小目录范围" }
                    text.append(buffer, 0, count)
                }
                JSONObject(text.toString())
            }
            check(payload.optInt("code", -1) == 200) {
                when (payload.optInt("code")) {
                    401, 403 -> "访问被拒绝，请检查令牌、目录密码和访问权限"
                    else -> "OpenList 操作失败，请检查服务与网盘挂载状态"
                }
            }
            return if (expectData) payload.getJSONObject("data") else JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PAGE_SIZE = 100
    }
}

data class OpenListConfig(
    val server: String,
    val token: String = "",
    val root: String = "/",
    val password: String = "",
)
