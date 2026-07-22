package com.local.ktv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/**
 * 在隐藏 WebView 中运行可热更新的歌曲接口脚本。
 *
 * JS 决定接口地址、参数、签名和响应解析；Android 只提供 HTTP 与 RSA 这两个
 * WebView 中不可靠的底层能力。这样接口变化时只需更新 Gitee 上的 ktv_api.js。
 */
class KtvJsBridge(context: Context) {
    companion object {
        private const val TAG = "KtvJsBridge"
        private const val JS_FILE = "mobile/ktv_api.js"
        private const val CACHE_FILE = "ktv_api_cache.js"
        private const val REMOTE_JS_URL =
            "https://gitee.com/yangyachao-X/maidong-ktv/raw/master/app/src/main/assets/mobile/ktv_api.js"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val JS_REQUEST_TIMEOUT_MS = 180_000
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, (String?) -> Unit>()

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = false
    @Volatile private var destroyed = false
    private var loadedFromCache = false
    private var assetRetryAttempted = false

    /** 必须最终在主线程创建 WebView；可从任意线程调用。 */
    fun init(callback: (Boolean) -> Unit) {
        mainHandler.post {
            if (destroyed) {
                callback(false)
                return@post
            }
            try {
                val cached = File(appContext.filesDir, CACHE_FILE)
                val cachedScript = cached.takeIf(::isValidScript)?.readText(Charsets.UTF_8)
                loadedFromCache = cachedScript != null
                createWebView(cachedScript ?: readAssetScript(), callback)
                updateCache(cached)
            } catch (error: Throwable) {
                Log.e(TAG, "WebView init failed", error)
                callback(false)
            }
        }
    }

    private fun createWebView(script: String, callback: (Boolean) -> Unit) {
        webView?.destroy()
        ready = false
        webView = WebView(appContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(NativeBridge(), "android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("typeof window.KtvApi === 'function'") { result ->
                        if (result == "true") {
                            ready = true
                            Log.i(TAG, "JS runtime ready (${if (loadedFromCache) "cache" else "asset"})")
                            callback(true)
                        } else if (loadedFromCache && !assetRetryAttempted) {
                            Log.w(TAG, "Cached JS failed to load; falling back to packaged asset")
                            assetRetryAttempted = true
                            loadedFromCache = false
                            createWebView(readAssetScript(), callback)
                        } else {
                            Log.e(TAG, "KtvApi was not exported by JS")
                            callback(false)
                        }
                    }
                }
            }
            loadDataWithBaseURL("https://localhost/", wrapHtml(script), "text/html", "UTF-8", null)
        }
    }

    fun getSongUrl(
        musicNo: String,
        resolution: String,
        h265: Boolean,
        callback: (String?) -> Unit,
    ) {
        if (!ready || destroyed) {
            callback(null)
            return
        }
        val requestId = UUID.randomUUID().toString()
        callbacks[requestId] = callback
        val script = """
            (function() {
              var requestId = ${JSONObject.quote(requestId)};
              var completed = false;
              function finish(url) {
                if (completed) return;
                completed = true;
                clearTimeout(timer);
                android.onUrl(requestId, url || '');
              }
              var timer = setTimeout(function() { finish(''); }, $JS_REQUEST_TIMEOUT_MS);
              Promise.resolve().then(async function() {
                if (!window.api) window.api = new window.KtvApi({debug: true});
                if (!window._apiInitPromise) {
                  window._apiInitPromise = window.api.init().then(function(ok) {
                    if (!ok) throw new Error('API initialization failed');
                    return true;
                  }).catch(function(error) {
                    window._apiInitPromise = null;
                    throw error;
                  });
                }
                await window._apiInitPromise;
                return window.api.getSongUrl(
                  ${JSONObject.quote(musicNo)}, ${JSONObject.quote(resolution)}, ${if (h265) "true" else "false"}
                );
              }).then(finish).catch(function(error) {
                android.log('getSongUrl failed: ' + (error && error.message ? error.message : error));
                finish('');
              });
            })();
        """.trimIndent()
        mainHandler.post {
            if (!ready) complete(requestId, null)
            else webView?.evaluateJavascript(script, null) ?: complete(requestId, null)
        }
    }

    private fun complete(requestId: String, url: String?) {
        callbacks.remove(requestId)?.invoke(url?.takeIf { it.startsWith("http://") || it.startsWith("https://") })
    }

    private fun readAssetScript(): String =
        appContext.assets.open(JS_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun isValidScript(file: File): Boolean =
        runCatching {
            file.isFile && file.length() in 1_001..500_000 &&
                file.readText(Charsets.UTF_8).let {
                    it.contains("KtvApi") && it.contains("KTV_BRIDGE_API: 3")
                }
        }.getOrDefault(false)

    /** 下载成功后原子替换缓存；新脚本在下次启动时生效。 */
    private fun updateCache(cacheFile: File) {
        Thread({
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            try {
                val connection = (URL(REMOTE_JS_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "KTV-Updater/2.0")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("HTTP ${connection.responseCode}")
                    }
                    val declaredLength = connection.contentLengthLong
                    require(declaredLength < 0 || declaredLength <= MAX_RESPONSE_BYTES) { "JS file is too large" }
                    val bytes = connection.inputStream.use { it.readBytes() }
                    require(bytes.size <= MAX_RESPONSE_BYTES) { "JS file is too large" }
                    val script = String(bytes, StandardCharsets.UTF_8)
                    require(
                        script.length > 1000 && script.contains("KtvApi") &&
                            script.contains("KTV_BRIDGE_API: 3"),
                    ) { "Invalid or incompatible JS file" }
                    tempFile.writeText(script, Charsets.UTF_8)
                    if (!tempFile.renameTo(cacheFile)) {
                        cacheFile.writeText(script, Charsets.UTF_8)
                        tempFile.delete()
                    }
                    Log.i(TAG, "JS cache updated from Gitee (${script.length} chars)")
                    // 启动后刚拉到的新版本直接生效；若已有请求在执行，则留到下次启动。
                    mainHandler.post {
                        if (ready && callbacks.isEmpty() && !destroyed) {
                            loadedFromCache = true
                            createWebView(script) { ok -> Log.i(TAG, "Hot-reloaded JS: $ok") }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Throwable) {
                tempFile.delete()
                Log.w(TAG, "JS update failed: ${error.message}")
            }
        }, "ktv-js-updater").start()
    }

    private fun wrapHtml(script: String): String {
        val escaped = script.replace("</script", "<\\/script", ignoreCase = true)
        return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>$escaped</script></body></html>"
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun log(message: String) = Log.d(TAG, message)

        @JavascriptInterface
        fun onUrl(requestId: String, url: String) {
            Log.d(TAG, "URL result ${requestId.take(8)}: ${url.take(100)}")
            complete(requestId, url)
        }

        @JavascriptInterface
        fun rsaEncryptPkcs1(publicKeyBase64: String, plaintext: String): String =
            runCatching {
                val keyBytes = Base64.decode(publicKeyBase64.replace("\\s".toRegex(), ""), Base64.DEFAULT)
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }.onFailure { Log.e(TAG, "RSA encryption failed", it) }.getOrDefault("")

        @JavascriptInterface
        fun httpGet(url: String, headersJson: String, timeoutMs: Int): String =
            request("GET", url, null, headersJson, timeoutMs)

        @JavascriptInterface
        fun httpPost(url: String, body: String, headersJson: String, timeoutMs: Int): String =
            request("POST", url, body, headersJson, timeoutMs)

        private fun request(method: String, url: String, body: String?, headersJson: String, timeoutMs: Int): String {
            var connection: HttpURLConnection? = null
            return try {
                require(url.startsWith("http://") || url.startsWith("https://")) { "Unsupported URL" }
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.readTimeout = timeoutMs.coerceIn(1_000, 30_000)
                connection.instanceFollowRedirects = true
                val headers = JSONObject(headersJson.ifBlank { "{}" })
                headers.keys().forEach { name -> connection.setRequestProperty(name, headers.optString(name)) }
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                JSONObject().put("status", status).put("body", responseBody).toString()
            } catch (error: Throwable) {
                Log.w(TAG, "$method request failed: ${error.message}")
                JSONObject().put("status", 0).put("body", "").put("error", error.message.orEmpty()).toString()
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun destroy() {
        destroyed = true
        ready = false
        callbacks.keys.toList().forEach { complete(it, null) }
        mainHandler.post {
            webView?.removeJavascriptInterface("android")
            webView?.destroy()
            webView = null
        }
    }
}
