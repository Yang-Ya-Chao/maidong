package com.local.ktv

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads the large catalog as repository-friendly chunks and assembles it atomically. */
object DatabaseBootstrapper {
    val manifestUrl: String get() = BuildConfig.GITEE_DATABASE_MANIFEST_URL

    fun download(onProgress: (Int) -> Unit): Result<File> = runCatching {
        val sourceManifestUrl = manifestUrl
        require(sourceManifestUrl.isNotBlank()) { "未配置 Gitee 曲库地址" }
        val manifest = JSONObject(readText(sourceManifestUrl))
        val expectedSize = manifest.getLong("size")
        val expectedSha256 = manifest.getString("sha256")
        val chunks = manifest.getJSONArray("chunks")
        require(chunks.length() > 0) { "database manifest has no chunks" }

        val target = MuseDatabase.defaultDbFile()
        val temporary = File(target.parentFile, "${target.name}.download")
        target.parentFile?.mkdirs()
        temporary.delete()
        var written = 0L
        BufferedOutputStream(FileOutputStream(temporary)).use { output ->
            repeat(chunks.length()) { index ->
                val item = chunks.getJSONObject(index)
                val chunkUrl = resolveUrl(sourceManifestUrl, item.getString("url"))
                open(chunkUrl).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress((written * 100L / expectedSize.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
                    }
                }
            }
        }
        check(temporary.length() == expectedSize) {
            "database size mismatch: ${temporary.length()} != $expectedSize"
        }
        check(sha256(temporary).equals(expectedSha256, ignoreCase = true)) {
            "database checksum mismatch"
        }
        if (target.exists()) check(target.delete()) { "cannot replace old database" }
        check(temporary.renameTo(target)) { "cannot install downloaded database" }
        onProgress(100)
        target
    }.onFailure {
        File(MuseDatabase.defaultDbFile().parentFile, "muse.db.download").delete()
    }

    private fun readText(url: String): String = open(url).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun open(url: String): BufferedInputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MaidongKTV/1.0")
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code: $url" }
        return BufferedInputStream(connection.inputStream)
    }

    private fun resolveUrl(manifestUrl: String, value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value
        else URL(URL(manifestUrl), value).toString()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
