package com.local.ktv

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/** Downloads, verifies, decompresses and installs the catalog database atomically. */
object DatabaseBootstrapper {
    private const val DOWNLOAD_RETRIES = 3
    private const val MIN_FREE_MARGIN = 64L * 1024L * 1024L
    val manifestUrl: String get() = BuildConfig.GITEE_DATABASE_MANIFEST_URL
    private val token: String get() = BuildConfig.GITEE_DATABASE_TOKEN

    private val versionFile: File get() = File(MuseDatabase.defaultDbFile().parentFile, "db_version.txt")

    fun fetchRemoteVersion(): String? = runCatching {
        JSONObject(readText(manifestUrl)).getString("version")
    }.getOrNull()

    fun getLocalDbVersion(): String? = runCatching {
        versionFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun saveLocalDbVersion(version: String) {
        runCatching { versionFile.writeText(version.trim()) }
    }

    /** Retries the complete operation. Each individual chunk also has its own retries. */
    fun download(onProgress: (Int) -> Unit): Result<File> {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_RETRIES) { attempt ->
            cleanupTemporaryFiles()
            val result = runCatching { downloadOnce(onProgress) }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            cleanupTemporaryFiles()
            if (isNoSpaceError(lastError)) return Result.failure(readableError(lastError))
            if (attempt + 1 < DOWNLOAD_RETRIES) {
                onProgress(0)
                Thread.sleep(800L * (attempt + 1))
            }
        }
        return Result.failure(readableError(lastError))
    }

    private fun downloadOnce(onProgress: (Int) -> Unit): File {
        val sourceManifestUrl = manifestUrl
        require(sourceManifestUrl.isNotBlank()) { "Gitee database URL is not configured" }
        val manifest = JSONObject(readText(sourceManifestUrl))
        val expectedCompressedSize = manifest.getLong("compressed_size")
        val expectedDecompressedSize = manifest.getLong("original_size")
        val expectedSha256 = manifest.optString("sha256", "")
        val filesJson = manifest.getJSONArray("files")
        require(filesJson.length() > 0) { "database manifest has no files" }

        val target = MuseDatabase.defaultDbFile()
        val temporaryDb = File(target.parentFile, "${target.name}.download")
        val partsDir = File(target.parentFile, "${target.name}.parts")
        check(target.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "无法创建曲库目录：${target.parent}"
        }
        check(partsDir.mkdirs()) { "无法创建曲库临时目录" }

        val chunks = List(filesJson.length()) { index ->
            val item = filesJson.getJSONObject(index)
            Chunk(
                url = resolveUrl(sourceManifestUrl, item.getString("file")),
                size = item.optLong("size", -1L),
                md5 = item.optString("md5", ""),
            )
        }
        val largestChunk = chunks.maxOfOrNull { it.size.coerceAtLeast(0L) } ?: 0L
        val requiredSpace = expectedDecompressedSize + largestChunk + MIN_FREE_MARGIN
        val usableSpace = target.parentFile?.usableSpace ?: 0L
        check(usableSpace >= requiredSpace) {
            "曲库需要 ${formatSize(requiredSpace)} 临时空间，当前曲库分区可用 ${formatSize(usableSpace)}"
        }

        val downloaded = AtomicLong(0L)
        try {
            val streams = object : Enumeration<InputStream> {
                private var index = 0
                override fun hasMoreElements(): Boolean = index < chunks.size

                override fun nextElement(): InputStream {
                    val chunkIndex = index++
                    val chunk = chunks[chunkIndex]
                    val part = File(partsDir, "part-${chunkIndex.toString().padStart(5, '0')}")
                    downloadPartWithRetry(chunk, part) { currentBytes ->
                        val total = downloaded.get() + currentBytes
                        onProgress((total * 80L / expectedCompressedSize.coerceAtLeast(1L)).toInt().coerceIn(0, 80))
                    }
                    downloaded.addAndGet(part.length())
                    return object : FilterInputStream(part.inputStream().buffered(256 * 1024)) {
                        override fun close() {
                            super.close()
                            part.delete()
                        }
                    }
                }
            }

            // The gzip stream spans all chunks. Keeping only the current chunk reduces peak disk use
            // from compressed*2 + database to database + one chunk.
            SequenceInputStream(streams).use { chunkInput ->
                GZIPInputStream(chunkInput, 256 * 1024).use { gzInput ->
                    BufferedOutputStream(FileOutputStream(temporaryDb)).use { dbOutput ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = gzInput.read(buffer)
                            if (count < 0) break
                            dbOutput.write(buffer, 0, count)
                        }
                    }
                }
            }

            check(downloaded.get() == expectedCompressedSize) {
                "压缩包大小错误：${downloaded.get()} != $expectedCompressedSize"
            }
            check(temporaryDb.length() == expectedDecompressedSize) {
                "数据库大小错误：${temporaryDb.length()} != $expectedDecompressedSize"
            }
            onProgress(90)
            if (expectedSha256.isNotEmpty()) {
                check(sha256(temporaryDb).equals(expectedSha256, ignoreCase = true)) { "数据库校验失败" }
            }
        } finally {
            partsDir.deleteRecursively()
        }

        // Preserve the old working database until the replacement is fully verified.
        val backup = File(target.parentFile, "${target.name}.backup")
        backup.delete()
        if (target.exists()) check(target.renameTo(backup)) { "无法备份旧曲库" }
        if (!temporaryDb.renameTo(target)) {
            backup.renameTo(target)
            error("无法安装下载的曲库")
        }
        backup.delete()
        saveLocalDbVersion(manifest.getString("version"))
        onProgress(100)
        return target
    }

    private fun cleanupTemporaryFiles() {
        val target = MuseDatabase.defaultDbFile()
        File(target.parentFile, "${target.name}.gz.download").delete()
        File(target.parentFile, "${target.name}.download").delete()
        File(target.parentFile, "${target.name}.parts").deleteRecursively()
    }

    private fun downloadPartWithRetry(chunk: Chunk, target: File, onBytes: (Long) -> Unit) {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_RETRIES) { attempt ->
            target.delete()
            val result = runCatching {
                val connection = connection(chunk.url)
                try {
                    connection.inputStream.buffered(256 * 1024).use { input ->
                        target.outputStream().buffered(256 * 1024).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var written = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                onBytes(written)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (chunk.size >= 0L) check(target.length() == chunk.size) {
                    "分片大小错误：${target.length()} != ${chunk.size}"
                }
                if (chunk.md5.isNotEmpty()) {
                    check(md5(target).equals(chunk.md5, ignoreCase = true)) { "分片校验失败" }
                }
            }
            if (result.isSuccess) return
            lastError = result.exceptionOrNull()
            target.delete()
            if (attempt + 1 < DOWNLOAD_RETRIES) Thread.sleep(500L * (attempt + 1))
        }
        throw lastError ?: IllegalStateException("分片下载失败")
    }

    private fun readText(url: String): String {
        val connection = connection(url)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MaidongKTV/1.2")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            val code = responseCode
            check(code in 200..299) { "HTTP $code: $url" }
        }

    private fun resolveUrl(manifestUrl: String, value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value
        else URL(URL(manifestUrl), value).toString()

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(file: File): String = digest(file, "SHA-256")
    private fun md5(file: File): String = digest(file, "MD5")
    private fun formatSize(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun isNoSpaceError(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase(Locale.ROOT) }
        .any { "enospc" in it || "no space left" in it }

    private fun readableError(error: Throwable?): Throwable {
        if (isNoSpaceError(error)) {
            val target = MuseDatabase.defaultDbFile()
            return IllegalStateException(
                "曲库分区空间不足（当前可用 ${formatSize(target.parentFile?.usableSpace ?: 0L)}），" +
                    "已清理下载临时文件，请释放空间后重试",
                error,
            )
        }
        return error ?: IllegalStateException("曲库下载失败")
    }

    private data class Chunk(val url: String, val size: Long, val md5: String)
}
