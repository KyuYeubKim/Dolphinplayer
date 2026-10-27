package com.dolphin.mplayer.youtube

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * YouTube 로그인 (0.66) — TV 방식(기기 코드). 웹 엔진 없이 로그인하려고 스마트 TV 의 YouTube 앱과 같은 방법을 쓴다:
 * 차 화면에 코드를 띄우면 사용자가 휴대폰으로 google.com/device 에 들어가 그 코드를 넣고 허용한다.
 * 비밀번호는 D-Player 가 보지도 입력하지도 않는다. 받은 토큰은 앱 전용 설정(yt_auth)에만 두고 밖으로 보내지 않는다.
 *
 *  - TV 앱의 로그인용 식별값(client id/secret)은 youtube.com/tv 페이지 스크립트에서 그때그때 읽는다 (바뀌면 다시 읽음).
 *  - 프리미엄 여부는 계정 정보의 'YouTube Premium' 표시로 확인 → 프리미엄이 아니면 YouTube 기능을 켜지 않는다.
 */
object YtAuth {

    private const val PREFS = "yt_auth"
    const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
    private const val FALLBACK_TV_VERSION = "7.20261025.19.00"

    class Device(val userCode: String, val url: String, val deviceCode: String, val intervalSec: Int, val expiresAt: Long)

    /** 계정 이름 · 프리미엄 여부 (확인한 시각) */
    class Account(val name: String, val premium: Boolean, val checkedAt: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasToken(c: Context) = prefs(c).getString("refresh", null) != null

    fun account(c: Context): Account? {
        val p = prefs(c)
        if (!p.contains("premium")) return null
        return Account(p.getString("name", "").orEmpty(), p.getBoolean("premium", false), p.getLong("checked", 0))
    }

    /** innertube TVHTML5 클라이언트 버전 (TV 페이지에서 읽은 값) */
    fun tvVersion(c: Context): String = prefs(c).getString("tv_ver", null) ?: FALLBACK_TV_VERSION

    // ---------- TV 앱 식별값 ----------

    private fun client(c: Context, refresh: Boolean = false): Pair<String, String> {
        val p = prefs(c)
        val id = p.getString("cid", null)
        val secret = p.getString("csec", null)
        if (!refresh && id != null && secret != null) return id to secret
        val page = get("https://www.youtube.com/tv")
        Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(page)?.let { p.edit().putString("tv_ver", it.groupValues[1]).apply() }
        val js = Regex("<script id=\"base-js\" src=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: throw IllegalStateException("TV 페이지에서 스크립트를 못 찾음")
        val body = get(if (js.startsWith("http")) js else "https://www.youtube.com$js")
        val m = Regex("clientId:\"([^\"]+\\.apps\\.googleusercontent\\.com)\",\\n?[^\"]*?:\"([^\"]+)\"").find(body)
            ?: throw IllegalStateException("TV 스크립트에서 로그인 식별값을 못 찾음")
        p.edit().putString("cid", m.groupValues[1]).putString("csec", m.groupValues[2]).apply()
        return m.groupValues[1] to m.groupValues[2]
    }

    // ---------- 기기 코드 로그인 ----------

    /** 코드 받기 (백그라운드에서 부를 것) */
    fun startDevice(c: Context): Device {
        val (id, _) = client(c)
        val r = post("https://www.youtube.com/o/oauth2/device/code", JSONObject()
            .put("client_id", id).put("scope", SCOPE)
            .put("device_id", UUID.randomUUID().toString().replace("-", "")).put("device_model", "ytlr::"))
        if (!r.has("user_code")) throw IllegalStateException("코드를 못 받음: ${r.optString("error")}")
        return Device(r.getString("user_code"), r.optString("verification_url", "https://www.google.com/device"),
            r.getString("device_code"), r.optInt("interval", 5),
            System.currentTimeMillis() + r.optInt("expires_in", 1800) * 1000L)
    }

    /** 사용자가 허용했는지 한 번 확인. true = 로그인됨, false = 아직, 예외 = 거절 · 만료 */
    fun pollDevice(c: Context, d: Device): Boolean {
        val (id, secret) = client(c)
        val r = post("https://www.youtube.com/o/oauth2/token", JSONObject()
            .put("client_id", id).put("client_secret", secret).put("code", d.deviceCode)
            .put("grant_type", "http://oauth.net/grant_type/device/1.0"))
        if (r.has("access_token")) {
            save(c, r)
            YtLog.e("YouTube 로그인 됨 (TV 방식)")
            return true
        }
        val err = r.optString("error")
        if (err == "authorization_pending" || err == "slow_down") return false
        throw IllegalStateException(if (err == "expired_token") "코드 시간이 지남" else if (err == "access_denied") "허용하지 않음" else err)
    }

    private fun save(c: Context, r: JSONObject) {
        prefs(c).edit()
            .putString("access", r.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + (r.optLong("expires_in", 3600) - 60) * 1000)
            .apply { r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString("refresh", it) } }
            .apply()
    }

    /** 쓸 수 있는 접근 토큰 (만료됐으면 새로 받음). 로그인 안 돼 있으면 null. 백그라운드에서 부를 것 */
    @Synchronized
    fun accessToken(c: Context): String? {
        val p = prefs(c)
        val refresh = p.getString("refresh", null) ?: return null
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0)) return access
        var (id, secret) = client(c)
        var r = post("https://www.youtube.com/o/oauth2/token", JSONObject()
            .put("client_id", id).put("client_secret", secret).put("refresh_token", refresh).put("grant_type", "refresh_token"))
        if (r.optString("error") == "invalid_client" || r.optString("error") == "unauthorized_client") {
            // TV 앱 식별값이 바뀜 → 다시 읽고 한 번 더
            client(c, refresh = true).let { id = it.first; secret = it.second }
            r = post("https://www.youtube.com/o/oauth2/token", JSONObject()
                .put("client_id", id).put("client_secret", secret).put("refresh_token", refresh).put("grant_type", "refresh_token"))
        }
        if (!r.has("access_token")) {
            YtLog.e("YouTube 토큰 새로 받기 실패: ${r.optString("error")} ${r.optString("error_description")}")
            if (r.optString("error") == "invalid_grant") clear(c) // 사용자가 연결을 끊음 → 다시 로그인
            return null
        }
        save(c, r)
        return r.getString("access_token")
    }

    /** 계정 이름 · 프리미엄 여부 읽기 (백그라운드). 로그인 안 돼 있으면 null */
    fun checkAccount(c: Context): Account? {
        val token = accessToken(c) ?: return null
        val r = YtApi.innertube(c, token, "account/accounts_list", JSONObject())
        val s = r.toString()
        val name = Regex("\"accountName\":\\{\"simpleText\":\"([^\"]*)\"").find(s)?.groupValues?.get(1).orEmpty()
        val premium = s.contains("\"shouldShowPremiumLogo\":true") ||
            Regex("\"unlimitedStatus\":\\[\\{\"simpleText\":\"[^\"]*Premium").containsMatchIn(s)
        if (name.isEmpty() && !s.contains("accountItem")) throw IllegalStateException("계정 정보를 못 읽음")
        prefs(c).edit().putString("name", name).putBoolean("premium", premium).putLong("checked", System.currentTimeMillis()).apply()
        YtLog.e("YouTube 계정 확인: 프리미엄=${if (premium) "예" else "아니오"}")
        return Account(name, premium, System.currentTimeMillis())
    }

    /** 로그아웃: Google 에 연결 해제를 알리고(실패해도 무시) 저장한 토큰을 지움. 백그라운드에서 */
    fun logout(c: Context) {
        val token = prefs(c).getString("refresh", null)
        if (token != null) runCatching {
            val conn = URL("https://oauth2.googleapis.com/revoke?token=" + java.net.URLEncoder.encode(token, "UTF-8"))
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.close()
            conn.responseCode
            conn.disconnect()
        }
        clear(c)
    }

    private fun clear(c: Context) {
        prefs(c).edit().remove("refresh").remove("access").remove("expires").remove("name").remove("premium").remove("checked").apply()
    }

    // ---------- HTTP ----------

    fun get(url: String, ua: String = TV_UA): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ua)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** JSON POST → JSON 응답 (오류 응답도 내용을 돌려줌) */
    fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", TV_UA)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "HTTP $code") }
                .apply { if (code >= 400 && !has("error")) put("error", "HTTP $code") }
        } finally {
            conn.disconnect()
        }
    }
}
