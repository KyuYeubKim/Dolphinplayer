package com.dolphin.mplayer.iradio

import android.content.Context
import com.dolphin.mplayer.RadioInfo
import com.dolphin.mplayer.youtube.YtLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 인터넷 라디오 (0.78) — 방송사가 자기 웹 · 앱 플레이어에 쓰는 공개 스트림을 그대로 재생하고, 편성 정보를 받는다.
 * 차 라디오(미디어센터)와 달리 지역이 바뀌어도 같은 방송이 이어지고, 채널을 누르면 바로 바뀐다.
 *
 *  - KBS: 콩 웹 플레이어의 채널 API (서명된 주소, 몇 시간 뒤 만료) + 편성표 API (진행자 · 제작진 · 프로그램 소개까지)
 *  - MBC: mini 웹 플레이어 주소 API + 오늘 편성표 (진행자 · 사진)
 *  - SBS: 고릴라 웹 플레이어 주소 API (토큰) + 지금 프로그램
 *  - 그 밖(CBS · EBS · YTN · TBS · 국악방송 · 극동방송): 웹 플레이어의 고정 주소. 편성 정보 API 는 없음
 * 주소가 만료되거나 끊기면 IRadioEngine 이 다시 받아 이어서 재생한다.
 */
object IRadioApi {

    sealed class Source {
        /** KBS 채널 코드 (콩): 21=1라디오 22=해피FM 23=3라디오 24=클래식FM 25=쿨FM 26=한민족방송 */
        class Kbs(val code: String) : Source()
        /** MBC mini 채널 (sfm · mfm · chm), sched = 편성표 sType (FM=표준FM · FM4U · CHM=올댓뮤직, 없으면 편성 정보 없음) */
        class Mbc(val channel: String, val sched: String?) : Source()
        /** SBS 고릴라 스트림 경로 (powerpc/powerfm), onair = 지금 프로그램 채널 id (S07) */
        class Sbs(val path: String, val onair: String) : Source()
        class Fixed(val url: String) : Source()
    }

    data class Station(
        val id: String,
        val broadcaster: String,
        val channel: String,
        /** 수도권 주파수 (인터넷 전용이면 빈 글자) */
        val freq: String,
        val source: Source,
        /** 한 줄 소개 (편성 정보가 없는 방송에 대신 보임) */
        val about: String,
        val homepage: String,
        /** radio-browser 로고 찾기 (RadioInfo, KBS 는 채널 API 의 로고) */
        val logoQuery: String = "",
    ) {
        val name: String get() = "$broadcaster $channel".trim()
        val freqText: String get() = if (freq.isEmpty()) "인터넷" else freq
    }

    val STATIONS = listOf(
        Station("kbs_cool", "KBS", "쿨FM", "FM 89.1", Source.Kbs("25"), "KBS 2FM · 가요 · 팝 · 청춘 음악", "https://program.kbs.co.kr/radio/2fm/"),
        Station("kbs_happy", "KBS", "해피FM", "FM 106.1", Source.Kbs("22"), "KBS 2라디오 · 가요 · 생활 정보", "https://program.kbs.co.kr/radio/2radio/"),
        Station("kbs_1r", "KBS", "1라디오", "FM 97.3", Source.Kbs("21"), "KBS 1라디오 · 뉴스 · 시사 · 교양", "https://program.kbs.co.kr/radio/1radio/"),
        Station("kbs_classic", "KBS", "클래식FM", "FM 93.1", Source.Kbs("24"), "KBS 1FM · 클래식 · 국악", "https://program.kbs.co.kr/radio/1fm/"),
        Station("kbs_3r", "KBS", "3라디오", "FM 104.9", Source.Kbs("23"), "KBS 3라디오 · 사랑의소리방송", "https://program.kbs.co.kr/radio/3radio/"),
        Station("kbs_hmj", "KBS", "한민족방송", "AM 972", Source.Kbs("26"), "KBS 한민족방송 · 해외 동포 · 북한 대상", "https://program.kbs.co.kr/radio/hanminjok/"),
        Station("mbc_fm4u", "MBC", "FM4U", "FM 91.9", Source.Mbc("mfm", "FM4U"), "MBC FM4U · 음악 전문", "https://www.imbc.com/broad/radio/fm4u/", "MBC FM4U"),
        Station("mbc_std", "MBC", "표준FM", "FM 95.9", Source.Mbc("sfm", "FM"), "MBC 표준FM · 시사 · 교양 · 음악", "https://www.imbc.com/broad/radio/fm/", "MBC FM"),
        Station("mbc_chm", "MBC", "올댓뮤직", "", Source.Mbc("chm", "CHM"), "MBC 올댓뮤직 · 인터넷 음악 채널", "https://mini.imbc.com/", "MBC"),
        Station("sbs_power", "SBS", "파워FM", "FM 107.7", Source.Sbs("powerpc/powerfm", "S07"), "SBS 파워FM · 음악 · 토크", "https://programs.sbs.co.kr/radio/powerfm", "SBS Power FM"),
        Station("sbs_love", "SBS", "러브FM", "FM 103.5", Source.Sbs("lovepc/lovefm", "S08"), "SBS 러브FM · 시사 · 생활 · 음악", "https://programs.sbs.co.kr/radio/lovefm", "SBS Love FM"),
        Station("cbs_music", "CBS", "음악FM", "FM 93.9", Source.Fixed("https://m-aac.cbs.co.kr/mweb_cbs939/_definst_/cbs939.stream/playlist.m3u8"),
            "CBS 음악FM · 음악 전문 (편성 정보 없음)", "https://www.cbs.co.kr/radio/", "CBS 음악"),
        Station("cbs_std", "CBS", "표준FM", "FM 98.1", Source.Fixed("https://m-aac.cbs.co.kr/mweb_cbs981/_definst_/cbs981.stream/playlist.m3u8"),
            "CBS 표준FM · 시사 · 교양 (편성 정보 없음)", "https://www.cbs.co.kr/radio/", "CBS 표준"),
        Station("ebs_fm", "EBS", "FM", "FM 104.5", Source.Fixed("https://ebsonair.ebs.co.kr/fmradiofamilypc/familypc1m/playlist.m3u8"),
            "EBS FM · 어학 · 교육 (편성 정보 없음)", "https://home.ebs.co.kr/radio/", "EBS FM"),
        Station("ytn", "YTN", "라디오", "FM 94.5", Source.Fixed("https://radiolive.ytn.co.kr/radio/_definst_/20211118_fmlive/playlist.m3u8"),
            "YTN 라디오 · 뉴스 · 경제 (편성 정보 없음)", "https://radio.ytn.co.kr/", "YTN"),
        Station("tbs", "TBS", "FM", "FM 95.1", Source.Fixed("https://cdnfm.tbs.seoul.kr/tbs/_definst_/tbs_fm_web_360.smil/playlist.m3u8"),
            "TBS 교통방송 · 서울 교통 · 생활 (편성 정보 없음)", "https://tbs.seoul.kr/", "TBS FM"),
        Station("gugak", "국악방송", "", "FM 99.1", Source.Fixed("https://mgugaklive.nowcdn.co.kr/gugakradio/gugakradio.stream/playlist.m3u8"),
            "국악방송 · 국악 · 전통 음악 (편성 정보 없음)", "https://www.igbf.kr/", "gukak"),
        Station("febc", "극동방송", "", "FM 106.9", Source.Fixed("https://mlive2.febc.net/live/seoulfm/playlist.m3u8"),
            "극동방송 · 기독교 · 음악 (편성 정보 없음)", "https://www.febc.net/", "FEBC Seoul"),
    )

    fun station(id: String?): Station? = STATIONS.firstOrNull { it.id == id }

    /** 프로그램 하나. start · end = 그날 0시부터 분 (자정을 넘기면 1440 이상), 모르면 -1 */
    data class Program(
        val title: String,
        val subtitle: String = "",
        val dj: String = "",
        val staff: String = "",
        val about: String = "",
        val start: Int = -1,
        val end: Int = -1,
        val image: String = "",
        val homepage: String = "",
    ) {
        val timeText: String get() = if (start < 0) "" else "${hm(start)}~${if (end < 0) "" else hm(end)}"
        fun isOn(min: Int) = start >= 0 && end > start && min >= start && min < end
    }

    /** 방송 정보: 지금 프로그램 + 오늘 편성표 (SBS 는 지금 것만) */
    data class Info(val now: Program?, val today: List<Program>, val at: Long) {
        val next: Program? get() = now?.let { n -> today.firstOrNull { it.start >= n.end && n.end > 0 } }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val io = Executors.newFixedThreadPool(3)
    private val infos = ConcurrentHashMap<String, Info>()
    private val logos = ConcurrentHashMap<String, String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    /** 받기에 실패한 시각 — 1분 동안은 화면을 다시 그려도 또 묻지 않음 (인터넷이 끊긴 곳) */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private fun failedRecently(key: String) = (failedAt[key] ?: 0) > System.currentTimeMillis() - 60_000

    fun hm(min: Int): String = "%02d:%02d".format(min / 60 % 24, min % 60)

    /** 편성표는 한국 시각 — 차 시계의 시간대와 상관없이 */
    val KST: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")

    /** 지금 한국 시각 (그날 0시부터 분) */
    fun nowMin(): Int = Calendar.getInstance(KST).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 스트림 주소 (백그라운드 스레드) ----------

    /** 재생 주소. KBS · MBC · SBS 는 매번 새로 받음 (서명 · 토큰이 몇 시간 뒤 만료) */
    fun streamUrl(s: Station): String = when (val src = s.source) {
        is Source.Kbs -> {
            val o = JSONObject(get("https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/${src.code}"))
            o.optJSONObject("channelMaster")?.optString("image_path_channel_logo")?.takeIf { it.isNotEmpty() }?.let { logos[s.id] = it }
            val items = o.optJSONArray("channel_item") ?: JSONArray()
            (0 until items.length()).map { items.getJSONObject(it) }
                .firstOrNull { it.optString("media_type") == "radio" }?.optString("service_url")
                ?.takeIf { it.startsWith("http") } ?: throw java.io.IOException("KBS 주소 없음")
        }
        is Source.Mbc -> get("https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=${src.channel}").trim()
            .takeIf { it.startsWith("http") } ?: throw java.io.IOException("MBC 주소 없음")
        is Source.Sbs -> get("https://apis.sbs.co.kr/play-api/1.0/livestream/${src.path}?protocol=hls&ssl=Y").trim()
            .takeIf { it.startsWith("http") } ?: throw java.io.IOException("SBS 주소 없음")
        is Source.Fixed -> src.url
    }

    // ---------- 방송 정보 ----------

    /** 받아 둔 정보 (없으면 null) */
    fun cached(s: Station): Info? = infos[s.id]

    /** 정보가 오래됐는지: 5분이 지났거나 지금 프로그램이 끝났으면 */
    private fun stale(i: Info?): Boolean {
        if (i == null) return true
        if (System.currentTimeMillis() - i.at > 5 * 60_000) return true
        val n = i.now ?: return false
        return n.end in 1..nowMin()
    }

    /** 편성 정보 API 가 있는 방송인지 */
    fun hasInfo(s: Station) = !(s.source is Source.Fixed || (s.source is Source.Mbc && s.source.sched == null))

    /** 정보가 오래됐을 때만 새로 받아 done (화면 그리기용 — 새것이면 아무것도 안 함, 다시 그리기가 돌지 않게) */
    fun refreshIfStale(s: Station, done: (Info) -> Unit) {
        if (hasInfo(s) && stale(infos[s.id]) && !failedRecently(s.id)) refresh(s, force = true, done = done)
    }

    /** 방송 정보를 받아 done (main 스레드 아님). 편성 API 가 없는 방송은 done 을 부르지 않음 */
    fun refresh(s: Station, force: Boolean = false, done: (Info) -> Unit) {
        if (!hasInfo(s)) return
        if (!force && !stale(infos[s.id])) return done(infos[s.id]!!)
        if (!loading.add(s.id)) return
        io.execute {
            try {
                val info = when (val src = s.source) {
                    is Source.Kbs -> kbs(src.code)
                    is Source.Mbc -> mbc(src.sched!!)
                    is Source.Sbs -> sbs(src.onair)
                    is Source.Fixed -> null
                } ?: return@execute
                infos[s.id] = info
                done(info)
            } catch (e: Exception) {
                failedAt[s.id] = System.currentTimeMillis()
                YtLog.e("인터넷 라디오 방송 정보 (${s.name}) 받기 실패: ${e.message}")
            } finally {
                loading.remove(s.id)
            }
        }
    }

    /** 편성표의 "07000000" · "0705" · "07:05" → 분 */
    private fun toMin(t: String): Int {
        val d = t.filter { it.isDigit() }
        if (d.length < 4) return -1
        return d.substring(0, 2).toInt() * 60 + d.substring(2, 4).toInt()
    }

    /** 끝 시각이 시작보다 이르면 자정을 넘긴 것 */
    private fun endMin(start: Int, end: Int) = if (end in 0 until start) end + 1440 else end

    private fun withNow(list: List<Program>): Info {
        val m = nowMin()
        val sorted = list.sortedBy { it.start }
        // 자정 넘은 새벽: 어제 시작한 프로그램이 오늘 표 맨 위에 없을 수 있어 끝 시각 +1440 도 확인
        val now = sorted.firstOrNull { it.isOn(m) } ?: sorted.firstOrNull { it.isOn(m + 1440) }
        return Info(now, sorted, System.currentTimeMillis())
    }

    private fun kbs(code: String): Info {
        val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA).apply { timeZone = KST }.format(java.util.Date())
        val arr = JSONArray(get("https://static.api.kbs.co.kr/mediafactory/v1/schedule/weekly?rtype=json&local_station_code=00" +
            "&channel_code=$code&program_planned_date_from=$day&program_planned_date_to=$day"))
        val list = ArrayList<Program>()
        var lastCode = ""
        for (i in 0 until arr.length()) {
            val ss = arr.getJSONObject(i).optJSONArray("schedules") ?: continue
            for (j in 0 until ss.length()) {
                val o = ss.getJSONObject(j)
                val st = toMin(o.optString("service_start_time"))
                val end = endMin(st, toMin(o.optString("service_end_time")))
                val title = txt(o, "program_title").ifEmpty { txt(o, "programming_table_title") }
                    .removeSuffix("제작협찬").trim().trimEnd('(', '[', '-', '·').trim() // 광고 표시 꼬리표는 제목에서 뺌
                // KBS 는 한 프로그램을 30분 · 1시간씩(…제작협찬 등) 나눠 싣는다 → 이어진 같은 프로그램은 하나로
                val code = o.optString("program_code")
                val prev = list.lastOrNull()
                if (prev != null && code.isNotEmpty() && code == lastCode && prev.end == st) {
                    list[list.lastIndex] = prev.copy(end = end, title = if (title.length < prev.title.length) title else prev.title)
                    continue
                }
                lastCode = code
                list += Program(
                    title = title,
                    subtitle = txt(o, "program_subtitle"),
                    dj = txt(o, "program_actor"),
                    staff = txt(o, "program_staff"),
                    about = listOfNotNull(
                        txt(o, "program_intention").ifEmpty { null },
                        listOf(txt(o, "production_type"), txt(o, "rerun_classification"))
                            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
                    ).joinToString("\n"),
                    start = st,
                    end = end,
                    image = o.optString("image_w").takeIf { it.startsWith("http") }.orEmpty(),
                    homepage = o.optString("homepage_url").takeIf { it.startsWith("http") }.orEmpty(),
                )
            }
        }
        return withNow(list.filter { it.title.isNotBlank() && it.start >= 0 })
    }

    /** 글자 칸: 'null' 은 빈 글자, &lt; 같은 HTML 기호는 풀어서 */
    private fun txt(o: JSONObject, key: String): String {
        val v = o.optString(key).trim()
        if (v.isEmpty() || v == "null") return ""
        return if ('&' in v || '<' in v) android.text.Html.fromHtml(v, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() else v
    }

    private fun mbc(type: String): Info {
        val arr = JSONArray(get("https://control.imbc.com/Schedule/Radio?sType=$type"))
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("IsToday") != "N" }.map { o ->
            val st = toMin(o.optString("StartTime"))
            Program(
                title = txt(o, "Title"),
                subtitle = txt(o, "SubTitle").takeIf { it != txt(o, "Title") }.orEmpty(),
                dj = txt(o, "Players"),
                about = txt(o, "MiniMsg"),
                start = st,
                end = endMin(st, toMin(o.optString("EndTime"))),
                image = o.optString("Photo").takeIf { it.startsWith("http") }.orEmpty(),
                homepage = o.optString("HomepageURL").takeIf { it.startsWith("http") }.orEmpty(),
            )
        }
        return withNow(list.filter { it.title.isNotBlank() && it.start >= 0 })
    }

    private fun sbs(channel: String): Info {
        val i = JSONObject(get("https://apis.sbs.co.kr/play-api/1.0/onair/channel/$channel?v_type=2&platform=pcweb&protocol=hls&ssl=Y"))
            .getJSONObject("onair").getJSONObject("info")
        val st = toMin(i.optString("starttime"))
        val p = Program(
            title = i.optString("title"),
            start = st,
            end = endMin(st, toMin(i.optString("endtime"))),
            image = (i.optJSONObject("thumbs")?.optString("640") ?: i.optString("thumbimg")).replaceFirst("http://", "https://"),
            homepage = i.optString("homeurl").takeIf { it.startsWith("http") }.orEmpty(),
        )
        return Info(p.takeIf { it.title.isNotBlank() }, listOfNotNull(p.takeIf { it.title.isNotBlank() }), System.currentTimeMillis())
    }

    // ---------- 로고 ----------

    /** 로고 주소 (KBS 는 채널 API, 나머지는 radio-browser). 없으면 받아서 done (main 스레드 아님) */
    fun logo(c: Context, s: Station, done: (String) -> Unit): String? {
        logos[s.id]?.let { return it }
        val pref = c.getSharedPreferences("iradio_logo", Context.MODE_PRIVATE)
        pref.getString(s.id, null)?.let { logos[s.id] = it; return it.ifEmpty { null } }
        if (failedRecently("logo:" + s.id) || !loading.add("logo:" + s.id)) return null
        val app = c.applicationContext
        if (s.source is Source.Kbs) io.execute {
            runCatching {
                JSONObject(get("https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/${s.source.code}"))
                    .optJSONObject("channelMaster")?.optString("image_path_channel_logo").orEmpty()
            }.onSuccess { url ->
                logos[s.id] = url
                pref.edit().putString(s.id, url).apply()
                if (url.isNotEmpty()) done(url)
            }.onFailure { failedAt["logo:" + s.id] = System.currentTimeMillis() }
            loading.remove("logo:" + s.id)
        } else if (s.logoQuery.isNotEmpty()) {
            RadioInfo.fetchLogo(app, s.logoQuery) { l ->
                val url = l.favicon.replaceFirst("http://", "https://")
                logos[s.id] = url
                pref.edit().putString(s.id, url).apply()
                loading.remove("logo:" + s.id)
                if (url.isNotEmpty()) done(url)
            }
        }
        return null
    }
}
