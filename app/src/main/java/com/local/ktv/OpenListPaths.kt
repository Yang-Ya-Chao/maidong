package com.local.ktv

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object OpenListPaths {
    fun server(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse {
            throw IllegalArgumentException("请输入有效的服务地址")
        }
        require(uri.scheme?.lowercase(Locale.ROOT) in listOf("http", "https")
            && !uri.host.isNullOrBlank() && uri.userInfo == null
            && uri.rawQuery == null && uri.rawFragment == null) {
            "服务地址必须为 HTTP 或 HTTPS，且不包含账号、查询参数或片段"
        }
        return uri.toString().trimEnd('/')
    }

    fun normalize(value: String): String {
        require(!value.contains('\\') && !value.contains('\u0000')) { "目录路径无效" }
        val parts = value.trim().split('/').filter(String::isNotEmpty)
        require(parts.none { it == "." || it == ".." }) { "目录不能包含相对路径" }
        return "/" + parts.joinToString("/")
    }

    fun child(parent: String, name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".."
            && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "文件名无效"
        }
        return normalize(parent).trimEnd('/') + "/" + name
    }

    fun within(path: String, root: String): Boolean {
        val base = normalize(root)
        val target = normalize(path)
        return base == "/" || target == base || target.startsWith(base + "/")
    }

    fun parent(path: String, root: String): String {
        require(within(path, root)) { "目录超出设置的范围" }
        return if (normalize(path) == normalize(root)) normalize(root)
        else normalize(path).substringBeforeLast('/').ifEmpty { "/" }
    }

    fun media(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("mp4", "mkv", "avi", "mpg", "mpeg", "ts", "m2ts", "mov", "webm",
            "mp3", "flac", "wav", "m4a", "aac", "ogg")

    fun songId(server: String, path: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((server + "\n" + path).toByteArray(Charsets.UTF_8))
        return "openlist:" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
