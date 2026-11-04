package com.local.ktv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Debug-only acceptance against the production server and bundled mobile page. */
class RemoteConnectionTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val report = StringBuilder()
    private var tested = false
    private var destroyed = false
    private fun record(message: String) {
        main.post {
            if (destroyed) return@post
            Log.i("RemoteAcceptance", message)
            report.append(message).append('\n')
            File(getExternalFilesDir(null), "remote-acceptance.txt").writeText(report.toString())
            status.text = report.toString().takeLast(1800)
        }
    }
    private fun request(base: String, path: String): Int {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 5000
        return try {
            val code = connection.responseCode
            if (code == 200) connection.inputStream.use { it.readBytes() }
            code
        } finally { connection.disconnect() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { textSize = 13f }
        web = WebView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, 160))
        setContentView(root)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun report(value: String) { record("WEB " + value) }
        }, "acceptance")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                record("CONSOLE " + message.message())
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (tested) return
                tested = true
                main.postDelayed({
                    if (!destroyed) web.evaluateJavascript(TEST_SCRIPT, null)
                }, 5000)
            }
        }
        record("USER_AGENT " + web.settings.userAgentString)
        Thread {
            try {
                val base = (8765..8775).map { "http://127.0.0.1:" + it }
                    .first { runCatching { request(it, "/api/v1/state") == 200 }.getOrDefault(false) }
                record("PRODUCTION_SERVER " + base)
                val paths = listOf("/mobile", "/mobile/app.js", "/mobile/app.css",
"/res/drawable-nodpi/ktv_logo.png", "/api/v1/state", "/api/v1/queue", "/api/v1/hot?page=1&pageSize=10",
                    "/api/v1/songs?q=%E5%91%A8&type=singer&page=1&pageSize=20")
                paths.forEach { path -> record("HTTP " + path + " status=" + request(base, path)) }
                val workers = Executors.newFixedThreadPool(4)
                val results = (0 until 120).map { index ->
                    workers.submit<Boolean> {
                        Thread.sleep(500)
                        request(base, if (index % 3 == 0) "/api/v1/queue" else "/api/v1/state") == 200
                    }
                }
                val passed = results.count { it.get(15, TimeUnit.SECONDS) }
                workers.shutdown()
                record("SOAK requests=120 passed=" + passed)
                checkAddressUpdate()
                main.post { if (!destroyed) web.loadUrl(base + "/mobile") }
            } catch (failure: Exception) { record("FAIL " + failure.toString()) }
        }.start()
        main.postDelayed({ if (!tested) record("FAIL mobile page did not load") }, 45000)
    }
    private fun checkAddressUpdate() {
        val started = java.util.concurrent.CountDownLatch(1)
        val server = LocalRemoteServer(this)
        val callback = object : LocalRemoteServer.Callback {
            override fun statusJson() = "{}"
            override fun catalogJson() = "{}"
            override fun queueJson() = "{}"
            override fun rankJson() = "{}"
            override fun filterJson(type: String?, value: String?) = "{}"
            override fun favoritesJson() = "{}"
            override fun search(query: String?) = "{}"
            override fun order(query: String?) = "{}"
            override fun command(action: String?, params: Map<String, String>) = "{}"
            override fun handle(method: String, path: String, query: Map<String, String>, body: String): LocalRemoteServer.ApiResponse {
                started.countDown()
                Thread.sleep(800)
                return LocalRemoteServer.ApiResponse(body = "{\"ok\":true}")
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            check(server.start("127.0.0.1", callback))
            val port = server.port()
            val pending = worker.submit<Int> { request("http://127.0.0.1:" + port, "/api/v1/state") }
            check(started.await(5, TimeUnit.SECONDS))
            check(server.start("127.0.0.2", callback))
            check(server.port() == port)
            check(pending.get(5, TimeUnit.SECONDS) == 200)
            record("ADDRESS_CHANGE portStable=true inflightRequest=200 pass=true")
        } finally { server.stop(); worker.shutdownNow() }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
    companion object {
        private val TEST_SCRIPT = """
            (async function() {
              try {
                if (typeof uiState === 'undefined') throw Error('mobile script did not initialize');
                clearTimeout(heartbeatTimer);
                await refresh();
                if (!uiState.connected) throw Error('initial connection failed');
                var savedFetch = window.fetch;
                window.fetch = function() { return Promise.reject(Error('acceptance simulated offline')); };
                try { await api('/api/v1/songs?q=test'); } catch (e) {}
                if (!uiState.connected) throw Error('search timeout incorrectly disconnected');
                await refresh(); await refresh();
                if (!uiState.connected) throw Error('transient failures incorrectly disconnected');
                await refresh();
                if (uiState.connected) throw Error('sustained failure not indicated');
                window.fetch = savedFetch;
                await refresh();
                if (!uiState.connected) throw Error('automatic reconnection failed');
                heartbeat();
                setTimeout(function() { acceptance.report(JSON.stringify({steady30s:uiState.connected,failures:uiState.heartbeatFailures})); }, 30000);
                acceptance.report(JSON.stringify({pass:true, connected:uiState.connected,
                  failures:uiState.heartbeatFailures,title:document.querySelector('#nowTitle').textContent}));
              } catch (e) { acceptance.report(JSON.stringify({pass:false,error:String(e)})); }
            })();
        """
    }
}
