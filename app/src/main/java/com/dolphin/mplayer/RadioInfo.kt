package com.dolphin.mplayer

import android.content.Context
import com.dolphin.mplayer.update.AppUpdater
import com.dolphin.mplayer.youtube.YtLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * 라디오 채널 정보 (0.74): 주파수 → 방송사 · 채널명 + 로고.
 * 차 미디어센터는 'FM 93.9 MHz' 만 알려 주고 방송 이름을 주지 않는다 (2026-11-01 차 진단).
 * radio-browser.info(공개 인터넷 라디오 목록)에는 FM 주파수 칸이 없어서(한국 104곳 중 이름에 주파수가 적힌 곳 7곳),
 * ① 주파수 → 방송사 · 채널명은 아래 수도권 표로 정하고 ② radio-browser 에서는 그 채널의 로고 · 홈페이지만 받는다.
 * 로고 조회는 채널마다 한 번만 하고 결과를 설정에 저장 (없어도 저장해 다시 묻지 않음, 30일 뒤 다시).
 */
object RadioInfo {

    data class Channel(val broadcaster: String, val channel: String, val query: String) {
        val label: String get() = "$broadcaster $channel".trim()
    }

    data class Logo(val favicon: String, val homepage: String)

    /** 수도권(서울 · 경기) 기준. 지역이 다르면 틀릴 수 있어 사용자가 이름을 고칠 수 있다 */
    private val FM = mapOf(
        89100 to Channel("KBS", "쿨FM", "KBS Cool FM"),
        89700 to Channel("원음방송", "", "원음방송"),
        90700 to Channel("경인방송", "iFM", "iFM"),
        91900 to Channel("MBC", "FM4U", "MBC FM4U"),
        93100 to Channel("KBS", "클래식FM", "KBS Classic FM"),
        93900 to Channel("CBS", "음악FM", "CBS 음악"),
        94500 to Channel("YTN", "라디오", "YTN"),
        95100 to Channel("TBS", "FM", "TBS FM"),
        95900 to Channel("MBC", "표준FM", "MBC FM"),
        97300 to Channel("KBS", "1라디오", "KBS 1Radio"),
        98100 to Channel("CBS", "표준FM", "CBS 표준"),
        99100 to Channel("국악방송", "", "gukak"),
        101300 to Channel("TBS", "eFM", "TBS eFM"),
        101900 to Channel("BBS", "불교방송", "BBS"),
        103500 to Channel("SBS", "러브FM", "SBS Love FM"),
        104500 to Channel("EBS", "FM", "EBS FM"),
        104900 to Channel("KBS", "3라디오", "KBS 3"),
        105300 to Channel("CPBC", "평화방송", "CPBC"),
        106100 to Channel("KBS", "해피FM", "kbs해피fm"),
        106900 to Channel("극동방송", "", "FEBC Seoul"),
        107700 to Channel("SBS", "파워FM", "SBS Power FM"),
    )
    private val AM = mapOf(
        711 to Channel("KBS", "1라디오", "KBS 1Radio"),
        792 to Channel("SBS", "러브FM", "SBS Love FM"),
        837 to Channel("CBS", "표준FM", "CBS 표준"),
        900 to Channel("MBC", "표준FM", "MBC FM"),
        1188 to Channel("극동방송", "", "FEBC Seoul"),
    )

    fun channel(band: Int, khz: Int): Channel? = if (band == RadioPresets.FM) FM[khz] else AM[khz]

    // ---- radio-browser 로고 (https://docs.radio-browser.info) ----

    private const val PREF = "radio_logo"
    private const val MAX_AGE = 30L * 24 * 3600 * 1000
    /** docs: 서버는 all.api.radio-browser.info 를 DNS 로 찾아 고름 — 실패하면 알려진 서버 */
    private val SERVERS = listOf("all.api.radio-browser.info", "de1.api.radio-browser.info")
    private val io = Executors.newSingleThreadExecutor()

    /** 저장해 둔 로고 (없거나 오래됐으면 null) */
    fun cachedLogo(c: Context, query: String): Logo? {
        val o = runCatching { JSONObject(prefs(c).getString(query, null) ?: return null) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - o.optLong("at") > MAX_AGE) return null
        return Logo(o.optString("favicon"), o.optString("homepage"))
    }

    /** 로고를 받아 저장한 뒤 done (main 스레드 아님). 이미 있으면 바로 */
    fun fetchLogo(c: Context, query: String, done: (Logo) -> Unit) {
        cachedLogo(c, query)?.let { return done(it) }
        val app = c.applicationContext
        io.execute {
            val logo = SERVERS.firstNotNullOfOrNull { host -> runCatching { search(app, host, query) }.getOrNull() } ?: return@execute
            prefs(app).edit().putString(query, JSONObject().put("favicon", logo.favicon).put("homepage", logo.homepage)
                .put("at", System.currentTimeMillis()).toString()).apply()
            YtLog.e("라디오 로고 ($query): ${if (logo.favicon.isEmpty()) "없음" else "받음"}")
            done(logo)
        }
    }

    private fun search(c: Context, host: String, query: String): Logo {
        val url = "https://$host/json/stations/search?countrycode=KR&hidebroken=true&order=votes&reverse=true&limit=5&name=" +
            URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "D-Player/" + AppUpdater.currentVersion(c)) // docs: 앱 이름/버전
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            // 로고가 있는 것 중 https 를 먼저 (차에서 http 그림이 막힐 수 있음)
            val pick = list.filter { it.optString("favicon").isNotEmpty() }
                .sortedByDescending { it.optString("favicon").startsWith("https") }.firstOrNull() ?: list.firstOrNull()
            return Logo(pick?.optString("favicon").orEmpty(), pick?.optString("homepage").orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
