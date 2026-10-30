package com.dolphin.mplayer.youtube

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube 목록 (0.66) — TV 앱이 받는 데이터(innertube TVHTML5)를 TV 방식 로그인 토큰으로 받아 D-Player 목록 형식으로 바꾼다.
 * 웹 페이지를 띄우지 않아 가볍다 (PC 측정: 보관함 0.7초 · 재생목록 0.3초 · 다음 곡 목록 1.1초).
 *
 * 돌려주는 형식은 0.65 까지 웹 확장(content.js)이 보내던 것과 같다 — 화면(MainActivity)은 그대로 쓴다:
 *   항목 {kind: song|list|browse, title, subtitle, thumb, videoId, playlistId, browseId, params}
 *   칸   {title, items}
 * TV 데이터는 두 모양이 섞여 온다: tileRenderer(보관함·재생목록·다음 곡) / lockupViewModel(검색).
 */
object YtApi {

    private const val BASE = "https://www.youtube.com/youtubei/v1/"

    class ApiException(msg: String) : Exception(msg)

    /** innertube 호출 (백그라운드에서). 오류면 ApiException */
    fun innertube(c: Context, token: String, endpoint: String, body: JSONObject): JSONObject {
        body.put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "TVHTML5").put("clientVersion", YtAuth.tvVersion(c))
            .put("hl", "ko").put("gl", "KR")))
        val r = YtAuth.post("$BASE$endpoint?prettyPrint=false", body,
            mapOf("Authorization" to "Bearer $token", "X-Origin" to "https://www.youtube.com"))
        r.optJSONObject("error")?.let { throw ApiException("${it.optInt("code")} ${it.optString("message")}") }
        if (r.has("error")) throw ApiException(r.optString("error"))
        return r
    }

    private fun call(c: Context, endpoint: String, body: JSONObject): JSONObject {
        val token = YtAuth.accessToken(c) ?: throw ApiException("로그인 필요")
        return innertube(c, token, endpoint, body)
    }

    // ---------- 화면별 요청 ----------

    /** 홈: TV 의 '음악' 화면 (내가 좋아하는 아티스트 · 최근에 재생한 음악 · 믹스 …) */
    fun home(c: Context): JSONArray = shelves(call(c, "browse", JSONObject().put("browseId", "FEtopics_music")))

    /** 내 재생목록 (YouTube Music 보관함) → items [{id, title, subtitle, thumb}] */
    fun library(c: Context): JSONArray {
        val r = call(c, "browse", JSONObject().put("browseId", "FEmusic_liked_playlists"))
        val out = JSONArray()
        out.put(JSONObject().put("id", "LM").put("title", "좋아요 표시한 음악").put("subtitle", "자동 재생목록"))
        for (it in items(r)) {
            val id = it.optString("browseId").removePrefix("VL").ifEmpty { it.optString("playlistId") }
            if (id.isEmpty() || id == "LM") continue
            out.put(JSONObject().put("id", id).put("title", it.optString("title"))
                .put("subtitle", it.optString("subtitle")).put("thumb", it.optString("thumb")))
        }
        return out
    }

    /** 재생목록의 곡 (다음 쪽까지 최대 약 500곡) → 곡 항목들 */
    fun playlist(c: Context, playlistId: String): List<JSONObject> {
        var r = call(c, "browse", JSONObject().put("browseId", "VL$playlistId"))
        val out = ArrayList(items(r).filter { it.optString("kind") == "song" })
        var pages = 0
        while (pages++ < 5) {
            val token = continuation(r) ?: break
            r = call(c, "browse", JSONObject().put("continuation", token))
            val more = items(r).filter { it.optString("kind") == "song" && out.none { o -> o.optString("videoId") == it.optString("videoId") } }
            if (more.isEmpty()) break
            out.addAll(more)
        }
        out.forEach { it.put("playlistId", playlistId) }
        return out
    }

    /** 재생목록 제목 (화면 위쪽) */
    fun playlistTitle(r: JSONObject): String =
        find(r, "entityMetadataRenderer").firstOrNull()?.let { text(it.opt("title")) }.orEmpty()

    /** 검색 → 칸들 (Shorts 칸은 뺌) */
    fun search(c: Context, query: String): JSONArray = shelves(call(c, "search", JSONObject().put("query", query)))

    /** 앨범 · 재생목록 · 아티스트 화면 → {title, playlistId, shelves} */
    fun browse(c: Context, browseId: String, params: String?): JSONObject {
        if (browseId.startsWith("VL")) {
            val id = browseId.removePrefix("VL")
            val first = call(c, "browse", JSONObject().put("browseId", browseId))
            val songs = playlist(c, id)
            return JSONObject().put("title", playlistTitle(first)).put("playlistId", id)
                .put("shelves", JSONArray().put(JSONObject().put("title", "").put("items", JSONArray(songs))))
        }
        val r = call(c, "browse", JSONObject().put("browseId", browseId).apply { if (!params.isNullOrEmpty()) put("params", params) })
        val title = find(r, "tvSurfaceHeaderRenderer").firstOrNull()?.let { text(it.opt("title")) }
            ?: find(r, "c4TabbedHeaderRenderer").firstOrNull()?.let { text(it.opt("title")) } ?: ""
        return JSONObject().put("title", title).put("playlistId", "").put("shelves", shelves(r))
    }

    /**
     * 다음 곡 목록 (YouTube 의 '다음 동영상' = 재생목록 또는 라디오). 첫 칸(대기열)의 곡들.
     * 라디오(RD…)는 끝이 없어서, 거의 다 들으면 마지막 곡으로 다시 불러 이어 붙인다.
     */
    fun next(c: Context, videoId: String, playlistId: String?): List<JSONObject> {
        val r = call(c, "next", JSONObject().put("videoId", videoId).apply { if (!playlistId.isNullOrEmpty()) put("playlistId", playlistId) })
        val shelf = find(r, "shelfRenderer").firstOrNull() ?: return emptyList()
        return collect(shelf.opt("content")).filter { it.optString("kind") == "song" }
            .onEach { if (!playlistId.isNullOrEmpty() && it.optString("playlistId").isEmpty()) it.put("playlistId", playlistId) }
    }

    /** YouTube 영상 첫 화면: 맞춤 동영상 / 구독 / 기록 / 나중에 볼 동영상 */
    fun videoFeed(c: Context, browseId: String): JSONArray = shelves(call(c, "browse", JSONObject().put("browseId", browseId)))

    // ---------- 해석 ----------

    /** 응답 안의 칸들. 칸 제목이 없는 묶음(보관함 격자 · 재생목록)도 칸 하나로 */
    fun shelves(r: JSONObject): JSONArray {
        val out = JSONArray()
        val shelfList = find(r, "shelfRenderer")
        for (s in shelfList) {
            val title = text(s.optJSONObject("headerRenderer")?.let { h ->
                h.optJSONObject("shelfHeaderRenderer")?.let { sh ->
                    sh.optJSONObject("avatarLockup")?.optJSONObject("avatarLockupRenderer")?.opt("title") ?: sh.opt("title")
                }
            }).trim()
            if (title == "Shorts") continue
            val list = collect(s.opt("content"))
            if (list.isEmpty()) continue
            out.put(JSONObject().put("title", title).put("items", JSONArray(list)))
        }
        if (shelfList.isEmpty()) {
            val list = collect(r.opt("contents"))
            if (list.isNotEmpty()) out.put(JSONObject().put("title", "").put("items", JSONArray(list)))
        }
        return out
    }

    /** 응답 안의 모든 항목 (순서대로) */
    fun items(r: JSONObject): List<JSONObject> = collect(r.opt("contents") ?: r.opt("onResponseReceivedActions") ?: r)

    private fun collect(o: Any?): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val tile = x.optJSONObject("tileRenderer")
                    val lockup = x.optJSONObject("lockupViewModel")
                    val item = when {
                        tile != null -> tile(tile)
                        lockup != null -> lockup(lockup)
                        else -> null
                    }
                    if (tile != null || lockup != null) {
                        if (item != null) {
                            val key = item.optString("kind") + ":" + item.optString("videoId") + item.optString("browseId") + item.optString("playlistId")
                            if (seen.add(key)) out.add(item)
                        }
                        return // 칸 안의 메뉴(긴 누름) 등은 보지 않음
                    }
                    // 머리글 · 메뉴 · 오버레이 안의 버튼은 항목이 아님
                    for (k in x.keys()) if (k != "headerRenderer" && k != "menu" && k != "menuRenderer" && !k.startsWith("overlay")) walk(x.opt(k))
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return out
    }

    private fun tile(t: JSONObject): JSONObject? {
        val meta = t.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
            ?: t.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")?.optJSONArray("thumbnailOverlays")
                ?.optJSONObject(0)?.optJSONObject("tileMetadataRenderer")
        val title = text(meta?.opt("title")).trim()
        if (title.isEmpty()) return null
        val lines = meta?.optJSONArray("lines")
        val parts = ArrayList<String>()
        if (lines != null) for (i in 0 until lines.length()) {
            val items = lines.optJSONObject(i)?.optJSONObject("lineRenderer")?.optJSONArray("items") ?: continue
            val line = (0 until items.length()).mapNotNull { j ->
                text(items.optJSONObject(j)?.optJSONObject("lineItemRenderer")?.opt("text")).trim().takeIf { it.isNotEmpty() && it != "•" }
            }.joinToString(" ")
            if (line.isNotBlank()) parts.add(line)
        }
        val header = t.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val duration = header?.optJSONArray("thumbnailOverlays")?.let { ov ->
            (0 until ov.length()).firstNotNullOfOrNull { text(ov.optJSONObject(it)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.opt("text")).takeIf { s -> s.isNotEmpty() } }
        }
        val thumb = thumb(header?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
        val subtitle = (parts.take(1) + listOfNotNull(duration)).joinToString(" · ")
        return endpoint(t.optJSONObject("onSelectCommand"), t.optString("contentType"), t.optString("contentId"))
            ?.put("title", title)?.put("subtitle", subtitle)?.put("thumb", thumb)
            // 영상 카드용: 모든 줄(채널 · 조회수 · 올린 때)과 길이를 따로
            ?.put("info", parts.joinToString(" · "))?.put("duration", duration.orEmpty())
    }

    private fun lockup(l: JSONObject): JSONObject? {
        val type = l.optString("contentType")
        if (type == "LOCKUP_CONTENT_TYPE_SHORT") return null
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = text(meta?.opt("title")).trim()
        if (title.isEmpty()) return null
        val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val rowTexts = (0 until (rows?.length() ?: 0)).map { i ->
            rows?.optJSONObject(i)?.optJSONArray("metadataParts")?.let { p ->
                (0 until p.length()).joinToString(" · ") { text(p.optJSONObject(it)?.opt("text")) }
            }.orEmpty()
        }.filter { it.isNotEmpty() }
        val firstRow = rowTexts.firstOrNull().orEmpty()
        val img = l.optJSONObject("contentImage")
        val vm = img?.optJSONObject("thumbnailViewModel")
            ?: img?.optJSONObject("collectionThumbnailViewModel")?.optJSONObject("primaryThumbnail")?.optJSONObject("thumbnailViewModel")
        val badge = vm?.optJSONArray("overlays")?.let { ov ->
            (0 until ov.length()).firstNotNullOfOrNull {
                ov.optJSONObject(it)?.optJSONObject("thumbnailBottomOverlayViewModel")?.optJSONArray("badges")
                    ?.optJSONObject(0)?.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.takeIf { s -> s.contains(':') }
            }
        }
        val thumb = thumb(vm?.optJSONObject("image")?.optJSONArray("sources"))
        val cmd = l.optJSONObject("rendererContext")?.optJSONObject("commandContext")?.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
        val kind = when (type) {
            "LOCKUP_CONTENT_TYPE_PLAYLIST" -> "TILE_CONTENT_TYPE_PLAYLIST"
            "LOCKUP_CONTENT_TYPE_CHANNEL" -> "TILE_CONTENT_TYPE_CHANNEL"
            else -> "TILE_CONTENT_TYPE_VIDEO"
        }
        return endpoint(cmd, kind, l.optString("contentId"))?.put("title", title)
            ?.put("subtitle", (listOf(firstRow).filter { it.isNotEmpty() } + listOfNotNull(badge)).joinToString(" · "))
            ?.put("thumb", thumb)?.put("info", rowTexts.joinToString(" · "))?.put("duration", badge.orEmpty())
    }

    /** 누르면 갈 곳 → 항목 종류. 곡(watch) / 바로 재생할 목록(믹스) / 들어가 볼 화면(재생목록 · 아티스트 · 앨범) */
    private fun endpoint(cmd: JSONObject?, contentType: String, contentId: String): JSONObject? {
        cmd ?: return null
        cmd.optJSONObject("watchEndpoint")?.let { w ->
            val vid = w.optString("videoId")
            val list = w.optString("playlistId")
            if (contentType == "TILE_CONTENT_TYPE_PLAYLIST" && list.isNotEmpty())
                return if (list.startsWith("RD")) JSONObject().put("kind", "list").put("playlistId", list)
                else JSONObject().put("kind", "browse").put("browseId", "VL$list")
            if (vid.isEmpty()) return null
            return JSONObject().put("kind", "song").put("videoId", vid).put("playlistId", list)
        }
        cmd.optJSONObject("watchPlaylistEndpoint")?.let { w ->
            return JSONObject().put("kind", "list").put("playlistId", w.optString("playlistId"))
        }
        cmd.optJSONObject("browseEndpoint")?.let { b ->
            val id = b.optString("browseId")
            if (id.isEmpty() || id.startsWith("FE") && id != "FEmusic_liked_playlists") return null // 보관함의 '기록' 같은 바로가기 칸
            return JSONObject().put("kind", "browse").put("browseId", id).put("params", b.optString("params"))
        }
        if (contentId.length == 11 && contentType == "TILE_CONTENT_TYPE_VIDEO") return JSONObject().put("kind", "song").put("videoId", contentId)
        return null
    }

    private fun continuation(r: JSONObject): String? {
        for (key in listOf("nextContinuationData", "continuationCommand", "reloadContinuationData")) {
            find(r, key).firstOrNull()?.let { c ->
                (c.optString("continuation").ifEmpty { c.optString("token") }).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /** 목록 그림: 너무 크지 않은 것 (목록 줄 · 앨범 사진용) */
    private fun thumb(list: JSONArray?): String {
        list ?: return ""
        var best: JSONObject? = null
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val w = t.optInt("width")
            if (best == null || (w in 120..480 && w > best.optInt("width")) || best.optInt("width") > 480 && w < best.optInt("width")) best = t
        }
        val url = best?.optString("url").orEmpty()
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** {simpleText} / {runs:[{text}]} / {content} / 문자열 → 글자 */
    fun text(o: Any?): String = when (o) {
        null -> ""
        is String -> o
        is JSONObject -> o.optString("simpleText").ifEmpty {
            o.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }
                ?: o.optString("content")
        }
        else -> ""
    }

    /** key 이름의 모든 객체 (깊이 우선 순서) */
    fun find(o: Any?, key: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> for (k in x.keys()) {
                    val v = x.opt(k)
                    if (k == key && v is JSONObject) out.add(v)
                    walk(v)
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return out
    }
}
