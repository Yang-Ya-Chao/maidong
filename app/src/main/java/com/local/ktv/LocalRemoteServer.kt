package com.local.ktv

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class LocalRemoteServer {
    interface Callback {
        fun statusJson(): String
        fun catalogJson(): String
        fun queueJson(): String
        fun rankJson(): String
        fun filterJson(type: String?, value: String?): String
        fun favoritesJson(): String
        fun search(query: String?): String
        fun order(query: String?): String
        fun command(action: String?, params: Map<String, String>): String
    }

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var callback: Callback? = null
    private val serverPort = 8765

    fun start(callback: Callback) {
        this.callback = callback
        if (running) return
        running = true
        pool.execute {
            runCatching {
                server = ServerSocket(serverPort)
                while (running) {
                    val socket = server?.accept() ?: break
                    pool.execute { handle(socket) }
                }
            }
            running = false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    fun port(): Int = serverPort

    private fun handle(socket: Socket) {
        runCatching {
            socket.use { client ->
                val reader = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                val request = reader.readLine()
                if (request == null || !request.startsWith("GET ")) {
                    write(output, 400, "{\"ok\":false,\"error\":\"bad request\"}")
                    return
                }
                val target = request.split(' ')[1]
                val path = target.substringBefore('?')
                val params = parseQuery(target.substringAfter('?', ""))
                val api = callback
                val body = when (path) {
                    "/", "/mobile" -> mobilePage()
                    "/status" -> api?.statusJson()
                    "/catalog" -> api?.catalogJson()
                    "/queue" -> api?.queueJson()
                    "/rank" -> api?.rankJson()
                    "/filter" -> api?.filterJson(params["type"], params["value"])
                    "/favorites" -> api?.favoritesJson()
                    "/search" -> api?.search(params["q"])
                    "/order" -> api?.order(params["q"])
                    "/cmd" -> api?.command(params["a"], params)
                    else -> null
                } ?: "{\"ok\":false,\"error\":\"not found\"}"
                write(output, 200, body)
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        if (query.isBlank()) return@buildMap
        query.split('&').forEach { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            put(key, value)
        }
    }

    private fun mobilePage(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>本地KTV点歌</title><style>body{background:#17083d;color:white;font-family:sans-serif;margin:0}header,main{padding:14px}input,button{font-size:16px;padding:10px;margin:4px;border:0;border-radius:7px}button{background:#7952c7;color:white}.song{padding:12px;border-bottom:1px solid #ffffff22}</style></head>
        <body><header><b>麦动点歌台</b><br><input id="q" placeholder="歌名 / 歌手 / 拼音"><button onclick="search()">搜索</button><button onclick="queue()">已点</button><button onclick="cmd('next')">切歌</button><button onclick="cmd('pause')">暂停/继续</button><button onclick="cmd('replay')">重唱</button></header><main id="list"></main>
        <script>async function j(u){return await(await fetch(u)).json()}function esc(s){return String(s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}function render(a){list.innerHTML=(a||[]).map(x=>'<div class="song"><b>'+esc(x.title)+'</b><br>'+esc(x.singer)+' <button onclick="order(\''+encodeURIComponent(x.title)+'\')">点歌</button></div>').join('')}async function search(){let r=await j('/search?q='+encodeURIComponent(q.value));render(r.songs)}async function order(v){await j('/order?q='+v)}async function queue(){let r=await j('/queue');render(r.queue)}async function cmd(a){await j('/cmd?a='+a)}search()</script></body></html>
    """.trimIndent()

    private fun write(output: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val type = if (body.startsWith("<!doctype")) "text/html" else "application/json"
        output.write("HTTP/1.1 $status OK\r\nContent-Type: $type; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }
}
