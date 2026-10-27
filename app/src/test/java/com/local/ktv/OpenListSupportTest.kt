package com.local.ktv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class OpenListSupportTest {
    @Test fun pathsKeepChineseNamesAndRespectRootBoundary() {
        assertEquals("/KTV/周杰伦 - 晴天 #1.MKV", OpenListPaths.child("/KTV/", "周杰伦 - 晴天 #1.MKV"))
        assertTrue(OpenListPaths.media("晴天.MKV"))
        assertFalse(OpenListPaths.media("说明.txt"))
        assertFalse(OpenListPaths.within("/KTV-other/歌.mp4", "/KTV"))
        assertEquals("/KTV", OpenListPaths.parent("/KTV/国语", "/KTV"))
        assertEquals("/KTV", OpenListPaths.parent("/KTV", "/KTV"))
        assertThrows(IllegalArgumentException::class.java) { OpenListPaths.child("/KTV", "../secret") }
        assertThrows(IllegalArgumentException::class.java) { OpenListPaths.normalize("/KTV/../secret") }
        assertThrows(IllegalArgumentException::class.java) { OpenListPaths.server("https://user:pass@example.com") }
        assertThrows(IllegalArgumentException::class.java) { OpenListPaths.server("https://example.com?token=test") }
    }

    @Test fun sameFileNameInDifferentFoldersOrServersRemainsDistinct() {
        val first = OpenListPaths.songId("https://a.example", "/one/歌.mp4")
        assertNotEquals(first, OpenListPaths.songId("https://a.example", "/two/歌.mp4"))
        assertNotEquals(first, OpenListPaths.songId("https://b.example", "/one/歌.mp4"))
        val a = song("/one/歌.mp4")
        val b = song("/two/歌.mp4")
        assertNotEquals(a, b)
        val restored = Song.fromJson(a.toJson())
        assertEquals(a, restored)
        assertTrue(restored.isOpenList())
        assertEquals("/one/歌.mp4", restored.openListPath)
        assertFalse(a.toJson().has("token"))
        assertEquals("", restored.getDownloadUrl())
    }

    @Test fun realHttpRequestsPreserveAuthorizationPasswordPaginationAndFreshUrls() {
        val gets = AtomicInteger()
        val server = LocalServer { request ->
            assertEquals("POST", request.method)
            assertEquals("test-token", request.headers["authorization"])
            val body = JSONObject(request.body)
            if (request.path == "/api/fs/list") {
                assertEquals("/KTV/国语", body.getString("path"))
                assertEquals("目录密码", body.getString("password"))
                assertEquals(2, body.getInt("page"))
                assertEquals(100, body.getInt("per_page"))
                assertFalse(body.getBoolean("refresh"))
                Response(body = """{"code":200,"data":{"total":101,"content":[{"name":"歌.mp4","is_dir":false,"size":123}]}}""")
            } else {
                assertEquals("/api/fs/get", request.path)
                assertEquals("/KTV/国语/歌.mp4", body.getString("path"))
                Response(body = """{"code":200,"data":{"is_dir":false,"raw_url":"/p/song.mp4?sign=${gets.incrementAndGet()}"}}""")
            }
        }
        try {
            val base = server.url
            val client = OpenListClient(OpenListConfig(base, "test-token", "/KTV", "目录密码"))
            val page = client.list("/KTV/国语", 2)
            assertEquals(101, page.total)
            assertEquals("歌.mp4", page.entries.single().name)
            assertEquals("$base/p/song.mp4?sign=1", client.resolve("/KTV/国语/歌.mp4"))
            assertEquals("$base/p/song.mp4?sign=2", client.resolve("/KTV/国语/歌.mp4"))
        } finally { server.close() }
    }

    @Test fun applicationErrorsAndRedirectsAreRejectedWithoutLeakingServerMessages() {
        val redirected = AtomicInteger()
        val server = LocalServer { request ->
            when (request.path) {
                "/api/fs/list" -> Response(body = """{"code":403,"message":"secret-token","data":null}""")
                "/api/fs/get" -> Response(status = 302, location = "/leak")
                else -> { redirected.incrementAndGet(); Response() }
            }
        }
        try {
            val client = OpenListClient(OpenListConfig(server.url, "test-token"))
            val denied = assertThrows(IllegalStateException::class.java) { client.list("/", 1) }
            assertFalse(denied.message.orEmpty().contains("secret-token"))
            assertThrows(IllegalStateException::class.java) { client.resolve("/song.mp4") }
            assertEquals(0, redirected.get())
        } finally { server.close() }
    }

    @Test fun streamUploadEncodesChinesePathAndVerifiesSavedLength() {
        val uploads = AtomicInteger()
        val content = "测试视频".repeat(100)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val target = "/KTV/歌手-测试 #1.mp4"
        val server = LocalServer { request ->
            if (request.path == "/api/fs/put") {
                assertEquals("PUT", request.method)
                assertEquals(target, java.net.URI(request.headers["file-path"]).path)
                assertEquals("false", request.headers["as-task"])
                assertEquals("false", request.headers["overwrite"])
                assertEquals("test-token", request.headers["authorization"])
                assertEquals(content, request.body)
                uploads.incrementAndGet()
                Response(body = """{"code":200,"data":null}""")
            } else {
                assertEquals(target, JSONObject(request.body).getString("path"))
                Response(body = """{"code":200,"data":{"is_dir":false,"size":${bytes.size}}}""")
            }
        }
        try {
            val client = OpenListClient(OpenListConfig(server.url, "test-token", "/KTV"))
            client.upload(target, bytes.size.toLong(), bytes.inputStream())
            assertEquals(1, uploads.get())
            assertThrows(IllegalArgumentException::class.java) {
                client.upload("/other/歌.mp4", bytes.size.toLong(), bytes.inputStream())
            }
            assertEquals(1, uploads.get())
        } finally { server.close() }
    }

    @Test fun uploadDenialAndLengthMismatchNeverReportSuccess() {
        var denied = true
        val server = LocalServer { request ->
            if (request.path == "/api/fs/put") {
                if (denied) Response(body = """{"code":403,"message":"secret","data":null}""")
                else Response(body = """{"code":200,"data":null}""")
            } else Response(body = """{"code":200,"data":{"is_dir":false,"size":999}}""")
        }
        try {
            val client = OpenListClient(OpenListConfig(server.url, "test-token", "/KTV"))
            val error = assertThrows(IllegalStateException::class.java) {
                client.upload("/KTV/歌.mp4", 100, ByteArray(100).inputStream())
            }
            assertFalse(error.message.orEmpty().contains("secret"))
            denied = false
            assertThrows(IllegalStateException::class.java) {
                client.upload("/KTV/歌.mp4", 100, ByteArray(100).inputStream())
            }
        } finally { server.close() }
    }

    @Test fun cloudIndexSurvivesRestartButIsIsolatedByServerAndRoot() {
        val file = java.io.File.createTempFile("openlist-index", ".json")
        try {
            val config = OpenListConfig("https://a.example", "secret", "/one", "password")
            val entry = song("/one/歌.mp4").apply { downloadUrl = "https://temporary.example/?sign=secret" }
            OpenListCatalog(file).save(config, listOf(entry))
            val restored = OpenListCatalog(file).cached(config).single()
            assertEquals(entry.id, restored.id)
            assertTrue(restored.downloadUrl.isNullOrBlank())
            assertFalse(file.readText().contains("secret"))
            assertTrue(OpenListCatalog(file).cached(config.copy(root = "/two")).isEmpty())
            assertTrue(OpenListCatalog(file).cached(config.copy(server = "https://b.example")).isEmpty())
            assertTrue(SongSearch.matches(restored, "歌"))
            assertTrue(SongSearch.matches(restored, "歌手", singerOnly = true))
        } finally { file.delete() }
    }

    @Test fun remoteDeleteUsesExactDirectoryAndNameAndAcceptsNullData() {
        val removed = AtomicInteger()
        val target = "/KTV/国语/歌手-歌曲 #1.mp4"
        val server = LocalServer { request ->
            assertEquals("POST", request.method)
            assertEquals("test-token", request.headers["authorization"])
            val body = JSONObject(request.body)
            when (request.path) {
                "/api/fs/get" -> {
                    assertEquals(target, body.getString("path"))
                    assertEquals("目录密码", body.getString("password"))
                    Response(body = """{"code":200,"data":{"is_dir":false}}""")
                }
                "/api/fs/remove" -> {
                    assertEquals("/KTV/国语", body.getString("dir"))
                    assertEquals(1, body.getJSONArray("names").length())
                    assertEquals("歌手-歌曲 #1.mp4", body.getJSONArray("names").getString(0))
                    removed.incrementAndGet()
                    Response(body = """{"code":200,"data":null}""")
                }
                else -> error("意外请求")
            }
        }
        try {
            OpenListClient(OpenListConfig(server.url, "test-token", "/KTV", "目录密码")).remove(target)
            assertEquals(1, removed.get())
        } finally { server.close() }
    }

    @Test fun remoteDeleteRejectsRootTraversalOutsidePathsAndDirectories() {
        val requests = AtomicInteger()
        val server = LocalServer { request ->
            requests.incrementAndGet()
            assertEquals("/api/fs/get", request.path)
            Response(body = """{"code":200,"data":{"is_dir":true}}""")
        }
        try {
            val client = OpenListClient(OpenListConfig(server.url, root = "/KTV"))
            listOf("/KTV", "/KTV-other/歌.mp4", "/KTV/../歌.mp4", "/KTV//歌.mp4", "/KTV/说明.txt").forEach {
                assertThrows(IllegalArgumentException::class.java) { client.remove(it) }
            }
            assertEquals(0, requests.get())
            assertThrows(IllegalStateException::class.java) { client.remove("/KTV/目录.mp4") }
            assertEquals(1, requests.get())
        } finally { server.close() }
    }

    @Test fun remoteDeleteFailureDoesNotErasePersistentIndex() {
        val file = java.io.File.createTempFile("openlist-delete", ".json")
        var fail = true
        val server = LocalServer { request ->
            if (request.path == "/api/fs/get") Response(body = """{"code":200,"data":{"is_dir":false}}""")
            else if (fail) Response(body = """{"code":403,"message":"private-token","data":null}""")
            else Response(status = 500)
        }
        try {
            val config = OpenListConfig(server.url, "test-token", "/KTV")
            val entry = song("/KTV/歌.mp4").apply { openListServer = server.url }
            val catalog = OpenListCatalog(file)
            catalog.save(config, listOf(entry))
            repeat(2) {
                val error = assertThrows(IllegalStateException::class.java) {
                    OpenListClient(config).remove(entry.openListPath!!)
                    catalog.remove(config, entry.openListPath!!)
                }
                assertFalse(error.message.orEmpty().contains("private-token"))
                assertEquals(entry.openListPath, OpenListCatalog(file).cached(config).single().openListPath)
                fail = false
            }
        } finally { server.close(); file.delete() }
    }

    @Test fun deletedRemoteIndexStaysRemovedAfterRestartAndKeepsOtherCopies() {
        val file = java.io.File.createTempFile("openlist-delete", ".json")
        try {
            val config = OpenListConfig("https://a.example", root = "/KTV")
            val first = song("/KTV/one/歌.mp4")
            val second = song("/KTV/two/歌.mp4")
            val catalog = OpenListCatalog(file)
            catalog.save(config, listOf(first, second))
            catalog.remove(config, first.openListPath!!)
            assertEquals(second.openListPath, OpenListCatalog(file).cached(config).single().openListPath)
            catalog.remove(config, first.openListPath!!)
            assertEquals(1, catalog.cached(config).size)
        } finally { file.delete() }
    }

    @Test fun oldDeleteDoesNotOverwriteNewServerIndex() {
        val file = java.io.File.createTempFile("openlist-switch", ".json")
        try {
            val old = OpenListConfig("https://a.example", root = "/KTV")
            val current = OpenListConfig("https://b.example", root = "/other")
            val entry = song("/other/歌.mp4").apply { openListServer = current.server }
            val catalog = OpenListCatalog(file)
            catalog.save(current, listOf(entry))
            catalog.remove(old, "/KTV/歌.mp4")
            assertEquals(entry.openListPath, catalog.cached(current).single().openListPath)
        } finally { file.delete() }
    }

    private fun song(path: String) = Song().apply {
        openListServer = "https://a.example"
        openListPath = path
        id = OpenListPaths.songId(openListServer!!, path)
        filename = "歌.mp4"
        title = "歌"
        singer = "歌手"
        remote = true
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Response(val status: Int = 200, val body: String = "", val location: String? = null)

    /** 用标准套接字模拟服务，避免依赖 Android 不包含的 JDK HTTP 模块。 */
    private class LocalServer(private val respond: (Request) -> Response) {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable>()
        val url = "http://127.0.0.1:${socket.localPort}"
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val input = client.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val byte = input.read()
                                if (byte < 0 || byte == 10) break
                                if (byte != 13) append(byte.toChar())
                            }
                        }
                        val first = line().split(' ')
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        val response = try {
                            respond(Request(first[0], first[1], headers, bytes.toString(Charsets.UTF_8)))
                        } catch (error: Throwable) {
                            failure.set(error)
                            Response(status = 500)
                        }
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 ${response.status} Test\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n" +
                            (response.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                        client.getOutputStream().use {
                            it.write(head.toByteArray(Charsets.US_ASCII))
                            it.write(body)
                        }
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) failure.set(error)
                    break
                } catch (error: Throwable) {
                    failure.set(error)
                    break
                }
            }
        }.apply { isDaemon = true; start() }

        fun close() {
            socket.close()
            worker.join(5000)
            failure.get()?.let { throw AssertionError("模拟服务断言失败", it) }
        }
    }
}
