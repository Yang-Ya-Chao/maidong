package com.local.ktv

import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Own-account authentication; optional credentials share the encrypted session vault. */
internal object IgebaAccount {
    data class Session(val userId: Long, val nickname: String)
    @Volatile var session: Session? = null
        private set
    private var cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val registrationCookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var generation = 0
    private data class Credentials(val account: String, val password: String)
    private var credentials: Credentials? = null
    private val reloginLock = Any()
    private var lastReloginAttempt = 0L
    val automaticLoginEnabled: Boolean get() = synchronized(this) { credentials != null }
    private var codeSentAt = 0L
    private var codePhone = ""
    private const val REGISTER_URL = "http://gc.amanktv.cn/usr.php"

    private var vault: IgebaSessionVault? = null

    @Synchronized fun initialize(context: android.content.Context) {
        if (vault != null) return
        val storage = IgebaSessionVault(context)
        vault = storage
        runCatching {
            val saved = storage.load() ?: return@runCatching
            val restored = Session(saved.getLong("userId"), saved.getString("nickname"))
            require(restored.userId > 0)
            val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            val entries = saved.getJSONArray("cookies")
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val expires = item.optLong("expires", -1)
                if (expires >= 0 && expires <= System.currentTimeMillis()) continue
                val uri = URI(item.getString("uri"))
                if (uri.host != "app.ige8.net") continue
                val cookie = java.net.HttpCookie(item.getString("name"), item.getString("value")).apply {
                    domain = item.optString("domain").takeIf { it.isNotEmpty() }
                    path = item.optString("path", "/")
                    secure = item.optBoolean("secure")
                    version = item.optInt("version")
                    if (expires >= 0) maxAge = ((expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                }
                jar.cookieStore.add(uri, cookie)
            }
            credentials = saved.optJSONObject("credentials")?.let {
                Credentials(it.getString("account"), it.getString("password"))
            }
            cookies = jar
            session = restored
        }.onFailure {
            storage.clear()
            android.util.Log.w("IgebaAccount", "保存的账号会话无法恢复，请重新登录")
        }
    }

    private fun persist(value: Session, jar: CookieManager, savedCredentials: Credentials? = credentials) {
        val entries = org.json.JSONArray()
        for (uri in jar.cookieStore.urIs) {
            if (uri.host != "app.ige8.net") continue
            for (cookie in jar.cookieStore.get(uri)) {
                if (cookie.hasExpired()) continue
                entries.put(JSONObject().put("uri", uri.toString()).put("name", cookie.name)
                    .put("value", cookie.value).put("domain", cookie.domain.orEmpty())
                    .put("path", cookie.path ?: "/").put("secure", cookie.secure).put("version", cookie.version)
                    .put("expires", if (cookie.maxAge < 0) -1L else System.currentTimeMillis() + cookie.maxAge.coerceAtMost(315360000L) * 1000))
            }
        }
        val saved = JSONObject().put("userId", value.userId).put("nickname", value.nickname).put("cookies", entries)
        savedCredentials?.let { saved.put("credentials", JSONObject().put("account", it.account).put("password", it.password)) }
        vault?.save(saved)
    }

    @Synchronized fun logout() {
        generation++
        session = null
        credentials = null
        cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        vault?.clear()
    }

    @Synchronized fun attach(connection: HttpURLConnection) {
        cookies.get(connection.url.toURI(), emptyMap()).forEach { (key, values) ->
            if (key.equals("Cookie", true)) connection.setRequestProperty(key, values.joinToString("; "))
        }
    }

    @Synchronized fun receive(connection: HttpURLConnection) {
        cookies.put(connection.url.toURI(), connection.headerFields.filterKeys { it != null })
        if (connection.headerFields.keys.any { it.equals("Set-Cookie", true) }) {
            session?.let { value -> runCatching { persist(value, cookies) }
                .onFailure { android.util.Log.w("IgebaAccount", "账号会话更新未能保存") } }
        }
    }

    private fun request(url: String, version: String, command: String, params: JSONObject,
                        jar: CookieManager = registrationCookies): JSONObject {
        val target = URL(url)
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", IgebaApiClient.USER_AGENT)
            jar.get(target.toURI(), emptyMap()).forEach { (key, values) ->
                if (key.equals("Cookie", true)) connection.setRequestProperty(key, values.joinToString("; "))
            }
            val form = linkedMapOf("VER" to version, "CMD" to command, "PARAMS" to params.toString())
            val body = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                if (BuildConfig.DEBUG && command == "DO_LOGIN") {
                    val type = connection.contentType?.substringBefore(';')?.take(80)
                    val server = connection.getHeaderField("Server")?.take(80)
                    android.util.Log.w(
                        "IgebaLogin",
                        "host=${connection.url.host} status=$status type=$type server=$server " +
                            "proxy=${connection.usingProxy()}"
                    )
                }
                throw IOException("账号服务 HTTP $status")
            }
            jar.put(target.toURI(), connection.headerFields.filterKeys { it != null })
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    fun login(account: String, password: String, remember: Boolean = false): Session {
        require(account.isNotBlank() && password.isNotEmpty()) { "请输入账号和密码" }
        val epoch = synchronized(this) { ++generation }
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val value = authenticate(account, password, jar)
        val savedCredentials = if (remember) Credentials(account.trim(), password) else null
        synchronized(this) {
            check(epoch == generation) { "本次登录已取消" }
            persist(value, jar, savedCredentials)
            session = value
            cookies = jar
            credentials = savedCredentials
        }
        return value
    }

    private fun authenticate(account: String, password: String, jar: CookieManager): Session {
        val params = JSONObject().put("UserName", account.trim()).put("Password", password)
            .put("ExeInfos", JSONObject().put("MaidongKTV", BuildConfig.VERSION_NAME).toString())
            .put("EnvInfos", JSONObject().put("Os", "Android ${Build.VERSION.RELEASE}").toString())
            .put("PlayerInfos", JSONObject().toString()).put("ReferId", 0)
        val result = request(IgebaApiClient.ADDRESS_URL, "10.0", "DO_LOGIN", params, jar)
        return parseSession(result)
    }

    /** One recovery at a time; failed requests retain the previous session. */
    fun relogin(expected: Session): Session = synchronized(reloginLock) recovery@ {
        val saved: Credentials
        val epoch: Int
        synchronized(this) {
            val current = session ?: throw IOException("账号已退出，请重新登录")
            check(current.userId == expected.userId) { "账号已切换，请重试下载" }
            if (current !== expected) return@recovery current
            saved = credentials ?: throw IOException("会话已失效，请重新登录并勾选自动恢复登录")
            val now = System.nanoTime()
            check(lastReloginAttempt == 0L || now - lastReloginAttempt >= 60_000_000_000L) {
                "账号恢复请求过于频繁，请稍后重试"
            }
            lastReloginAttempt = now
            epoch = generation
        }
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val value = authenticate(saved.account, saved.password, jar)
        check(value.userId == expected.userId) { "恢复登录返回的账号不一致" }
        synchronized(this) {
            check(epoch == generation && session === expected) { "账号已变更，本次恢复已取消" }
            persist(value, jar)
            cookies = jar
            session = value
            generation++
        }
        value
    }

    internal fun parseSession(result: JSONObject): Session {
        if (result.optInt("Code", -1) <= 0) throw IOException(result.optString("Reason", "登录失败").take(160))
        val user = result.optJSONObject("UserInfo") ?: throw IOException("登录响应缺少用户信息")
        val id = user.optLong("UserId", 0)
        if (id <= 0) throw IOException("登录响应缺少有效账号编号")
        return Session(id, user.optString("NickName", "i歌霸用户"))
    }

    @Synchronized fun remainingSeconds(phone: String): Long =
        if (codePhone == phone) ((codeSentAt + 120_000L - android.os.SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0) else 0

    fun sendCode(phone: String) {
        require(phone.matches(Regex("1[3-9]\\d{9}"))) { "请输入有效的中国大陆手机号" }
        synchronized(this) {
            val left = remainingSeconds(phone)
            check(left == 0L) { "请在 ${left} 秒后重新获取验证码" }
            codePhone = phone
            codeSentAt = android.os.SystemClock.elapsedRealtime()
        }
        val result = request(REGISTER_URL, "1", "SND_VERIFY",
            JSONObject().put("Addr", phone).put("VerifyType", 1).put("SignName", "i歌霸"))
        if (result.optInt("Result", -1) < 0) throw IOException(result.optString("Description", "验证码发送失败").take(160))
    }

    fun register(phone: String, password: String, code: String) {
        require(phone.matches(Regex("1[3-9]\\d{9}"))) { "请输入有效的中国大陆手机号" }
        require(password.isNotEmpty() && code.isNotBlank()) { "请输入密码和验证码" }
        val result = request(REGISTER_URL, "1", "DO_REG", JSONObject()
            .put("AccountName", phone).put("Email", "").put("Mobile", phone)
            .put("Password", password).put("VerifyBy", 4).put("VerifyCode", code.trim()))
        if (result.optInt("Result", -1) < 0) throw IOException(result.optString("Description", "注册失败").take(160))
    }
}
