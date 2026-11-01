package com.local.ktv

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Loopback OpenList fixture: exercises the production resolver, downloader and storage. */
internal object LocalOpenListValidation {
    fun run(sample: File, record: (String) -> Unit) {
        val context = KtvApplication.getInstance()
        val fixtureFile = File(context.cacheDir, "issue-validation-session.bin")
        val vault = IgebaSessionVault(context, fixtureFile)
        try {
            val data = org.json.JSONObject().put("userId", 123L).put("nickname", "本机测试")
                .put("cookies", org.json.JSONArray())
                .put("credentials", org.json.JSONObject().put("account", "local-fixture-account").put("password", "fixture-only-secret"))
            vault.save(data)
            val encoded = fixtureFile.readBytes().toString(Charsets.ISO_8859_1)
            check(!encoded.contains("fixture-only-secret") && !encoded.contains("local-fixture-account"))
            val restored = checkNotNull(IgebaSessionVault(context, fixtureFile).load())
            check(restored.getJSONObject("credentials").getString("password") == "fixture-only-secret")
            check(restored.getLong("userId") == 123L)
            vault.clear()
            check(IgebaSessionVault(context, fixtureFile).load() == null)
            record("VAULT encrypted-reopen-and-clear pass=true")
        } finally { vault.clear() }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val worker = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread { runCatching { serve(client, base, sample) }; runCatching { client.close() } }.start()
            }
        }.apply { isDaemon = true; start() }
        fun digest(file: File) = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
            hash.digest()
        }
        try {
            val directStart = System.nanoTime()
            var directBytes = 0L
            URL("$base/media/normal").openStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; directBytes += n }
            }
            check(directBytes == sample.length())
            val directMs = (System.nanoTime() - directStart) / 1_000_000
            record("NETWORK direct bytes=$directBytes elapsedMs=$directMs")
            for (mode in listOf("normal", "truncated", "cancel")) {
                val song = Song().apply {
                    id = "openlist:issue-validation-${System.nanoTime()}"
                    title = "本机回归-$mode"; singer = "测试"
                    openListServer = base; openListPath = "/$mode.mkv"; remote = true
                }
                val done = CountDownLatch(1)
                var complete = false
                var failure = ""
                val start = System.nanoTime()
                SongOkDownloadManager.download(song, object : SongOkDownloadManager.DownloadCallback {
                    override fun onDownloadStart(song: Song) = Unit
                    override fun onDownloadProgress(song: Song, progress: Int) {
                        if (mode == "cancel" && progress > 0) SongOkDownloadManager.cancelDownload(song)
                    }
                    override fun onDownloadReadyToPlay(song: Song) = Unit
                    override fun onDownloadComplete(song: Song, localPath: String) { complete = true; done.countDown() }
                    override fun onDownloadFailed(song: Song, error: String) { failure = error; done.countDown() }
                }, source = OpenListConfig(base))
                // Cancellation intentionally has no failure callback; wait until the job drains.
                if (mode == "cancel") {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (SongOkDownloadManager.isDownloading(song) && System.nanoTime() < deadline) Thread.sleep(20)
                    Thread.sleep(200)
                } else check(done.await(30, TimeUnit.SECONDS)) { "下载验证超时：$mode" }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                val passed = if (mode == "normal") complete && File(checkNotNull(song.path)).let {
                    it.length() == sample.length() && digest(it).contentEquals(digest(sample))
                } else !complete && !song.hasLocalFile() && (mode == "cancel" || failure.isNotEmpty())
                record("NETWORK mode=$mode pass=$passed bytes=${sample.length()} elapsedMs=$elapsed")
                check(SongOkDownloadManager.deleteCache(song)) { "测试下载文件清理失败" }
                check(passed) { "网盘验证失败：$mode $failure" }
            }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    private fun serve(client: Socket, base: String, sample: File) {
        client.soTimeout = 10000
        val input = client.getInputStream().buffered()
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == 10) break
                if (c != 13) result.append(c.toChar())
                check(result.length < 8192)
            }
            return result.toString()
        }
        val path = line().split(" ").getOrElse(1) { "/" }
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
        }
        check(length in 0..65536)
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
        val output = client.getOutputStream().buffered(65536)
        if (path == "/api/fs/get") {
            val mode = org.json.JSONObject(String(body, Charsets.UTF_8)).getString("path").substringAfterLast('/').substringBefore('.')
            val json = org.json.JSONObject().put("code", 200).put("data",
                org.json.JSONObject().put("is_dir", false).put("raw_url", "$base/media/$mode")).toString().toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(json)
        } else {
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${sample.length()}\r\nConnection: close\r\n\r\n".toByteArray())
            val truncated = path.endsWith("truncated")
            val slow = path.endsWith("cancel")
            val limit = if (truncated) sample.length() / 2 else sample.length()
            sample.inputStream().use { source ->
                val bytes = ByteArray(32768)
                var sent = 0L
                while (sent < limit) {
                    val n = source.read(bytes, 0, minOf(bytes.size.toLong(), limit - sent).toInt())
                    if (n < 0) break
                    output.write(bytes, 0, n); sent += n
                    if (slow) { output.flush(); Thread.sleep(5) }
                }
            }
        }
        output.flush()
    }
}
