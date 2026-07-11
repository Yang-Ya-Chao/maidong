package com.local.ktv

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.LinkedHashMap

object SongApiClient {
    private const val TAG = "SongApiClient"
    private const val APP_ID = "d4eeacc6cec3434fbc8c41608a3056a0"
    private const val APP_KEY = "024210cba40d4385a93e6c2d3249bfb5"
    private const val SDK_KEY = "19042303a8374f67ae3fe1e25c97936f"
    private const val VN = "4.1.3.03161025"
    private const val VER = "2.0"
    private const val AUTH_HOST = "http://gz.ac16.vip"

    private var deviceSn = "abe235a87118f6de"
    private var deviceMac = "080027deed4f"
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpireTime = 0L

    @JvmStatic
    fun init(sn: String, mac: String) {
        deviceSn = sn
        deviceMac = mac
        clearTokenCache()
    }

    @JvmStatic
    @Synchronized
    @Throws(Exception::class)
    fun getAuthToken(): String {
        cachedToken?.takeIf { System.currentTimeMillis() < tokenExpireTime }?.let { return it }
        val params = linkedMapOf(
            "appid" to APP_ID,
            "mac" to "${deviceMac}_$deviceSn",
            "sn" to deviceSn,
            "time" to (System.currentTimeMillis() / 1000).toString(),
            "ver" to VER,
            "vn" to VN,
        )
        val response = JSONObject(httpGet("$AUTH_HOST/i.php?${buildSignedParams(params, APP_KEY)}"))
        check(response.optInt("code") == 200) { "Auth failed: $response" }
        return response.getString("token").also {
            cachedToken = it
            tokenExpireTime = System.currentTimeMillis() + 3_600_000
        }
    }

    @JvmStatic
    fun getSongDownloadUrl(musicno: String?): String? =
        musicno?.takeIf(String::isNotBlank)?.let { getSongDownloadUrl(it, "720", false) }

    @JvmStatic
    fun getSongDownloadUrl(musicno: String, resolution: String, h265: Boolean): String? {
        repeat(3) { attempt ->
            try {
                val params = LinkedHashMap<String, String>().apply {
                    put("appid", APP_ID)
                    put("device", "${deviceMac}_$deviceSn")
                    put("ish265", if (h265) "1" else "0")
                    put("ls", "1")
                    put("musicno", musicno)
                    put("resolution", resolution)
                    put("sn", deviceSn)
                    put("time", (System.currentTimeMillis() / 1000).toString())
                    put("token", getAuthToken())
                }
                val response = JSONObject(httpGet("$AUTH_HOST/music/do.php?${buildSignedParams(params, SDK_KEY)}"))
                val code = response.optInt("code")
                if (code == 200) {
                    val url = response.optString("data").takeIf(String::isNotBlank)
                    if (url != null && (!url.contains("wb66.cn") || attempt == 2)) return url
                } else if (code != 20002) {
                    Log.e(TAG, "下载链接请求失败: code=$code, msg=${response.optString("msg")}")
                    return null
                }
            } catch (error: Exception) {
                Log.w(TAG, "下载链接请求第 ${attempt + 1} 次失败", error)
                clearTokenCache()
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(if (attempt == 0) 1500 else 2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    @JvmStatic
    fun clearTokenCache() {
        cachedToken = null
        tokenExpireTime = 0
    }

    private fun buildSignedParams(params: Map<String, String>, key: String, vararg excluded: String): String {
        val signInput = params.filterKeys { it !in excluded }.entries.joinToString("&") { "${it.key}=${it.value}" }
        val query = params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        return "$query&sign=${md5(signInput + key)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "ThunderSDK/4.1.3")
            connection.setRequestProperty("Accept", "*/*")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
