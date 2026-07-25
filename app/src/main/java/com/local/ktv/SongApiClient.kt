package com.local.ktv

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 歌曲 API 客户端 —— JS 热更新桥接
 * =================================
 * - 通过 KtvJsBridge 调用 Gitee 上的 JS 脚本
 * - API 变更时只需更新 Gitee JS 文件，App 无需发版
 * - 首次播放时实时获取 URL，不存在过期问题
 */
object SongApiClient {
    private const val TAG = "SongApiClient"

    private var bridge: KtvJsBridge? = null
    @Volatile private var ready = false

    @JvmStatic
    fun init(context: Context) {
        if (bridge != null) return
        bridge = KtvJsBridge(context)
        bridge?.init { ok ->
            ready = ok
            Log.i(TAG, "JS Bridge init: $ok")
        }
    }

    /** 兼容旧接口 */
    @JvmStatic
    fun init(sn: String, mac: String) {
        // JS 脚本自己生成 MAC/SN
    }

    @JvmStatic
    fun getAuthToken(): String = ""

    @JvmStatic
    fun getSongDownloadUrl(musicno: String?): String? =
        musicno?.takeIf(String::isNotBlank)?.let { getSongDownloadUrl(it, "720", false) }

    @JvmStatic
    fun getSongDownloadUrl(musicno: String, resolution: String, h265: Boolean): String? {
        // 主线程同步等待 WebView 回调会死锁；当前播放/下载流程会在 IO 线程调用这里。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "Synchronous JS request on main thread; using DB fallback")
            return fetchFromDb(musicno)
        }
        // 等 Bridge 就绪 (最多 3 秒)
        var waited = 0
        while (!ready && waited < 30) {
            try { Thread.sleep(100) } catch (_: Exception) {}
            waited++
        }
        if (!ready || bridge == null) {
            Log.w(TAG, "Bridge not ready after ${waited*100}ms, DB fallback")
            return fetchFromDb(musicno)
        }
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        bridge?.getSongUrl(musicno, resolution, h265) { url -> result.set(url); latch.countDown() }
        // JS 需要覆盖主/备节点和最多 3 次设备重试；略长于桥内 180 秒总超时。
        try {
            latch.await(185, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result.get() ?: fetchFromDb(musicno)
    }

    /** DB cloud_url fallback — 必须通过 demo 检测才能使用 */
    private fun fetchFromDb(musicno: String): String? {
        try {
            val db = MuseDatabase()
            if (db.open()) {
                val url = db.getCloudUrl(musicno)
                db.close()
                // DB 中的 cloud_url 可能也是 demo 地址, 做 URL 基本校验
                if (url != null && !isLikelyDemoUrl(url)) return url
            }
        } catch (_: Exception) {}
        return null
    }

    /** 快速判断是否为 demo URL (不含 JS 的完整逻辑, 仅做 IP+端口 硬特征兜底) */
    private fun isLikelyDemoUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return true
        // 纯 IP 地址 + 非 80/443 端口 → 高概率 demo
        if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
            val port = runCatching { java.net.URI(url).port }.getOrDefault(-1)
            if (port > 0 && port != 80 && port != 443) return true
        }
        return false
    }

    @JvmStatic
    fun clearTokenCache() {}
}
