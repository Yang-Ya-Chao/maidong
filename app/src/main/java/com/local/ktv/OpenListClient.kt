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

    private fun request(action: String, body: JSONObject): JSONObject {
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
            return payload.getJSONObject("data")
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
