package com.dolphin.mplayer.youtube

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.dolphin.mplayer.R
import com.dolphin.mplayer.local.VideoActivity
import com.dolphin.mplayer.local.VideoSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.media.session.MediaSession as PlatformSession

/**
 * YouTube Music / YouTube 재생 엔진 (0.66 — 웹 엔진 없이 앱 플레이어로).
 *
 *  - 로그인: TV 방식 기기 코드 (YtAuth). YouTube Premium 계정일 때만 켠다.
 *  - 목록: YouTube TV 데이터 (YtApi) — 홈 · 보관함 · 재생목록 · 검색 · 다음 곡(라디오).
 *  - 재생: 곡의 실제 소리 파일 주소(YtStreams, NewPipeExtractor)를 ExoPlayer 로 직접 튼다.
 *    받은 소리는 앱 캐시(최대 150MB)에 남아 다시 들을 때 바로 나오고, 다음 곡은 주소와 앞부분을 미리 받아 둔다.
 *  - 실패하면 웹 재생으로 대신하지 않는다. 멈추고 어디서 왜 실패했는지 진단 로그(YtLog)에 남긴다.
 *
 * 화면(MainActivity)과 주고받는 함수 · 메시지(listener 로 보내는 JSON)는 0.65 의 웹 엔진과 같은 모양이다.
 * 재생 정보 · 버튼은 Android MediaSession("D-Player YouTube")으로 내보내 플레이 화면 · 핸들 버튼이 그대로 동작한다.
 */
object YtEngine : YtService.Source {

    enum class Site(val label: String) {
        MUSIC("YouTube Music"),
        VIDEO("YouTube"),
    }

    /** 재생 목록의 곡 하나 */
    private data class Track(val videoId: String, val title: String, val artist: String, val thumb: String, val playlistId: String) {
        /** 같은 곡 판별: 믹스의 '다음 곡' 목록은 같은 곡을 뮤직비디오 · 음원으로 두 번씩 줘서(videoId 다름) 제목 + 가수로 (0.74) */
        val songKey: String get() = title.lowercase().replace(SPACES, "") + "|" + artist.lowercase().replace(SPACES, "")
    }
    private val SPACES = Regex("""\s+""")

    private const val ACTION_SHUFFLE = "dplayer.yt.shuffle"
    private const val CACHE_MB = 150L
    /** 다음 곡을 미리 받아 두는 앞부분 크기 (AAC 128k 로 약 1분) */
    private const val PRECACHE_BYTES = 1_000_000L

    private val main = Handler(Looper.getMainLooper())
    /** 목록 요청 (두 개까지 동시에) */
    private val api = Executors.newFixedThreadPool(2)
    /** 다음 곡 미리 받기 · 앨범 사진 (하나씩) */
    private val bg = Executors.newSingleThreadExecutor()

    private lateinit var app: Context
    private var started = false
    private var cache: SimpleCache? = null
    private var music: ExoPlayer? = null
    private var video: ExoPlayer? = null
    private var session: PlatformSession? = null

    /** 지금 재생 목록 (ExoPlayer 의 항목 순서와 같음) · 그 목록이 속한 재생목록 id */
    private val tracks = ArrayList<Track>()
    private var queueList = ""

    /** 플레이 화면이 따르는 쪽 */
    private var active = Site.MUSIC
    /** 영상 때문에 멈춘 음악 → YT Music 으로 돌아오면 이어서 */
    private var musicInterrupted = false
    private var videoTrack: Track? = null

    private var art: Bitmap? = null
    private var artFor = ""

    /** 로그인(프리미엄) 여부. null = 아직 확인 전, false = 로그인 안 됨 · 프리미엄 아님 */
    var loggedIn: Boolean? = null
        private set
    /** 로그인은 됐지만 프리미엄이 아니라서 끈 경우 */
    var notPremium = false
        private set
    val accountName: String get() = if (::app.isInitialized) YtAuth.account(app)?.name.orEmpty() else ""

    /** 엔진 → 화면 메시지 (status / home / library / playlist / queue / search / browse / error) */
    var listener: ((JSONObject) -> Unit)? = null

    val site: Site get() = active
    override val playing: Boolean get() = current()?.isPlaying == true
    override val label: String get() = active.label
    override fun currentTitle() = currentTrack()?.title.orEmpty()
    override fun sessionToken(): PlatformSession.Token? = session?.sessionToken

    private fun current(): ExoPlayer? = if (active == Site.VIDEO) video else music

    private fun currentTrack(): Track? = if (active == Site.VIDEO) videoTrack
    else music?.currentMediaItemIndex?.let { tracks.getOrNull(it) } ?: restoreShown

    private fun emit(msg: JSONObject) {
        main.post { listener?.invoke(msg) }
    }

    // ---------- 시작 · 로그인 ----------

    /** 엔진 시작 (가벼움: 플레이어는 처음 재생할 때 만든다). 로그인 · 프리미엄을 확인해 화면에 알려 준다 */
    fun start(context: Context) {
        app = context.applicationContext
        ensureSession()
        if (started) return
        started = true
        if (!YtAuth.hasToken(app)) {
            loggedIn = false
            YtLog.e("YouTube: 로그인 안 됨")
            emit(status())
            return
        }
        // 오늘 확인한 프리미엄이면 바로 쓰고, 뒤에서 다시 확인
        val acc = YtAuth.account(app)
        if (acc != null && acc.premium && System.currentTimeMillis() - acc.checkedAt < 7 * 24 * 3600_000L) {
            loggedIn = true
            emit(status())
            main.post { maybeRestore() }
        }
        api.execute { refreshAccount() }
    }

    /**
     * 0.65 까지 쓰던 웹 엔진(GeckoView)이 남긴 파일 정리 (한 번만) — 에뮬레이터 측정 약 560MB (페이지 캐시 · 프로필).
     * D-Player 앱 전용 폴더 안의 것만 지운다.
     */
    fun cleanOldWebEngine(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getBoolean("gecko_cleaned", false)) return
        bg.execute {
            var bytes = 0L
            val targets = listOf(File(app.filesDir, "mozilla"), File(app.filesDir, "yt_last_art.png"), File(app.cacheDir, "gecko_temp")) +
                (app.cacheDir.listFiles { f -> f.isDirectory && f.name.endsWith(".default") }?.toList() ?: emptyList())
            for (f in targets) if (f.exists()) {
                bytes += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                f.deleteRecursively()
            }
            p.edit().putBoolean("gecko_cleaned", true).apply()
            YtLog.e("예전 웹 엔진 파일 정리: ${bytes / 1_048_576}MB")
        }
    }

    /** 계정 확인 실패(시동 직후 인터넷 없음 등) 뒤 다시 확인한 횟수 */
    private var accountRetries = 0

    private fun refreshAccount() {
        val acc = runCatching { YtAuth.checkAccount(app) }
            .onFailure { YtLog.e("YouTube 계정 확인 실패: ${why(it)}") }.getOrNull()
        // 토큰은 있는데 확인을 못 함 → 1분 뒤 다시 (최대 5번)
        if (acc == null && YtAuth.hasToken(app) && accountRetries++ < 5) main.postDelayed({ api.execute { refreshAccount() } }, 60_000)
        main.post {
            val before = loggedIn
            when {
                acc == null && YtAuth.hasToken(app) -> if (loggedIn == null) loggedIn = YtAuth.account(app)?.premium == true // 인터넷이 없으면 지난 확인 결과로
                acc == null -> { loggedIn = false; notPremium = false }
                else -> { loggedIn = acc.premium; notPremium = !acc.premium }
            }
            if (loggedIn != before) {
                emit(status())
                if (loggedIn == true) maybeRestore()
            }
        }
    }

    private fun status() = JSONObject().put("type", "status").put("loggedIn", loggedIn == true).put("notPremium", notPremium)

    /** 로그인 진행 (화면이 코드를 보여 줌). 돌려준 함수를 부르면 그만둠 */
    fun login(context: Context, onCode: (YtAuth.Device) -> Unit, onDone: (ok: Boolean, message: String) -> Unit): () -> Unit {
        app = context.applicationContext
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread { // 최대 30분 기다리므로 목록 요청 스레드와 따로
            try {
                val d = YtAuth.startDevice(app)
                main.post { if (!stop.get()) onCode(d) }
                while (!stop.get() && System.currentTimeMillis() < d.expiresAt) {
                    Thread.sleep(d.intervalSec * 1000L)
                    if (stop.get()) return@Thread
                    if (YtAuth.pollDevice(app, d)) break
                }
                if (stop.get()) return@Thread
                if (!YtAuth.hasToken(app)) throw IllegalStateException("코드 시간이 지남")
                val acc = YtAuth.checkAccount(app) ?: throw IllegalStateException("계정 정보를 못 읽음")
                if (!acc.premium) {
                    YtAuth.logout(app) // 쓰지 않을 연결은 바로 해제
                    main.post {
                        loggedIn = false; notPremium = true
                        emit(status())
                        onDone(false, app.getString(R.string.yt_not_premium))
                    }
                    return@Thread
                }
                main.post {
                    loggedIn = true; notPremium = false
                    started = true
                    emit(status())
                    onDone(true, acc.name)
                }
            } catch (e: Exception) {
                YtLog.e("YouTube 로그인 실패: ${why(e)}")
                main.post { if (!stop.get()) onDone(false, why(e)) }
            }
        }.start()
        return { stop.set(true) }
    }

    fun logout() {
        val c = app
        music?.stop(); music?.clearMediaItems()
        video?.stop()
        tracks.clear()
        restoreShown = null
        loggedIn = false
        notPremium = false
        publishMetadata(); publishState()
        api.execute { YtAuth.logout(c); YtLog.e("YouTube 로그아웃") }
        emit(status())
    }

    // ---------- 목록 요청 (결과는 listener 로) ----------

    private fun request(cmd: String, work: () -> JSONObject) {
        val t0 = SystemClock.elapsedRealtime()
        api.execute {
            try {
                val msg = work()
                YtLog.e("YouTube 목록 $cmd ${SystemClock.elapsedRealtime() - t0}ms")
                emit(msg)
            } catch (e: Exception) {
                YtLog.e("YouTube 목록 $cmd 실패 (${SystemClock.elapsedRealtime() - t0}ms): ${why(e)}")
                if (e is YtApi.ApiException && e.message == "로그인 필요") main.post { loggedIn = false; emit(status()) }
                emit(JSONObject().put("type", "error").put("cmd", cmd).put("message", why(e)))
            }
        }
    }

    fun requestHome(context: Context) = request("home") {
        JSONObject().put("type", "home").put("shelves", YtApi.home(context))
    }

    fun requestLibrary(context: Context) = request("library") {
        JSONObject().put("type", "library").put("items", YtApi.library(context))
    }

    fun requestPlaylist(context: Context, id: String) = request("playlist") {
        JSONObject().put("type", "playlist").put("id", id).put("items", JSONArray(YtApi.playlist(context, id)))
    }

    fun requestSearch(context: Context, query: String) = request("search") {
        JSONObject().put("type", "search").put("query", query).put("shelves", YtApi.search(context, query))
    }

    fun requestBrowse(context: Context, browseId: String, params: String? = null) = request("browse") {
        YtApi.browse(context, browseId, params).put("type", "browse").put("id", browseId)
    }

    /** 지금 재생 목록 → {type:"queue", list, items:[{videoId,title,subtitle,thumb,selected}]} */
    fun requestQueue(context: Context) {
        val idx = music?.currentMediaItemIndex ?: -1
        val items = JSONArray()
        tracks.forEachIndexed { i, t ->
            items.put(JSONObject().put("videoId", t.videoId).put("title", t.title).put("subtitle", t.artist)
                .put("thumb", t.thumb).put("selected", i == idx && active == Site.MUSIC))
        }
        emit(JSONObject().put("type", "queue").put("list", queueList).put("items", items))
    }

    // ---------- 음악 재생 ----------

    /** 재생 요청 → 소리 시작 (진단용) */
    private var playRequestedAt = 0L
    private var playMethod = ""
    private val startReports = ArrayDeque<String>()
    val lastStartReport: String get() = if (startReports.isEmpty()) "아직 없음" else startReports.joinToString(" / ")

    /**
     * 곡 재생. 곡을 바로 틀고(첫 소리를 빨리), 그 곡이 속한 재생목록 · 라디오는 뒤에서 받아 앞뒤에 붙인다.
     * 이미 지금 재생 목록에 있는 곡이면 목록은 그대로 두고 그 곡으로 건너뛴다.
     */
    fun playSong(context: Context, videoId: String, playlistId: String?, shuffle: Boolean = false,
                 title: String = "", subtitle: String = "", thumb: String = "", startMs: Long = 0) {
        start(context)
        if (loggedIn != true) return emit(JSONObject().put("type", "error").put("cmd", "play").put("message", app.getString(R.string.yt_need_login)))
        stopVideo()
        active = Site.MUSIC
        restoreShown = null
        val p = ensureMusic()
        val inQueue = tracks.indexOfFirst { it.videoId == videoId }
        playRequestedAt = SystemClock.elapsedRealtime()
        if (inQueue >= 0 && (playlistId == null || playlistId == queueList)) {
            playMethod = "목록 안 이동" + if (YtStreams.cached(videoId)) " (주소 미리 받음)" else ""
            YtLog.e("재생 요청 $videoId (지금 목록 ${inQueue + 1}번째)")
            p.seekTo(inQueue, startMs)
            p.play()
            if (shuffle) p.shuffleModeEnabled = true
            return
        }
        val list = playlistId?.takeIf { it.isNotEmpty() } ?: "RDAMVM$videoId"
        YtLog.e("재생 요청 $videoId list=$list")
        playMethod = if (YtStreams.cached(videoId)) "새 목록 (주소 미리 받음)" else "새 목록"
        tracks.clear()
        tracks.add(Track(videoId, title, artistOf(subtitle), thumb, list))
        queueList = list
        p.shuffleModeEnabled = false
        p.setMediaItem(item(tracks[0]), startMs)
        p.prepare()
        p.play()
        publishMetadata()
        queueLoading = true
        api.execute { loadQueue(videoId, list, shuffle) }
    }

    /** 재생목록 처음부터 (즐겨찾기 등) */
    fun playPlaylist(context: Context, playlistId: String) {
        start(context)
        if (loggedIn != true) return emit(JSONObject().put("type", "error").put("cmd", "play").put("message", app.getString(R.string.yt_need_login)))
        YtLog.e("재생목록 재생 $playlistId")
        playRequestedAt = SystemClock.elapsedRealtime()
        playMethod = "재생목록"
        api.execute {
            val songs = runCatching { queueFor("", playlistId) }.onFailure { YtLog.e("재생목록 불러오기 실패: ${why(it)}") }.getOrNull()
            main.post {
                if (songs.isNullOrEmpty()) return@post emit(JSONObject().put("type", "error").put("cmd", "play")
                    .put("message", app.getString(R.string.yt_no_songs)))
                stopVideo()
                active = Site.MUSIC
                restoreShown = null
                val p = ensureMusic()
                tracks.clear(); tracks.addAll(songs)
                queueList = playlistId
                p.shuffleModeEnabled = false
                p.setMediaItems(songs.map { item(it) }, 0, 0)
                p.prepare()
                p.play()
                publishMetadata()
            }
        }
    }

    /** 재생목록(PL · OLAK · LM …)은 곡 전체, 라디오 · 믹스(RD…)는 '다음 곡' 목록 */
    private fun queueFor(videoId: String, list: String): List<Track> {
        val raw = if (list.startsWith("RD")) YtApi.next(app, videoId, list)
        else YtApi.playlist(app, list).ifEmpty { YtApi.next(app, videoId, list) }
        return raw.map { Track(it.optString("videoId"), it.optString("title"), artistOf(it.optString("subtitle")), it.optString("thumb"), list) }
            .filter { it.videoId.length == 11 }.distinctBy { it.videoId }.distinctBy { it.songKey }
    }

    /** 틀고 있는 곡의 앞뒤에 재생목록을 붙인다 (재생은 끊지 않음) */
    private fun loadQueue(videoId: String, list: String, shuffle: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val songs = runCatching { queueFor(videoId, list) }
            .onFailure { YtLog.e("재생 목록 불러오기 실패 ($list): ${why(it)} — 이 곡만 재생") }.getOrNull()
        if (songs == null) {
            main.post { queueLoading = false }
            return
        }
        YtLog.e("재생 목록 ${songs.size}곡 (${SystemClock.elapsedRealtime() - t0}ms)")
        main.post {
            queueLoading = false
            val p = music ?: return@post
            if (queueList != list || tracks.size != 1 || tracks[0].videoId != videoId) return@post // 그사이 다른 곡을 고름
            val at = songs.indexOfFirst { it.videoId == videoId }
            val first = tracks[0]
            val before = if (at > 0) songs.subList(0, at) else emptyList()
            val after = if (at >= 0) songs.subList(at + 1, songs.size) else songs.filter { it.videoId != videoId }
            // 지금 곡의 제목 등이 비어 있었으면 목록의 것으로
            if (at >= 0 && first.title.isEmpty()) { tracks[0] = songs[at]; publishMetadata() }
            if (before.isNotEmpty()) { tracks.addAll(0, before); p.addMediaItems(0, before.map { item(it) }) }
            if (after.isNotEmpty()) { tracks.addAll(after); p.addMediaItems(after.map { item(it) }) }
            if (shuffle) p.shuffleModeEnabled = true
            prefetchNext()
            requestQueueIfShown()
        }
    }

    /** 라디오 · 믹스는 끝이 없음: 남은 곡이 3곡 이하면 마지막 곡으로 다음 목록을 받아 이어 붙임 */
    private var extending = false
    /** 곡을 고른 직후 재생목록을 받는 중 — 그동안 이어 붙이기를 하지 않음 (0.73 은 먼저 붙여 같은 곡이 두 번 들어감) */
    private var queueLoading = false

    private fun extendIfNeeded() {
        val p = music ?: return
        if (extending || queueLoading || !queueList.startsWith("RD") || tracks.isEmpty()) return
        if (tracks.size - p.currentMediaItemIndex > 4) return
        extending = true
        val list = queueList
        val last = tracks.last().videoId
        api.execute {
            val more = runCatching { YtApi.next(app, last, list) }.getOrNull().orEmpty()
                .map { Track(it.optString("videoId"), it.optString("title"), artistOf(it.optString("subtitle")), it.optString("thumb"), list) }
            main.post {
                extending = false
                if (queueList != list) return@post
                val have = tracks.map { it.songKey }.toHashSet()
                val add = more.filter { m -> m.videoId.length == 11 && tracks.none { it.videoId == m.videoId } }
                    .distinctBy { it.songKey }.filter { it.songKey !in have }
                if (add.isEmpty()) return@post
                tracks.addAll(add)
                music?.addMediaItems(add.map { item(it) })
                YtLog.e("라디오 이어 붙임 +${add.size}곡")
            }
        }
    }

    /** 목록의 둘째 줄(가수 · 3:30) → 가수만 (플레이 화면용) */
    private fun artistOf(subtitle: String) =
        subtitle.replace(Regex("\\s·\\s(\\d+:)?\\d+:\\d{2}$"), "").replace(Regex("\\s·\\s라이브$"), "")

    private fun item(t: Track) = MediaItem.Builder()
        .setUri("yt:${t.videoId}")
        .setMediaId(t.videoId)
        .setCustomCacheKey("yta:${t.videoId}")
        .build()

    /** 다음 곡: 주소를 미리 받고 앞부분(약 1분)을 캐시에 받아 둔다 → 곡 넘김 · 다음 곡 버튼이 바로 */
    private var prefetchedFor = ""

    private fun prefetchNext() {
        val p = music ?: return
        val nextIdx = p.nextMediaItemIndex
        if (nextIdx == C.INDEX_UNSET) return
        val t = tracks.getOrNull(nextIdx) ?: return
        if (prefetchedFor == t.videoId) return
        prefetchedFor = t.videoId
        val c = cache ?: return
        bg.execute {
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                val ds = cacheFactory(c).createDataSource()
                CacheWriter(ds, DataSpec.Builder().setUri("yt:${t.videoId}").setKey("yta:${t.videoId}")
                    .setLength(PRECACHE_BYTES).build(), null, null).cache()
            }.onSuccess { YtLog.e("다음 곡 미리 받음 '${t.title}' (${SystemClock.elapsedRealtime() - t0}ms)") }
                .onFailure { YtLog.e("다음 곡 미리 받기 실패 '${t.title}': ${why(it)}") }
        }
    }

    // ---------- 플레이어 ----------

    private fun ensureCache(): SimpleCache {
        cache?.let { return it }
        val dir = File(app.cacheDir, "yt_audio")
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(CACHE_MB * 1024 * 1024), StandaloneDatabaseProvider(app))
            .also { cache = it }
    }

    /** 캐시 → (없으면) yt:<id> 를 실제 주소로 바꿔 → HTTP */
    private fun cacheFactory(c: SimpleCache): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "yt") spec
            else {
                val s = try {
                    YtStreams.audio(spec.uri.schemeSpecificPart)
                } catch (e: Exception) {
                    throw IOException("재생 주소를 못 받음: ${why(e)}", e)
                }
                spec.withUri(Uri.parse(s.url))
            }
        }
        return CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun ensureMusic(): ExoPlayer {
        music?.let { return it }
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory(ensureCache())))
            .build()
        // 오디오 포커스는 ExoPlayer 가 처리 (다른 앱이 재생하면 멈춤 · 안내 음성 때 소리 줄임 · 통화 뒤 이어서)
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val t = currentTrack()
                YtLog.e("곡 바뀜: '${t?.title ?: mediaItem?.mediaId}' (${reasonName(reason)})")
                retried = ""
                if (active == Site.MUSIC) { publishMetadata(); publishState() }
                saveLast()
                prefetchNext()
                extendIfNeeded()
                requestQueueIfShown()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && playRequestedAt > 0) {
                    val ms = SystemClock.elapsedRealtime() - playRequestedAt
                    playRequestedAt = 0
                    startReports.addFirst("${ms}ms ($playMethod)")
                    while (startReports.size > 5) startReports.removeLast()
                    YtLog.e("소리 시작: 요청 후 ${ms}ms ($playMethod)")
                }
                if (isPlaying) { musicInterrupted = false; prefetchNext() }
                if (active == Site.MUSIC) publishState()
                saveLast()
                main.removeCallbacks(saveTick)
                if (isPlaying) main.postDelayed(saveTick, 15_000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // 곡 길이는 파일을 열어 봐야 앎 → 준비되면 곡 정보를 다시 (진행 막대 · 남은 시간)
                if (active == Site.MUSIC) { if (state == Player.STATE_READY) publishMetadata(); publishState() }
            }
            override fun onShuffleModeEnabledChanged(on: Boolean) { if (active == Site.MUSIC) publishState(); prefetchedFor = ""; prefetchNext() }
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) { if (active == Site.MUSIC) publishState() }
            override fun onPlayerError(error: PlaybackException) = onMusicError(error)
        })
        music = p
        return p
    }

    /** 한 곡에 한 번은 주소를 새로 받아 다시 시도 (주소 만료 등). 또 안 되면 멈추고 이유를 알림 */
    private var retried = ""

    private fun onMusicError(e: PlaybackException) {
        val p = music ?: return
        val t = currentTrack()
        val reason = "${e.errorCodeName}: ${why(e.cause ?: e)}"
        YtLog.e("재생 실패 '${t?.title ?: "?"}' ${t?.videoId ?: ""}: $reason")
        if (t != null && retried != t.videoId) {
            retried = t.videoId
            YtStreams.forget(t.videoId)
            val pos = p.currentPosition
            YtLog.e("재생 주소를 새로 받아 다시 시도 (${pos / 1000}초부터)")
            p.prepare()
            p.seekTo(p.currentMediaItemIndex, pos)
            p.play()
            return
        }
        playRequestedAt = 0
        emit(JSONObject().put("type", "error").put("cmd", "play").put("message", reason))
        publishState()
    }

    private fun reasonName(r: Int) = when (r) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "곡 끝남"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "건너뜀"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "목록 바뀜"
        else -> "반복"
    }

    /** 화면이 재생 목록을 보고 있으면(요청이 최근에 있었으면) 바뀐 목록을 다시 보냄 */
    private fun requestQueueIfShown() {
        if (listener != null && ::app.isInitialized) requestQueue(app)
    }

    override fun play() {
        if (active == Site.VIDEO) return video?.play() ?: Unit
        val p = music
        val last = restoreShown
        if ((p == null || p.mediaItemCount == 0) && last != null) { // 시동 후 마지막 곡
            restoreShown = null
            return playSong(app, last.videoId, last.playlistId.ifEmpty { null }, title = last.title, subtitle = last.artist,
                thumb = last.thumb, startMs = restorePos)
        }
        if (p != null && p.playbackState == Player.STATE_IDLE) p.prepare()
        p?.play()
    }

    override fun pause() { current()?.pause() }

    fun stop() {
        current()?.pause()
        current()?.seekTo(0)
    }

    fun seekTo(ms: Long) { current()?.seekTo(ms) }

    override fun next() {
        if (active == Site.VIDEO) return
        val p = music ?: return
        if (!p.hasNextMediaItem()) return
        playRequestedAt = SystemClock.elapsedRealtime()
        playMethod = "다음 곡 버튼" + if (YtStreams.cached(tracks.getOrNull(p.nextMediaItemIndex)?.videoId.orEmpty())) " (미리 받음)" else ""
        p.seekToNextMediaItem()
    }

    override fun previous() {
        if (active == Site.VIDEO) return video?.seekTo(0) ?: Unit
        music?.seekToPrevious() // 3초 넘게 들었으면 처음으로, 아니면 앞 곡
    }

    fun toggleShuffle() {
        music?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** 플레이어로 YT Music 을 고름: 영상이 재생 중이면 멈추고, 영상 때문에 멈춘 음악은 이어서 */
    fun selectMusic(context: Context) {
        start(context)
        val wasVideo = active == Site.VIDEO
        if (wasVideo) stopVideo()
        active = Site.MUSIC
        YtLog.e("YT Music 고름 (영상에서 돌아옴=$wasVideo, 이어서 재생=$musicInterrupted)")
        if (musicInterrupted) { musicInterrupted = false; music?.play() }
        publishMetadata(); publishState()
    }

    /** 다른 플레이어(Spotify · USB · 라디오 …)를 고름 — 0.65 까지 웹의 '다시 재생' 시도를 멈추던 자리. 지금은 할 일 없음 */
    fun quiet() = Unit

    // ---------- YouTube 영상 ----------

    /** 영상 화질 상한 (차 부하를 생각해 480p) */
    private const val VIDEO_MAX_HEIGHT = 480

    /** 영상 재생: 전체화면을 먼저 열고, 영상 주소는 뒤에서 받아 바로 재생 */
    fun playVideo(context: Context, videoId: String, title: String, subtitle: String, thumb: String) {
        start(context)
        if (loggedIn != true) return emit(JSONObject().put("type", "error").put("cmd", "play").put("message", app.getString(R.string.yt_need_login)))
        if (music?.isPlaying == true) { musicInterrupted = true; music?.pause() }
        val t = Track(videoId, title, artistOf(subtitle), thumb, "")
        videoTrack = t
        active = Site.VIDEO
        val p = ensureVideo()
        p.stop()
        p.clearMediaItems()
        publishMetadata(); publishState()
        context.startActivity(Intent(context, VideoActivity::class.java).putExtra(VideoActivity.EXTRA_SOURCE, VideoActivity.SOURCE_YT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
        val t0 = SystemClock.elapsedRealtime()
        YtLog.e("영상 재생 요청 $videoId '$title'")
        api.execute {
            val s = runCatching { YtStreams.video(videoId, VIDEO_MAX_HEIGHT) }
                .onFailure { YtLog.e("영상 주소 실패 $videoId: ${why(it)}") }.getOrNull()
            main.post {
                if (videoTrack !== t) return@post
                if (s == null) return@post emit(JSONObject().put("type", "error").put("cmd", "play").put("message", app.getString(R.string.yt_video_failed)))
                val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                val f = ProgressiveMediaSource.Factory(http)
                val src = when {
                    s.format == "HLS" -> HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(s.url)) // 라이브
                    s.videoUrl != null -> MergingMediaSource(
                        f.createMediaSource(MediaItem.fromUri(s.videoUrl)), f.createMediaSource(MediaItem.fromUri(s.url)))
                    else -> f.createMediaSource(MediaItem.fromUri(s.url))
                }
                p.setMediaSource(src)
                p.prepare()
                p.play()
                YtLog.e("영상 재생 시작 준비 ${SystemClock.elapsedRealtime() - t0}ms (${s.videoLabel})")
            }
        }
    }

    private fun ensureVideo(): ExoPlayer {
        video?.let { return it }
        val p = ExoPlayer.Builder(app).build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (active == Site.VIDEO) publishState() }
            override fun onPlaybackStateChanged(state: Int) {
                if (active == Site.VIDEO) publishState()
                if (state == Player.STATE_ENDED && videoTrack != null && !videoOnScreen) selectMusicQuietly()
            }
            override fun onPlayerError(error: PlaybackException) {
                YtLog.e("영상 재생 실패 '${videoTrack?.title}': ${error.errorCodeName}: ${why(error.cause ?: error)}")
                emit(JSONObject().put("type", "error").put("cmd", "play").put("message", "${error.errorCodeName}: ${why(error.cause ?: error)}"))
            }
        })
        video = p
        return p
    }

    private fun stopVideo() {
        if (videoTrack == null) return
        video?.stop()
        video?.clearMediaItems()
        videoTrack = null
        if (active == Site.VIDEO) active = Site.MUSIC
    }

    /** 화면 없이 듣던 영상이 끝나면 플레이 화면을 음악으로 */
    private fun selectMusicQuietly() {
        stopVideo()
        active = Site.MUSIC
        publishMetadata(); publishState()
    }

    private var videoOnScreen = false

    /** 영상 전체화면(VideoActivity)이 쓰는 재생원 */
    val videoSource = object : VideoSource {
        override val playing get() = video?.isPlaying == true
        override fun play() { video?.play() }
        override fun pause() { video?.pause() }
        override fun seekTo(ms: Long) { video?.seekTo(ms) }
        override fun position() = video?.currentPosition ?: 0
        override fun duration() = video?.duration?.takeIf { it != C.TIME_UNSET } ?: 0
        override fun title() = videoTrack?.title.orEmpty()
        override fun loading() = video?.playbackState == Player.STATE_BUFFERING || video?.mediaItemCount == 0
        override fun videoAspect(): Float {
            val v = video?.videoSize ?: return 0f
            if (v.width == 0 || v.height == 0) return 0f
            return v.width * v.pixelWidthHeightRatio / v.height
        }
        override fun attach(view: SurfaceView) {
            videoOnScreen = true
            video?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build() }
            video?.setVideoSurfaceView(view)
        }
        /** 화면을 닫으면 소리만 계속 (영상 디코딩은 끔 → 차 부하 줄임) */
        override fun detach(view: SurfaceView) {
            videoOnScreen = false
            video?.clearVideoSurfaceView(view)
            video?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build() }
            if (video?.isPlaying != true) selectMusicQuietly()
        }
    }

    // ---------- 시동 후 이어 듣기 ----------
    // 시동을 끄기 전 곡 · 위치 · 재생목록을 기억했다가, 켜면 플레이 화면에 마지막 곡을 보여 주고
    // 끄기 전에 재생 중이었으면 그 위치부터 이어서 재생한다 (멈춰 있었으면 재생 버튼을 누를 때).

    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun isResumeOnStart(c: Context) = prefs(c).getBoolean("yt_resume_on_start", true)
    fun setResumeOnStart(c: Context, on: Boolean) = prefs(c).edit().putBoolean("yt_resume_on_start", on).apply()

    /** 시동 후 보여 주기만 한 마지막 곡 (재생 버튼을 누르면 이어서) */
    private var restoreShown: Track? = null
    private var restorePos = 0L
    private var restoreChecked = false

    private val saveTick = object : Runnable {
        override fun run() {
            saveLast()
            if (music?.isPlaying == true) main.postDelayed(this, 15_000)
        }
    }

    private fun saveLast() {
        val p = music ?: return
        val t = currentTrack() ?: return
        if (restoreShown != null) return
        prefs(app).edit()
            .putString("yt_last_vid", t.videoId)
            .putString("yt_last_list", queueList)
            .putLong("yt_last_pos_ms", p.currentPosition)
            .putBoolean("yt_last_playing", p.isPlaying)
            .putString("yt_last_title", t.title)
            .putString("yt_last_artist", t.artist)
            .putString("yt_last_thumb", t.thumb)
            .putLong("yt_last_at", System.currentTimeMillis())
            .apply { p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { putLong("yt_last_dur", it) } }
            .apply()
    }

    private fun maybeRestore() {
        if (restoreChecked) return
        restoreChecked = true
        if ((music?.mediaItemCount ?: 0) > 0) return
        val p = prefs(app)
        if (p.getString("pinned", null) != app.packageName) return YtLog.e("이어 듣기 안 함 (지금 플레이어가 YouTube 쪽이 아님)")
        val vid = p.getString("yt_last_vid", null)?.takeIf { it.length == 11 } ?: return
        if (System.currentTimeMillis() - p.getLong("yt_last_at", 0) > 30L * 24 * 3600_000) return
        var pos = p.getLong("yt_last_pos_ms", 0)
        val dur = p.getLong("yt_last_dur", 0)
        if (dur > 0 && pos > dur - 5000) pos = 0
        val t = Track(vid, p.getString("yt_last_title", "").orEmpty(), p.getString("yt_last_artist", "").orEmpty(),
            p.getString("yt_last_thumb", "").orEmpty(), p.getString("yt_last_list", "").orEmpty())
        val auto = p.getBoolean("yt_last_playing", false) && isResumeOnStart(app)
        YtLog.e("이어 듣기: '${t.title}' ${pos / 1000}초부터 (끄기 전 재생 중=${p.getBoolean("yt_last_playing", false)}, 자동 재생=$auto)")
        restoreShown = t
        restorePos = pos
        active = Site.MUSIC
        publishMetadata(); publishState()
        if (auto) play()
    }

    // ---------- MediaSession ----------

    private fun ensureSession() {
        if (session != null) return
        session = PlatformSession(app, "D-Player YouTube").apply {
            setCallback(object : PlatformSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_SHUFFLE) toggleShuffle()
                }
            })
        }
    }

    private fun publishMetadata() {
        val s = session ?: return
        val t = currentTrack()
        if (t == null) {
            s.setMetadata(null)
            return
        }
        val p = current()
        val dur = p?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: if (t === restoreShown) prefs(app).getLong("yt_last_dur", 0) else 0
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, t.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
            .apply { if (artFor == t.videoId) art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        if (artFor != t.videoId) loadArt(t)
    }

    /** 앨범 사진: YouTube Music 곡은 정사각형 큰 그림으로, 영상은 대표 그림 */
    private fun loadArt(t: Track) {
        artFor = t.videoId
        art = null
        val url = when {
            t.thumb.contains("googleusercontent.com") -> t.thumb.replace(Regex("=w\\d+-h\\d+[^&]*$"), "=w544-h544-l90-rj")
            else -> "https://i.ytimg.com/vi/${t.videoId}/hqdefault.jpg"
        }
        bg.execute {
            val bmp = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                try { conn.inputStream.use { com.dolphin.mplayer.ImageLoader.decodeScaled(it.readBytes()) } } finally { conn.disconnect() }
            }.getOrNull()
            main.post {
                if (artFor == t.videoId && bmp != null) {
                    art = bmp
                    publishMetadata()
                }
            }
        }
    }

    private fun publishState() {
        val s = session ?: return
        val p = current()
        val t = currentTrack()
        if (t == null) {
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build())
            YtService.update(app, this, false)
            return
        }
        val state = when {
            p == null || t === restoreShown -> PlaybackState.STATE_PAUSED
            p.isPlaying -> PlaybackState.STATE_PLAYING
            p.playbackState == Player.STATE_BUFFERING && p.playWhenReady -> PlaybackState.STATE_BUFFERING
            p.playerError != null -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_PAUSED
        }
        val pos = if (t === restoreShown) restorePos else p?.currentPosition ?: 0
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .apply {
                if (active == Site.MUSIC) addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_SHUFFLE, "셔플 (shuffle)", R.drawable.ic_shuffle).build())
            }
            .apply { p?.playerError?.let { setErrorMessage(it.errorCodeName) } }
            .setState(state, pos, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
        if (!s.isActive) s.isActive = true
        YtService.update(app, this, state == PlaybackState.STATE_PLAYING)
    }

    // ---------- 진단 ----------

    /** 진단 로그 한 줄 */
    fun describe(): String = buildString {
        append("YouTube: 로그인=${when (loggedIn) { true -> "됨(프리미엄)"; false -> if (notPremium) "프리미엄 아님" else "안 됨"; null -> "확인 중" }}")
        append(" · 재생 시작까지 $lastStartReport · 주소 받기 ${YtStreams.lastTimings}")
        cache?.let { append(" · 캐시 ${it.cacheSpace / 1_048_576}MB") }
        append(" · 목록 ${tracks.size}곡 ($queueList)")
    }

    /** 오류 설명: 감싼 예외(재생 오류 등) 안의 실제 원인 */
    private fun why(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t &&
            (t is PlaybackException || t.message.isNullOrEmpty() || t is java.util.concurrent.ExecutionException)) t = t.cause!!
        return "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(300)
    }
}
