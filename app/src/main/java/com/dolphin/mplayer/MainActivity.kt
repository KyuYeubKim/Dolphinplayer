package com.dolphin.mplayer

import android.app.Activity
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.dolphin.mplayer.adb.AdbClient
import com.dolphin.mplayer.adb.AdbDiag
import com.dolphin.mplayer.adb.AdbProvisioner
import com.dolphin.mplayer.update.AppUpdater
import com.dolphin.mplayer.local.LocalEngine
import com.dolphin.mplayer.local.VideoActivity
import com.dolphin.mplayer.youtube.YtEngine
import com.dolphin.mplayer.youtube.YtLog

/**
 * 현재 재생 중인 미디어 앱(MediaSession)을 보여주고 제어하는 컴팩트 플레이어.
 * 목록은 해당 앱의 MediaBrowser 라이브러리 → 안 되면 재생 대기열 → 둘 다 없으면 미디어 앱 목록.
 */
class MainActivity : Activity() {

    private enum class Kind {
        UP, FOLDER, PLAYABLE, QUEUE, APP, YT_PLAYLIST, YT_SONG, YT_PLAY_ALL, YT_QUEUE, PLAY_ALL, LOCAL_FOLDER, LOCAL_FILE,
        LOCAL_PLAY_ALL,
        /** YouTube Music: 칸 제목(누를 수 없음) · 검색 · 내 재생목록 · 둘러보기(앨범 등) · 재생목록 바로 재생 · 추천 다시 불러오기 */
        YT_HEADER, YT_SEARCH, YT_MYLIB, YT_BROWSE, YT_LIST, YT_RETRY,
        /** YouTube 영상: 썸네일 카드 한 줄(cards) · 위쪽 탭(검색 · 맞춤 동영상 · 구독 …) */
        YT_GRID, YT_TABS,
        /** Spotify(차량 앱 라이브러리): 검색 · 검색어로 바로 재생 */
        SP_SEARCH, SP_PLAY_QUERY, SP_RECENT,
    }

    private data class Row(
        val kind: Kind,
        val title: String,
        val subtitle: String = "",
        val id: String? = null,
        val queueId: Long = -1,
        val icon: Bitmap? = null,
        val iconUri: Uri? = null,
        val active: Boolean = false, // 지금 재생 중인 곡
        val playable: Boolean = false, // 폴더(재생목록) 자체를 재생할 수 있음
        val extra: String? = null, // YouTube Music: 곡이 속한 재생목록 id / 둘러보기 params
        val cards: List<Row> = emptyList(), // YT_GRID: 이 줄에 놓이는 영상 카드
        val badge: String = "", // 영상 카드: 썸네일 위 길이 (12:34)
    )

    /** 라이브러리 폴더 id → 그 폴더 자체를 재생할 수 있는지 (전체 재생에 사용) */
    private val folderPlayable = HashMap<String, Boolean>()

    /** 리스트 내용: 라이브러리 / 재생 목록(대기열) × 일반 앱 / YouTube Music */
    private enum class ListMode { LIBRARY, QUEUE, YOUTUBE, YT_QUEUE, LOCAL }

    /** 라이브러리 계열인가 (아니면 재생 목록 계열) */
    private fun isLibraryMode() =
        listMode == ListMode.LIBRARY || listMode == ListMode.YOUTUBE || listMode == ListMode.LOCAL


    /** USB/SD 라이브러리에서 보고 있는 폴더 (null = 저장소 목록) */
    private var localPath: String? = null
    private var storageAsked = false

    private lateinit var sessionManager: MediaSessionManager
    private lateinit var audioManager: AudioManager
    private lateinit var images: ImageLoader
    private val listener by lazy { ComponentName(this, MediaListenerService::class.java) }
    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var browser: MediaBrowser? = null
    private val browseStack = ArrayDeque<String>()
    private var listMode = ListMode.LIBRARY
    private var listOpen = false           // 평소엔 닫아서 플레이 화면을 넓게
    private var compact = false            // 분할화면: 플레이 화면과 리스트를 번갈아 표시
    private var updateChecked = false
    private var rows = emptyList<Row>()
    private var shuffleOn = false
    private var lastPackage: String? = null
    private var resumed = false
    private var sessionsRegistered = false
    private var granting = false
    private var autoGrantTried = false
    private var lastAdbLog = ""
    /** 권한 설정 중 화면이 가려졌다 돌아온 기록 (차량의 허용 창이 실제로 떴는지 판단) */
    private var grantStart = 0L
    private val grantEvents = StringBuilder()
    private var pausedDuringGrant = false
    /** 설치 후 한 번만 자동으로 ADB 권한을 묻는다 */
    private val ADB_ASKED_KEY = "adb_asked_once"

    private lateinit var setupPanel: View
    private lateinit var playerPanel: View
    private lateinit var cover: ImageView
    private lateinit var coverBackdrop: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var listHeader: TextView
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var listPane: View
    private lateinit var playerPane: View
    private lateinit var playerContent: View
    // 재생 중 차량 제어 패널 (0.61): 앨범 이미지 자리에 DolphinControl 하단바 기능
    private lateinit var carPanel: CarPanel
    private var carShown = false
    /** 재생 중 패널을 옆으로 밀어 앨범 이미지를 보는 중 (멈춰서 패널이 닫히면 풀림 → 다음 재생 땐 다시 패널) */
    private var carUserCover = false
    private var carRetryAt = 0L
    private var lastCoverDp = 260
    // 즐겨찾기 바로 재생 (0.64): 재생 앱 이름 아래 버튼 줄
    private lateinit var favRow: LinearLayout
    private lateinit var btnLibrary: View
    private lateinit var btnQueue: View
    private var ytQueueList = "" // YouTube Music 재생 목록이 속한 재생목록 id
    private lateinit var settingsPanel: SettingsPanel
    /** YouTube 로그인 창 (TV 방식 코드, 0.66) */
    private var loginDialog: AlertDialog? = null
    private var loginCancel: (() -> Unit)? = null
    /** YouTube 목록을 영상 쪽(YouTube 버튼)으로 보는 중 — 곡을 누르면 영상 전체화면 (0.66) */
    private var ytVideoMode = false
    /** YouTube 영상 첫 화면에서 고른 탭 (browse id) */
    private var ytVideoTab = "FEwhat_to_watch"
    /** 영상 카드 한 줄의 칸 수 (목록 폭으로 정함, 분할 · 전체화면이 바뀌면 다시 나눔) */
    private var ytGridCols = 0
    /**
     * YouTube Music 목록에서 들어간 화면들 (비어 있으면 첫 화면 = 검색 · 내 재생목록 · 홈 추천).
     * MYLIB = 내 재생목록 목록, PLAYLIST = 내 재생목록 하나, BROWSE = 앨범·재생목록·믹스·아티스트, SEARCH = 검색 결과(id = 검색어)
     */
    private enum class YtPageType { MYLIB, PLAYLIST, BROWSE, SEARCH }
    private data class YtPage(val type: YtPageType, val id: String, val title: String, val params: String = "")
    private val ytStack = ArrayList<YtPage>()
    private val ytTop: YtPage? get() = ytStack.lastOrNull()
    /** 홈 추천 칸 (한 번 받으면 5분 동안 바로 보여 줌) */
    private var ytHome: org.json.JSONArray? = null
    private var ytHomeAt = 0L
    private var ytHomeFailed = false
    /** 들어갔던 화면 내용 (뒤로 왔을 때 바로 보여 줌) */
    private val ytPageCache = HashMap<String, org.json.JSONObject>()
    /** 지금 보는 앨범·재생목록 화면의 재생목록 id (곡을 누르면 그 목록을 이어서 재생) */
    private var ytPageList: String? = null
    private var searchDialogOpen = false
    private lateinit var btnYtMusic: View
    private lateinit var btnSpotify: View
    private lateinit var btnLocal: View
    private lateinit var btnBt: View
    private lateinit var btnRadio: View
    private lateinit var btnFlo: View
    private lateinit var appShortcuts: LinearLayout
    private lateinit var btnYt: View
    private lateinit var sourceIcon: ImageView
    private lateinit var sourceLabel: TextView
    private lateinit var timeElapsed: TextView
    private lateinit var timeTotal: TextView
    private lateinit var setupStatus: TextView
    private lateinit var setupSpinner: ProgressBar
    private lateinit var btnAutoGrant: TextView

    private var tick = 0

    private val ticker = object : Runnable {
        override fun run() {
            renderProgress()
            if (tick++ % 2 == 0) watchOtherApps()
            main.postDelayed(this, 1000)
        }
    }

    /** YT Music 을 고른 뒤 다른 앱의 재생 상태 (새로 재생을 시작하는지 보기 위함) */
    private val otherPlaying = HashMap<String, Boolean>()

    /**
     * 플레이어 구분 키. 다른 앱은 패키지명, D-Player 안의 재생은 세션 태그로 구분한다:
     * YouTube = 내 패키지명, USB/SD = LOCAL_KEY.
     */
    private fun key(c: MediaController): String = when {
        c.packageName == packageName && isLocalSession(c) -> LOCAL_KEY
        c.packageName == FLO_BYD -> FLO_PKG // 차량 FLO 화면 앱이 세션을 내도 FLO 하나로
        else -> c.packageName
    }

    /** USB/SD 재생 세션인가. MediaController.getTag() 는 Android 11+ 에만 있어 10(차량)에서는 세션 extras 표시로 확인 */
    private fun isLocalSession(c: MediaController): Boolean =
        if (Build.VERSION.SDK_INT >= 30) c.tag == LocalEngine.TAG
        else runCatching { c.extras?.getBoolean(LocalEngine.EXTRA_LOCAL) }.getOrNull() == true

    private fun rememberOtherApps() {
        otherPlaying.clear()
        activeSessions().filter { key(it) != pinned() }
            .forEach { otherPlaying[key(it)] = it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }

    /** 버튼/목록으로 고른(고정한) 플레이어 앱. null = 재생 중인 앱을 자동으로 따름 */
    private fun pinned(): String? = prefs.getString("pinned", null)

    private fun pin(pkg: String?) {
        val before = pinned()
        prefs.edit().putString("pinned", pkg).apply { if (pkg != null) putString("package", pkg) }.apply()
        if (before != pkg) YtLog.e("플레이어 바뀜: ${before ?: "-"} → ${pkg ?: "-"}")
        if (before != pkg) rememberVolume(before, pkg)
        // (0.51 에서 다른 플레이어로 바꾸면 차량 Spotify 앱 연결을 풀었는데, 그 순간 차량 Spotify 가 재생을 멈출 수 있어 0.56 에서 뺌.
        //  연결은 Spotify 를 고르거나 마지막 플레이어가 Spotify 일 때만 맺으므로 Spotify 를 안 쓰는 날엔 원래 안 맺음)
    }

    /**
     * 앱별 음량 기억 (0.64): 플레이어를 바꿀 때 지금 음량을 이전 앱 것으로 저장하고, 새 앱에서 쓰던 음량이 있으면 그 음량으로.
     * 미디어 음량(STREAM_MUSIC)만 바꾼다. 처음 쓰는 앱은 저장된 값이 없어 그대로.
     */
    private fun rememberVolume(from: String?, to: String?) {
        if (!VolumeMemory.isEnabled(this) || !::audioManager.isInitialized) return
        runCatching {
            val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (from != null) prefs.edit().putInt("vol:$from", now).apply()
            val saved = if (to != null) prefs.getInt("vol:$to", -1) else -1
            if (saved < 0 || saved == now) return
            val v = saved.coerceAtMost(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
            YtLog.e("앱별 음량: $now → $v (${appLabel(to!!)})")
        }
    }

    /** 플레이어를 고정한 상태에서 다른 앱이 새로 재생을 시작하면 플레이어·라이브러리를 그 앱으로 */
    private fun watchOtherApps() {
        val pinnedPkg = pinned() ?: return
        for (c in activeSessions()) {
            if (key(c) == pinnedPkg) continue
            val now = c.playbackState?.state == PlaybackState.STATE_PLAYING
            val before = otherPlaying.put(key(c), now)
            if (now && before == false) { // 멈춤 → 재생으로 바뀐 경우만 (처음 보는 상태는 기록만)
                pin(key(c))
                bind(c)
                if (isListVisible()) {
                    followPlayer()
                    loadList()
                }
                return
            }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickSession(it) }

    /** 마지막으로 본 '지금 곡' 재생 목록 번호 (곡이 바뀌면 목록 스크롤) */
    private var lastActiveQueueId = -1L
    /** Spotify 재생 상태 기록용 (멈춤 진단) */
    private var spWasPlaying = false
    private var spPlayingSince = 0L
    private var spStoppedAt = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            renderMetadata()
            if (listOpen && listMode == ListMode.YT_QUEUE) main.postDelayed({ YtEngine.requestQueue(this@MainActivity) }, 800)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (checkOtherAppPlaying()) {
                updatePlayerChips()
                if (isListVisible() && listMode == ListMode.YOUTUBE) {
                    followPlayer()
                    loadList()
                }
            }
            renderState()
            // Spotify 가 재생 중에 멈추면 기록 (진단: 재생 몇 초 뒤 멈춘 건지 · 그때 소리를 누가 가져갔는지 같이 보려고, 0.56)
            val c = controller
            val nowPlaying = state?.state == PlaybackState.STATE_PLAYING
            if (c != null && c.packageName.startsWith("com.spotify")) {
                if (nowPlaying && !spWasPlaying) {
                    spPlayingSince = SystemClock.elapsedRealtime()
                    // 멈췄다가 다시 나오면 몇 초 만인지 (내비 안내 뒤 저절로 이어지는지 확인용)
                    if (spStoppedAt > 0) YtLog.e("Spotify 다시 재생: 멈춘 지 ${(spPlayingSince - spStoppedAt) / 1000}초 뒤")
                    spStoppedAt = 0
                }
                if (!nowPlaying && spWasPlaying) {
                    spStoppedAt = SystemClock.elapsedRealtime()
                    YtLog.e("Spotify 멈춤: 상태=${state?.state} 재생 ${
                        (spStoppedAt - spPlayingSince) / 1000}초 뒤 · 오류='${state?.errorMessage ?: ""}'")
                }
                spWasPlaying = nowPlaying
            }
            // 재생 목록을 열어 둔 채 곡이 바뀌면 지금 곡으로 따라감 (0.55)
            val aq = state?.activeQueueItemId ?: -1L
            if (aq != lastActiveQueueId) {
                lastActiveQueueId = aq
                if (listOpen && listMode == ListMode.QUEUE) {
                    adapter.notifyDataSetChanged()
                    val i = rows.indexOfFirst { it.kind == Kind.QUEUE && it.queueId == aq }
                    if (i >= 0) listView.post { listView.setSelection(maxOf(0, i - 1)) }
                }
            }
            if (listMode == ListMode.QUEUE) adapter.notifyDataSetChanged()
        }
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            if (listOpen && listMode == ListMode.QUEUE) showQueue()
        }
        override fun onSessionDestroyed() = pickSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        com.dolphin.mplayer.youtube.YtLog.init(this) // 진단 로그에 지난 기록도 넣을 수 있게
        com.dolphin.mplayer.youtube.YtLog.e("D-Player 화면 열림 (" + AppUpdater.currentVersion(this) + ")")
        sessionManager = getSystemService(MediaSessionManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        images = ImageLoader(this)

        setupPanel = findViewById(R.id.setupPanel)
        playerPanel = findViewById(R.id.playerPanel)
        cover = findViewById(R.id.cover)
        coverBackdrop = findViewById(R.id.coverBackdrop)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        progress = findViewById(R.id.progress)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnPlay = findViewById(R.id.btnPlay)
        listHeader = findViewById(R.id.listHeader)
        listView = findViewById(R.id.library)
        emptyText = findViewById(R.id.emptyText)
        sourceIcon = findViewById(R.id.sourceIcon)
        sourceLabel = findViewById(R.id.sourceLabel)
        timeElapsed = findViewById(R.id.timeElapsed)
        timeTotal = findViewById(R.id.timeTotal)
        setupStatus = findViewById(R.id.setupStatus)
        setupSpinner = findViewById(R.id.setupSpinner)
        btnAutoGrant = findViewById(R.id.btnAutoGrant)
        title.isSelected = true // 긴 제목은 흐르게 (두 번만 — 계속 흐르면 매 프레임 다시 그려 CPU 를 씀, 0.47 측정 본체 50%→19%)

        btnAutoGrant.setOnClickListener { autoGrant() }
        findViewById<View>(R.id.btnOpenSettings).setOnClickListener {
            // 알림 접근이 이미 있으면 이 버튼은 '나중에' (ADB 권한 설정만 하던 중)
            if (hasListenerAccess()) {
                if (!granting) {
                    setupPanel.visibility = View.GONE
                    playerPanel.visibility = View.VISIBLE
                }
            } else openListenerSettings()
        }
        setupStatus.setOnClickListener { showAdbLog() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnNext).setOnClickListener {
            controller?.transportControls?.skipToNext()
                ?: if (isSpotifyPlayer()) withSpotifyController { it.transportControls.skipToNext() }
                else if (pinned() == BT_PKG) withBtController { it.transportControls.skipToNext() }
                else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener {
            controller?.transportControls?.skipToPrevious()
                ?: if (isSpotifyPlayer()) withSpotifyController { it.transportControls.skipToPrevious() }
                else if (pinned() == BT_PKG) withBtController { it.transportControls.skipToPrevious() }
                else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        findViewById<View>(R.id.btnStop).setOnClickListener { stop() }
        findViewById<View>(R.id.btnSource).setOnClickListener { showSources() }
        btnShuffle.setOnClickListener { toggleShuffle() }
        listPane = findViewById(R.id.listPane)
        playerPane = findViewById(R.id.playerPane)
        playerContent = findViewById(R.id.playerContent)
        carPanel = CarPanel(this, onUnavailable = {
            // DolphinControl 이 없거나 예전 버전 · 고른 기능 없음 → 앨범 이미지로 (30초 뒤 다시 시도)
            carRetryAt = SystemClock.elapsedRealtime() + 30_000
            setCarShown(false)
        }, onSwipe = {
            carUserCover = true
            updateCarPanel()
        })
        (playerContent as LinearLayout).addView(carPanel.view,
            (playerContent as LinearLayout).indexOfChild(cover) + 1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        carPanel.view.visibility = View.GONE
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (playerContent as LinearLayout).addView(favRow,
            (playerContent as LinearLayout).indexOfChild(findViewById(R.id.btnSource)) + 1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
        renderFavorites()
        btnLibrary = findViewById(R.id.btnLibrary)
        btnLibrary.setOnClickListener { toggleList(library = true) }
        btnQueue = findViewById(R.id.btnQueue)
        btnQueue.setOnClickListener { toggleList(library = false) }
        findViewById<View>(R.id.btnListClose).setOnClickListener { upOrCloseList() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        settingsPanel = SettingsPanel(this, settingsActions)
        settingsPanel.onBetaChanged = { updateRailButtons() }
        btnYtMusic = findViewById(R.id.btnYtMusic)
        btnYt = findViewById(R.id.btnYt)
        btnYtMusic.setOnClickListener { selectYtMusic() }
        btnSpotify = findViewById(R.id.btnSpotify)
        btnSpotify.setOnClickListener { selectSpotify() }
        btnLocal = findViewById(R.id.btnLocal)
        btnLocal.setOnClickListener { selectLocal() }
        btnBt = findViewById(R.id.btnBt)
        btnBt.setOnClickListener { selectBluetooth() }
        btnRadio = findViewById(R.id.btnRadio)
        btnRadio.setOnClickListener { selectRadio() }
        btnFlo = findViewById(R.id.btnFlo)
        btnFlo.setOnClickListener { selectFlo() }
        playerIcon(FLO_PKG)?.let { (btnFlo as ImageButton).setImageDrawable(it) }
        updateRailButtons()
        appShortcuts = findViewById(R.id.appShortcuts)
        refreshShortcuts()
        btnYt.setOnClickListener { openYtVideo() }
        emptyText.setOnClickListener { onEmptyClick() }
        YtEngine.listener = { onYtMessage(it) }
        // 이용 안내에 동의한 뒤에만 재생 엔진 · 이어 듣기를 시작 (설치 · 업데이트 뒤 처음이면 동의 창에서 [동의하고 시작] 때) — 0.58
        if (termsAgreed()) startPlayers()
        applyLayout()
        // 화면 분할은 DolphinControl 이 맡는다 — D-Player 의 분할 기능은 0.52 에서 제거 (남은 설정 정리)
        runCatching { deleteSharedPreferences("split") }
        listHeader.setOnClickListener { toggleListMode() }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> onRowLongClick(rows[pos]) }
        // 목록 폭이 바뀌면(분할 ↔ 전체화면) 영상 카드를 새 칸 수로 다시 나눔
        listView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            if (rows.any { it.kind == Kind.YT_GRID } && gridCols(r - l) != ytGridCols) listView.post {
                setRows(toGrid(rows.flatMap { if (it.kind == Kind.YT_GRID) it.cards else listOf(it) }), emptyText.text.toString())
            }
        }
    }

    // ---- 즐겨찾기 바로 재생 (0.64) ----

    /** 즐겨찾기에 넣을 수 있는 줄이면 그 항목 (재생목록 · 재생할 수 있는 폴더 · USB 폴더) */
    private fun favFor(row: Row): Favorites.Fav? {
        val id = row.id ?: return null
        return when (row.kind) {
            Kind.YT_PLAYLIST, Kind.YT_LIST -> Favorites.Fav("yt", id, row.title)
            Kind.FOLDER -> if (row.playable && isSpotifyPlayer()) Favorites.Fav("sp", id, row.title) else null
            Kind.PLAYABLE -> if (isSpotifyPlayer()) Favorites.Fav("sp", id, row.title) else null
            Kind.LOCAL_FOLDER -> Favorites.Fav("local", id, row.title)
            else -> null
        }
    }

    /** 라이브러리 줄을 길게 누르면 즐겨찾기에 넣기 / 빼기 */
    private fun onRowLongClick(row: Row): Boolean {
        val f = favFor(row) ?: return false
        resetAutoClose()
        if (Favorites.contains(this, f.src, f.id)) {
            Favorites.remove(this, f)
            toast(getString(R.string.fav_removed, f.title))
        } else if (Favorites.add(this, f)) {
            toast(getString(R.string.fav_added, f.title))
        } else {
            toast(getString(R.string.fav_full, Favorites.MAX))
        }
        renderFavorites()
        return true
    }

    /** 재생 화면의 즐겨찾기 버튼 (없으면 줄을 숨김) — 누르면 재생, 길게 누르면 빼기 */
    private fun renderFavorites() {
        if (!::favRow.isInitialized) return
        val favs = Favorites.list(this)
        favRow.removeAllViews()
        favRow.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
        favs.forEachIndexed { i, f ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_rail_button)
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = f.title
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(when (f.src) {
                        "yt" -> R.drawable.ic_logo_ytmusic
                        "local" -> R.drawable.ic_logo_usb
                        else -> R.drawable.ic_logo_spotify
                    })
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(TextView(this@MainActivity).apply {
                    text = f.title
                    textSize = 13f
                    setTextColor(getColor(R.color.chip_text))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) })
                setOnClickListener { playFavorite(f) }
                setOnLongClickListener {
                    Favorites.remove(this@MainActivity, f)
                    toast(getString(R.string.fav_removed, f.title))
                    renderFavorites()
                    true
                }
            }
            favRow.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { if (i > 0) marginStart = dp(8) })
        }
        if (::playerPane.isInitialized) playerPane.post { fitPlayerHeight(lastCoverDp) }
    }

    /** 즐겨찾기 재생: 그 플레이어로 바꾸고 바로 재생 (Spotify · USB 는 전체 재생과 같이 셔플, YouTube Music 은 순서대로) */
    private fun playFavorite(f: Favorites.Fav) {
        YtLog.e("즐겨찾기 재생: ${f.src} · ${f.title}")
        when (f.src) {
            "yt" -> {
                prefs.edit().putBoolean("yt_used", true).apply()
                pin(packageName)
                YtEngine.start(this)
                rememberOtherApps()
                YtEngine.playPlaylist(this, f.id)
                pickSession(force = true)
            }
            "sp", "spuri" -> {
                val pkg = spotifyPackage() ?: return toast(getString(R.string.spotify_missing))
                YtEngine.quiet()
                bindSpotifyWrapper()
                pin(pkg)
                rememberOtherApps()
                if (f.src == "spuri") spotifyPlay(f.id, shuffle = true)
                else withSpotifyController { c ->
                    setShuffleOn(c)
                    c.transportControls.playFromMediaId(f.id, null)
                    main.postDelayed({ setShuffleOn(controller ?: c) }, 2000)
                    shuffleOn = true
                    btnShuffle.isActivated = true
                    pickSession(force = true)
                }
            }
            "local" -> {
                YtEngine.quiet()
                pin(LOCAL_KEY)
                rememberOtherApps()
                LocalEngine.scan(this) {
                    main.post {
                        val songs = LocalEngine.audioUnder(f.id)
                        if (songs.isEmpty()) return@post toast(getString(R.string.fav_local_missing, f.title))
                        LocalEngine.play(this, songs, songs.indices.random(), shuffle = true)
                        shuffleOn = true
                        btnShuffle.isActivated = true
                        main.postDelayed({ pickSession(force = true) }, 500)
                    }
                }
            }
        }
        updatePlayerChips()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        settingsPanel.refresh() // 분할 ↔ 전체화면 폭에 맞춰 다시 그림
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyLayout() // 분할 칸(DolphinControl) ↔ 전체화면에 맞춰 화면 배치만 바꿈
    }

    // ---- 레이아웃: 전체화면 / 분할화면 ----

    /** 분할화면(멀티윈도우)이거나 창이 좁으면 컴팩트: 플레이 화면 또는 리스트 중 하나만 표시 */
    private fun isCompactWindow(): Boolean {
        val cfg = resources.configuration
        return isInMultiWindowMode || cfg.screenWidthDp < 720 || cfg.screenHeightDp < 400
    }

    private fun applyLayout() {
        val wasCompact = compact
        compact = isCompactWindow()
        if (compact != wasCompact && listOpen) {
            // 전체화면 ↔ 분할화면 전환 시 리스트는 닫고 플레이 화면부터
            listOpen = false
            disconnectBrowser()
        }
        val cfg = resources.configuration
        val showList = listOpen
        listPane.visibility = if (showList) View.VISIBLE else View.GONE
        // 분할화면에서 리스트를 열면 플레이 화면 자리에 리스트를 띄움
        playerPane.visibility = if (compact && showList) View.GONE else View.VISIBLE
        btnLibrary.isActivated = listOpen && isLibraryMode()
        btnQueue.isActivated = listOpen && !isLibraryMode()

        // 플레이 화면 폭: 창(또는 왼쪽 버튼 줄·리스트를 뺀 영역)에 맞추되 너무 넓게 퍼지지 않게
        val availDp = cfg.screenWidthDp - RAIL_DP
        val paneDp = if (showList && !compact) availDp * 5 / 11 else availDp
        playerContent.layoutParams = playerContent.layoutParams.apply { width = dp(minOf(560, paneDp - 24)) }
        val coverDp = minOf(260, (cfg.screenHeightDp * 0.4).toInt(), paneDp - 64).coerceAtLeast(96)
        cover.layoutParams = cover.layoutParams.apply { width = dp(coverDp); height = dp(coverDp) }
        lastCoverDp = coverDp
        playerPane.post { fitPlayerHeight(coverDp) }

        // 좁은 창(7:3 의 오른쪽 등)에서도 재생 버튼 아이콘이 작아지지 않게 여백을 폭에 맞춤
        // 컨트롤 줄 실제 폭 = 플레이 화면 폭 - 좌우 여백(16dp × 2), 버튼 4개(1) + 재생(1.3)
        // 차량 제어 패널이 보이면 재생 버튼 · 재생 목록/라이브러리를 조금 작게 → 패널 자리 확보 (0.63)
        val small = carShown
        val rowDp = minOf(560, paneDp - 24) - 32
        val btnDp = rowDp / 5.3f
        val ctrlH = if (small) 48 else 64
        val pad = if (small) dp(13) else dp(((minOf(64f, btnDp) - 30) / 2).toInt().coerceIn(4, 18))
        listOf(R.id.btnShuffle, R.id.btnPrev, R.id.btnNext, R.id.btnStop).forEach {
            val b = findViewById<View>(it)
            b.setPadding(pad, pad, pad, pad)
            b.layoutParams = b.layoutParams.apply { height = dp(ctrlH) }
        }
        val playDp = if (small) 54 else minOf(72, (btnDp * 1.3f).toInt() - 4).coerceAtLeast(52)
        btnPlay.layoutParams = btnPlay.layoutParams.apply { width = dp(playDp); height = dp(playDp) }
        btnPlay.setPadding(dp(playDp / 4), dp(playDp / 4), dp(playDp / 4), dp(playDp / 4))
        (btnPlay.parent as View).layoutParams = (btnPlay.parent as View).layoutParams.apply { height = dp(if (small) 58 else 80) }
        (findViewById<View>(R.id.ctrlRow).layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (small) 4 else 12)
        (findViewById<View>(R.id.listRow).layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (small) 8 else 20)

        // 재생 목록 / 라이브러리: 아주 좁으면 아이콘을 글자 위로 (글자가 잘리지 않게)
        val stacked = rowDp < 300
        listOf(btnQueue, btnLibrary).forEach { b ->
            (b as LinearLayout).orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            b.layoutParams = b.layoutParams.apply {
                height = dp(if (stacked) (if (small) 60 else 76) else (if (small) 44 else 60))
            }
            b.getChildAt(0).layoutParams = b.getChildAt(0).layoutParams.apply {
                width = dp(if (small) 22 else 28); height = width
            }
            (b.getChildAt(1) as TextView).textSize = if (small) 14f else 16f
            (b.getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = if (stacked) 0 else dp(8)
                topMargin = if (stacked) dp(4) else 0
            }
        }
        updateCarPanel()
    }

    // ---- 재생 중 차량 제어 패널 (0.61) ----

    private val carHide = Runnable {
        setCarShown(false)
        carUserCover = false
    }

    /** 패널이 보이면 제목·가수(왼쪽)와 짧은 진행바(오른쪽)를 한 줄로 → 패널 자리를 더 넓게 (0.62) */
    private fun compactInfo(on: Boolean) {
        val row = findViewById<LinearLayout>(R.id.infoRow)
        val titleBox = findViewById<View>(R.id.titleBox)
        val progressBox = findViewById<View>(R.id.progressBox)
        val artist = findViewById<TextView>(R.id.artist)
        row.orientation = if (on) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (on) 10 else 20)
        titleBox.layoutParams = if (on) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        progressBox.layoutParams = if (on) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            .apply { marginStart = dp(12) }
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(20) }
        val g = if (on) android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.CENTER
        title.gravity = g
        artist.gravity = g
        title.textSize = if (on) 18f else 24f
        artist.textSize = if (on) 13f else 16f
        (artist.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (on) 1 else 4)
        row.requestLayout()
    }

    /** 설정에서 켰고 · 재생 중이고 · 플레이 화면이 보일 때만 앨범 이미지 대신 패널 (멈추면 1.5초 뒤 앨범 이미지 — 곡 넘김 때 깜빡이지 않게) */
    private fun updateCarPanel() {
        if (!::carPanel.isInitialized) return
        val st = controller?.playbackState?.state
        val playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        val allowed = resumed && CarPanel.isEnabled(this) && playerPane.visibility == View.VISIBLE &&
            SystemClock.elapsedRealtime() >= carRetryAt
        main.removeCallbacks(carHide)
        when {
            allowed && playing -> setCarShown(!carUserCover)
            allowed && (carShown || carUserCover) -> main.postDelayed(carHide, 1500)
            else -> setCarShown(false)
        }
    }

    private fun setCarShown(on: Boolean) {
        if (on == carShown) return
        carShown = on
        carPanel.view.visibility = if (on) View.VISIBLE else View.GONE
        if (on) carPanel.show() else carPanel.hide()
        compactInfo(on)
        applyLayout() // 버튼 크기 · 앨범 이미지 크기 다시 (안에서 부르는 updateCarPanel 은 상태가 같아 그대로)
    }

    /**
     * 화면이 낮은 차량에서 아래쪽 버튼(재생 목록 / 라이브러리)이 잘리지 않게
     * 실제 높이를 재서 남는 만큼만 앨범 이미지를 키운다. 너무 작아지면 흐린 배경 이미지로 바꾼다.
     */
    private fun fitPlayerHeight(maxCoverDp: Int) {
        if (carShown) {
            // 차량 제어 패널이 앨범 이미지 자리를 씀 (남는 높이를 모두 차지)
            cover.visibility = View.GONE
            coverBackdrop.visibility = View.GONE
            return
        }
        val avail = playerPane.height
        if (avail <= 0 || playerContent.width <= 0) return
        cover.visibility = View.VISIBLE
        coverBackdrop.visibility = View.GONE
        cover.layoutParams = cover.layoutParams.apply { width = dp(maxCoverDp); height = dp(maxCoverDp) }
        playerContent.measure(
            View.MeasureSpec.makeMeasureSpec(playerContent.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val overflow = playerContent.measuredHeight - avail + dp(12)
        if (overflow <= 0) return
        val size = dp(maxCoverDp) - overflow
        if (size < dp(72)) {
            // 자리가 없으면 앨범 이미지를 재생 정보 뒤에 흐린 배경으로 깐다
            cover.visibility = View.GONE
            coverBackdrop.visibility = View.VISIBLE
        } else {
            cover.layoutParams = cover.layoutParams.apply { width = size; height = size }
        }
    }

    /**
     * 라이브러리 폴더(재생목록) 전체 재생 = 셔플.
     * 폴더 자체를 재생할 수 있으면 폴더를, 아니면 그 안의 무작위 곡을 재생하고 셔플을 '켜기'로 지정한다
     * (켜기/끄기 전환이 아니라 지정이라 이미 켜져 있어도 꺼지지 않음).
     */
    private fun playFolderShuffled(folderId: String) {
        if (isSpotifyPlayer() && controller?.packageName != spotifyPackage()) {
            return withSpotifyController { playFolderShuffledWith(it, folderId) }
        }
        val c = controller ?: return
        playFolderShuffledWith(c, folderId)
    }

    private fun playFolderShuffledWith(c: MediaController, folderId: String) {
        setShuffleOn(c)
        val target = if (folderPlayable[folderId] == true) folderId
        else rows.filter { it.kind == Kind.PLAYABLE && it.id != null }.randomOrNull()?.id ?: folderId
        c.transportControls.playFromMediaId(target, null)
        main.postDelayed({ setShuffleOn(controller ?: c) }, 2000) // 재생이 시작된 뒤 한 번 더
        shuffleOn = true
        btnShuffle.isActivated = true
    }

    /** 셔플 켜기 (AndroidX MediaSessionCompat/Media3 표준 명령: 모드 지정) */
    private fun setShuffleOn(c: MediaController) {
        runCatching {
            c.transportControls.sendCustomAction(COMPAT_SET_SHUFFLE, Bundle().apply { putInt(COMPAT_SHUFFLE_ARG, 1) })
        }
    }

    // ---- 라이브러리 자동 닫기: 일정 시간 건드리지 않으면 재생 화면으로 ----


    private fun autoCloseSec(): Int = prefs.getInt("auto_close_sec", 10)

    private val autoClose = Runnable {
        // 검색 창에서 글자를 치는 동안은 닫지 않음 (닫히면 검색 결과를 보여 줄 곳이 없어짐)
        if (listOpen && loginDialog == null && !searchDialogOpen) setListOpen(false)
    }

    private fun resetAutoClose() {
        main.removeCallbacks(autoClose)
        if (listOpen && resumed) main.postDelayed(autoClose, autoCloseSec() * 1000L)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (listOpen) resetAutoClose() // 화면을 만지면 다시 셈
        if (carUserCover) coverSwipe(ev)
        return super.dispatchTouchEvent(ev)
    }

    private var coverSwipeX = -1f
    private var coverSwipeY = 0f

    /**
     * 패널을 밀어 앨범 이미지를 보는 중: 앨범 이미지 줄 전체 폭 ~ 제목까지 어디서든 좌우로 밀면 다시 패널 (0.72).
     * 0.71 은 앨범 이미지 위에서만 받아, 칸 가운데 작은 이미지 옆 검은 곳에서 밀면 반응하지 않았다. 다른 동작은 그대로 둠 (가로채지 않음).
     */
    private fun coverSwipe(ev: android.view.MotionEvent) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                coverSwipeX = -1f
                if (playerPane.visibility != View.VISIBLE) return
                val pane = IntArray(2).also { playerPane.getLocationOnScreen(it) }
                val top = IntArray(2).also { cover.getLocationOnScreen(it) }[1]
                val title = findViewById<View>(R.id.titleBox)
                val bottom = IntArray(2).also { title.getLocationOnScreen(it) }[1] + title.height
                if (ev.rawX >= pane[0] && ev.rawX <= pane[0] + playerPane.width && ev.rawY >= top && ev.rawY <= bottom) {
                    coverSwipeX = ev.rawX
                    coverSwipeY = ev.rawY
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (coverSwipeX < 0) return
                val dx = kotlin.math.abs(ev.rawX - coverSwipeX)
                coverSwipeX = -1f
                if (dx > dp(60) && dx > kotlin.math.abs(ev.rawY - coverSwipeY) * 1.5f) {
                    carUserCover = false
                    updateCarPanel()
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> coverSwipeX = -1f
        }
    }

    /** 리스트 ← / Back: 폴더(재생목록) 안이면 한 단계 위로, 맨 위면 리스트 닫기 */
    private fun upOrCloseList() {
        when {
            listMode == ListMode.YOUTUBE && ytStack.isNotEmpty() -> {
                ytStack.removeAt(ytStack.lastIndex)
                showYtLibrary()
            }
            listMode == ListMode.LOCAL && localPath != null -> localUp()
            listMode == ListMode.LIBRARY && browser != null && spSearchQuery != null -> closeSpotifySearch()
            listMode == ListMode.LIBRARY && browsingRecent && browseStack.size <= 1 && isSpotifyPlayer() -> loadList()
            listMode == ListMode.LIBRARY && browser != null && browseStack.size > 1 -> goUp()
            else -> setListOpen(false)
        }
    }

    /** 왼쪽 버튼: 라이브러리 / 재생 목록. 같은 버튼을 다시 누르면 닫고, 다른 버튼이면 내용만 바꾼다 */
    private fun toggleList(library: Boolean) {
        if (listOpen && isLibraryMode() == library) return setListOpen(false)
        listMode = if (library) ListMode.LIBRARY else ListMode.QUEUE
        ytStack.clear()
        ytVideoMode = false
        if (!listOpen) setListOpen(true) else {
            followPlayer()
            applyLayout()
            loadList()
        }
    }

    private fun setListOpen(open: Boolean) {
        listOpen = open
        resetAutoClose()
        if (open) followPlayer()
        applyLayout()
        if (open) loadList() else disconnectBrowser()
    }

    // ---- 차량 Spotify 깨우기 ----
    // 시동 직후에는 차량 Spotify 화면 앱(com.vivid.music.byd)이 아직 안 떠 있어
    // Spotify 엔진이 재생 · 라이브러리 요청을 받지 않는다 (앱을 한 번 열면 해결되던 증상).
    // 1단계: 화면 앱의 공개 서비스에 연결해 앱을 화면 없이 띄운다.
    // 2단계: 그래도 준비되지 않으면 화면 앱을 잠깐 열었다가 D-Player 로 돌아온다.

    private var spWrapperBound = false
    private val spWrapperConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    private fun bindSpotifyWrapper() {
        if (spWrapperBound || !isInstalled(SPOTIFY_BYD)) return
        spWrapperBound = runCatching {
            bindService(Intent(SPOTIFY_BYD_SERVICE).setPackage(SPOTIFY_BYD), spWrapperConn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    /** Spotify 엔진이 준비됐는지 (재생 정보 세션이 있는지) */
    private fun spotifySessionReady(): Boolean {
        val pkg = spotifyPackage() ?: return false
        return activeSessions().any { it.packageName == pkg }
    }

    private var spLaunching = false
    private var spLastLaunch = 0L

    /**
     * 2단계: 차량 Spotify 화면을 잠깐 열고, Spotify 세션이 생기면(최대 8초) D-Player 를 다시 앞으로.
     * then: 준비된 뒤(또는 시간 초과 뒤) 이어서 할 일
     */
    private fun launchSpotifyBriefly(then: () -> Unit) {
        val launch = packageManager.getLaunchIntentForPackage(SPOTIFY_BYD)
            ?: spotifyPackage()?.let { packageManager.getLaunchIntentForPackage(it) }
        // 화면 앱 열기는 1분에 한 번만 (준비가 안 되는 기기에서 반복해서 열리지 않게)
        if (launch == null || spLaunching || SystemClock.elapsedRealtime() - spLastLaunch < 60_000) return then()
        spLaunching = true
        spLastLaunch = SystemClock.elapsedRealtime()
        toast(getString(R.string.sp_waking))
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val start = SystemClock.elapsedRealtime()
        fun check() {
            val ready = spotifySessionReady()
            if (!ready && SystemClock.elapsedRealtime() - start < 8000) {
                main.postDelayed({ check() }, 500)
                return
            }
            // Spotify 화면이 자리를 잡을 시간을 조금 준 뒤 D-Player 로 복귀
            main.postDelayed({
                spLaunching = false
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                main.postDelayed({ then() }, 800)
            }, if (ready) 1200 else 0)
        }
        main.postDelayed({ check() }, 800)
    }

    // ---- 재생 전 Spotify 에 명령 보내기 ----
    // Spotify 는 한 번 재생하기 전까지 재생 세션을 내보내지 않아(시동 직후) 세션으로 보낸 명령이 버려진다.
    // Android Auto 처럼 라이브러리 연결(MediaBrowser)이 주는 세션 열쇠로 컨트롤러를 만들어 명령을 보낸다.

    private var spCtlBrowser: MediaBrowser? = null
    private var spCtlPending = mutableListOf<(MediaController) -> Unit>()

    /** Spotify 컨트롤러로 cb 실행: 재생 세션 → 열려 있는 라이브러리 연결 → 새 연결 순으로 */
    private fun withSpotifyController(cb: (MediaController) -> Unit) {
        val pkg = spotifyPackage() ?: return toast(getString(R.string.spotify_missing))
        activeSessions().firstOrNull { it.packageName == pkg }?.let { return cb(it) }
        browser?.takeIf { it.isConnected && it.serviceComponent.packageName == pkg }
            ?.let { b -> return cb(MediaController(this, b.sessionToken)) }
        spCtlBrowser?.takeIf { it.isConnected }?.let { b -> return cb(MediaController(this, b.sessionToken)) }
        spCtlPending.add(cb)
        if (spCtlBrowser != null) return // 연결 중
        bindSpotifyWrapper()
        val svc = packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return spotifyCtlFailed()
        lateinit var b: MediaBrowser
        b = MediaBrowser(this, ComponentName(pkg, svc.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val c = MediaController(this@MainActivity, b.sessionToken)
                val todo = spCtlPending.toList()
                spCtlPending.clear()
                todo.forEach { it(c) }
            }
            override fun onConnectionFailed() = spotifyCtlFailed()
            override fun onConnectionSuspended() {
                spCtlBrowser = null
            }
        }, null)
        spCtlBrowser = b
        runCatching { b.connect() }.onFailure { spotifyCtlFailed() }
    }

    private var spCtlRetried = false

    private fun spotifyCtlFailed() {
        runCatching { spCtlBrowser?.disconnect() }
        spCtlBrowser = null
        if (spCtlRetried) {
            spCtlRetried = false
            spCtlPending.clear()
            return toast(getString(R.string.sp_not_ready))
        }
        // 준비가 안 됨 → 차량 Spotify 를 깨운 뒤 한 번 더
        spCtlRetried = true
        val todo = spCtlPending.toList()
        spCtlPending.clear()
        launchSpotifyBriefly { todo.forEach { withSpotifyController(it) } }
    }

    override fun onDestroy() {
        runCatching { spCtlBrowser?.disconnect() }
        spCtlBrowser = null
        runCatching { btBrowser?.disconnect() }
        runCatching { spSearchBrowser?.disconnect() }
        spSearchBrowser = null
        btBrowser = null
        btCtl = null
        if (spWrapperBound) runCatching { unbindService(spWrapperConn) }
        spWrapperBound = false
        super.onDestroy()
        loginCancel?.invoke()
        if (YtEngine.listener != null) YtEngine.listener = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.isShowing) return settingsPanel.hide()
        if (listOpen) return upOrCloseList()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- YouTube / YouTube Music (앱 플레이어, 0.66) ----

    /**
     * 플레이어로 YT Music 선택: 라이브러리에 내 재생목록을 보여준다 (재생은 뒤에서, 표시는 플레이 화면).
     * 전체화면이면 라이브러리를 바로 열고, 분할화면이면 플레이 화면을 유지한다.
     */
    private fun selectYtMusic() {
        prefs.edit().putBoolean("yt_used", true).apply()
        pin(packageName)
        YtEngine.start(this)
        YtEngine.selectMusic(this) // YouTube 영상을 보고 와도 듣던 곡으로
        rememberOtherApps() // 지금 재생 중인 다른 앱은 무시, 이후 새로 재생을 시작하면 그 앱으로
        pickSession(force = true) // 플레이 화면을 YouTube 쪽으로 (아직 없으면 빈 화면)
        listMode = ListMode.YOUTUBE
        ytStack.clear()
        ytVideoMode = false
        if (!listOpen && !compact) {
            listOpen = true
            applyLayout()
            resetAutoClose()
        }
        if (listOpen) loadList()
        updatePlayerChips()
    }

    /**
     * YouTube 버튼: 영상 목록(검색 · 맞춤 동영상 · 구독 · 기록 · 나중에 볼 동영상). 영상을 누르면 전체화면으로 재생 (0.66 — 웹 대신 앱 플레이어).
     * 플레이어(고른 앱)는 바꾸지 않는다 — 영상은 잠깐 보는 것.
     */
    private fun openYtVideo() {
        YtEngine.start(this)
        listMode = ListMode.YOUTUBE
        ytStack.clear()
        ytVideoMode = true
        if (!listOpen) setListOpen(true) else loadList()
    }

    /**
     * 플레이어로 Spotify 선택. Spotify 재생 정보가 있으면 바로 보여주고,
     * 아직 없으면(앱이 꺼져 있으면) Spotify 를 열어 재생을 시작하게 한다 → 재생되면 자동으로 플레이 화면에 표시.
     */
    private fun selectSpotify() {
        val pkg = spotifyPackage() ?: return toast(getString(R.string.spotify_missing))
        YtEngine.quiet() // YouTube Music 이 곡을 바꾼 직후면 '다시 재생' 시도가 Spotify 소리를 가져가지 않게
        bindSpotifyWrapper()
        pin(pkg)
        rememberOtherApps()
        pickSession(force = true)
        if (listOpen) setListOpen(true) // 라이브러리를 Spotify 것으로
        // 아직 Spotify 재생 정보가 없으면(꺼져 있거나 쉬는 중) Spotify 에 직접 재생 명령을 보내 깨운다.
        // 차량의 Spotify 는 BYD 미디어 화면에 통합돼 실행 아이콘이 없을 수 있어 앱 띄우기는 마지막 수단.
        // Spotify 는 외부 미디어 버튼 신호를 막아 두어(수신기 비공개) 라이브러리 연결로 세션 열쇠를 받아 깨운다
        if (controller?.packageName != pkg) withSpotifyController { pickSession(force = true) }
        updatePlayerChips()
    }

    /**
     * 플레이어로 FLO 선택 (차량 기본 음악 앱, 0.68). 차에는 화면 앱(com.byd.music.kr)과 재생 엔진(skplanet.musicmate)이 따로 있고,
     * 재생 정보 · 버튼 · 라이브러리는 엔진이 공개한 연결(MediaSession · MediaBrowser)을 그대로 쓴다.
     * 아직 재생 정보가 없으면 빈 플레이 화면 → 재생 버튼이 FLO 에 재생 신호를 보내 깨운다 (wakeApp).
     */
    private fun selectFlo() {
        if (!isInstalled(FLO_PKG)) return toast(getString(R.string.flo_missing))
        YtEngine.quiet()
        pin(FLO_PKG)
        rememberOtherApps()
        pickSession(force = true)
        if (listOpen) setListOpen(true) // 라이브러리를 FLO 것으로
        updatePlayerChips()
    }

    /** 플레이어 앱 아이콘 (FLO 는 엔진 앱 대신 화면 앱 아이콘이 있으면 그것) */
    private fun playerIcon(pkg: String): android.graphics.drawable.Drawable? {
        val icon = if (pkg == FLO_PKG && isInstalled(FLO_BYD)) FLO_BYD else pkg
        return runCatching { packageManager.getApplicationIcon(icon) }.getOrNull()
    }

    /**
     * 설치된 Spotify 패키지. 차량의 Spotify 는 실행 아이콘(런처 항목)이 없을 수 있어
     * 실행 가능 여부가 아니라 설치 여부로 판단한다. (차량용 빌드는 이름이 다를 수 있어 com.spotify.* 도 찾음)
     */
    private fun spotifyPackage(): String? {
        if (isInstalled(SPOTIFY)) return SPOTIFY
        return runCatching {
            packageManager.getInstalledApplications(0).map { it.packageName }.firstOrNull { it.startsWith("com.spotify") }
        }.getOrNull()
    }

    private fun isInstalled(pkg: String) = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    /** 그 앱에만 미디어 버튼(재생)을 보내 깨운다. 받는 곳이 없으면 앱 화면을 연다 */
    private fun wakeApp(pkg: String) {
        if (!sendMediaButton(pkg, KeyEvent.KEYCODE_MEDIA_PLAY)) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
        }
        // 재생 정보가 늦게 뜨는 경우를 위해 잠시 뒤 다시 찾아본다
        main.postDelayed({ if (pinned() == pkg && controller?.packageName != pkg) pickSession(force = true) }, 1500)
        main.postDelayed({ if (pinned() == pkg && controller?.packageName != pkg) pickSession(force = true) }, 4000)
    }

    /** 특정 앱의 미디어 버튼 수신기로 키를 보낸다. 수신기가 있으면 true */
    private fun sendMediaButton(pkg: String, code: Int): Boolean {
        val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
        val receivers = runCatching { packageManager.queryBroadcastReceivers(probe, 0) }.getOrDefault(emptyList())
        if (receivers.isEmpty()) return false
        val target = receivers.first().activityInfo
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val i = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClassName(target.packageName, target.name)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
            runCatching { sendBroadcast(i) }
        }
        return true
    }

    // ---- 블루투스 (휴대폰 음악) ----

    /**
     * 플레이어로 블루투스 선택: 휴대폰에서 블루투스로 트는 음악을 D-Player 에서 보고 조정한다 (0.46).
     * 차는 표준 안드로이드 블루투스 음악(A2DP 받기 + AVRCP 조정)을 쓰고, 그 플레이어(BluetoothMediaBrowserService)가
     * 다른 앱에 열려 있어 Spotify 처럼 재생 정보 · 재생 / 다음 / 이전을 그대로 쓸 수 있다 (펌웨어 13.1.32 확인).
     * 라이브러리는 휴대폰이 목록 보기를 지원하면(AVRCP 1.4+) 그 목록, 아니면 비어 있음.
     */
    private fun selectBluetooth() {
        YtLog.e("블루투스 고름: 플레이어 서비스=${btService()?.className ?: "없음"}, 휴대폰 연결=${btPhoneConnected()}, " +
            "세션 보임=${activeSessions().any { it.packageName == BT_PKG }}")
        if (btService() == null) return toast(getString(R.string.bt_unavailable))
        YtEngine.quiet()
        pin(BT_PKG)
        rememberOtherApps()
        pickSession(force = true)
        if (controller?.packageName != BT_PKG) withBtController { pickSession(force = true) }
        if (btPhoneConnected() == false) toast(getString(R.string.bt_no_phone))
        if (listOpen) setListOpen(true) // 라이브러리를 블루투스 것으로
        updatePlayerChips()
    }

    /** 진단 로그용: 고른 플레이어 · 보이는 재생 세션 · 블루투스 상태 */
    private fun playerReport(): String = buildString {
        appendLine("고른 플레이어: ${pinned() ?: "(자동)"} · 보고 있는 세션: ${controller?.let { key(it) } ?: "없음"}")
        val list = activeSessions()
        if (list.isEmpty()) appendLine("보이는 재생 세션 없음")
        list.forEach { c ->
            appendLine("- ${key(c)} 상태=${c.playbackState?.state} 곡='${c.metadata?.description?.title ?: ""}'")
            if (c.packageName == MEDIA_PKG) appendLine("  미디어센터: ${describeSession(c)} · 라디오로 보임=${looksLikeRadio(c)}")
        }
        if (radioComponent() != null) appendLine(CarRadio.describe(this@MainActivity))
        append("블루투스: 플레이어 서비스=${btService()?.className ?: "없음(꺼짐)"}, 휴대폰 연결=${btPhoneConnected()}, " +
            "연결한 컨트롤러=${btCtl != null} 상태=${btCtl?.playbackState?.state} 곡='${btCtl?.metadata?.description?.title ?: ""}'")
    }

    // ---- 차량 라디오 (0.65) ----

    /**
     * 플레이어로 라디오 선택: 차의 미디어센터(com.byd.mediacenter)가 라디오를 틀고, D-Player 는 그 재생 정보로
     * 재생 · 멈춤 · 이전/다음 방송만 조정한다 (Spotify 와 같은 방식, 차량 시스템 설정은 건드리지 않음).
     * 미디어센터가 지금 라디오가 아니면 BYD SDK 로 화면 없이 라디오로 바꾼다 (CarRadio — 미디어센터는 분할을 지원하지 않아
     * 라디오 화면을 열면 분할이 풀림). SDK 권한을 못 받는 차만 라디오 화면을 잠깐 열고 D-Player 로 돌아온다.
     */
    private fun selectRadio() {
        val comp = radioComponent() ?: return toast(getString(R.string.radio_unavailable))
        val c = activeSessions().firstOrNull { it.packageName == MEDIA_PKG }
        YtLog.e("라디오 고름: ${c?.let { describeSession(it) } ?: "미디어센터 재생 정보 없음"}")
        YtEngine.quiet()
        pin(MEDIA_PKG)
        rememberOtherApps()
        pickSession(force = true)
        updatePlayerChips()
        if (c != null && looksLikeRadio(c)) {
            val st = c.playbackState?.state
            if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_BUFFERING) c.transportControls.play()
            return
        }
        Thread {
            // 처음 한 번: SDK 권한을 ADB 로 D-Player 자신에게 (항상 허용된 키가 있을 때만 — 허용 창 없음)
            if (!CarRadio.hasPermission(this) && AdbProvisioner.isReady(this)) {
                val ok = AdbProvisioner.grantPermissions(this, CarRadio.PERMISSIONS)
                YtLog.e("라디오 권한 받기: ${if (ok) "됨" else "안 됨"}")
            }
            val r = if (CarRadio.hasPermission(this)) CarRadio.play(this) else null
            YtLog.e("라디오 켜기 (화면 없이): 결과=${r ?: "못 함"} · ${CarRadio.describe(this)}")
            main.post {
                if (pinned() != MEDIA_PKG) return@post
                if (r != null) {
                    main.postDelayed({
                        activeSessions().firstOrNull { it.packageName == MEDIA_PKG }?.let { YtLog.e("라디오 켠 뒤: ${describeSession(it)}") }
                        pickSession(force = true)
                    }, 1500)
                } else openRadioScreen(comp)
            }
        }.start()
    }

    /** SDK 를 못 쓸 때만: 라디오 화면을 열어 라디오로 바꾼 뒤 D-Player 를 다시 앞으로 (분할은 DolphinControl 이 다시 묶음) */
    private fun openRadioScreen(comp: ComponentName) {
        toast(getString(R.string.radio_opening))
        runCatching { startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { YtLog.e("라디오 화면 열기 실패: ${it.message}") }
        main.postDelayed({
            if (pinned() != MEDIA_PKG) return@postDelayed
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            activeSessions().firstOrNull { it.packageName == MEDIA_PKG }?.let { YtLog.e("라디오 켠 뒤: ${describeSession(it)}") }
            pickSession(force = true)
        }, 2500)
    }

    /** 미디어센터가 지금 라디오를 트는지 — 곡 이름 · 설명에 주파수(MHz · kHz · FM · AM, 예 89.1)가 있으면 라디오로 봄 */
    private fun looksLikeRadio(c: MediaController): Boolean {
        val d = c.metadata?.description
        val text = listOfNotNull(d?.title, d?.subtitle, d?.description,
            c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)).joinToString(" ")
        return RADIO_TEXT.containsMatchIn(text)
    }

    /** 진단용: 세션이 주는 곡 정보 키 · 값과 지원 명령 (라디오 화면 다듬기용) */
    private fun describeSession(c: MediaController): String = buildString {
        append("상태=${c.playbackState?.state} 명령=${c.playbackState?.actions}")
        val m = c.metadata
        m?.keySet()?.sorted()?.forEach { k ->
            val v = runCatching { m.getString(k) }.getOrNull() ?: runCatching { m.getLong(k).toString() }.getOrNull()
            if (!v.isNullOrEmpty() && v != "0") append(" · ${k.substringAfterLast('.')}=$v")
        }
        c.extras?.keySet()?.let { if (it.isNotEmpty()) append(" · extras=${it.joinToString(",")}") }
    }

    /**
     * 위쪽 음악 소스 버튼 (0.68): 설정에서 고른 것만 보인다. 블루투스 · 라디오 · FLO 는 이 차에 있을 때만
     * (라디오 = 미디어센터에 라디오 화면, 블루투스 = 블루투스 음악 플레이어, FLO = FLO 엔진 앱).
     */
    private fun updateRailButtons() {
        if (!::btnFlo.isInitialized) return
        val shown = railSources()
        mapOf("spotify" to btnSpotify, "flo" to btnFlo, "youtube" to btnYt, "usb" to btnLocal, "bt" to btnBt,
            "radio" to btnRadio, "ytmusic" to btnYtMusic).forEach { (k, v) ->
            v.visibility = if (k in shown) View.VISIBLE else View.GONE
        }
    }

    /** 이 차에 있는 소스 (설정 창에 보일 것) */
    private fun availableSources(): List<String> = RAIL_SOURCES.map { it.first }.filter {
        when (it) {
            "bt" -> btService() != null || pinned() == BT_PKG
            "radio" -> radioComponent() != null || pinned() == MEDIA_PKG
            "flo" -> isInstalled(FLO_PKG)
            else -> true
        }
    }

    private fun hiddenSources(): Set<String> = prefs.getStringSet("rail_hidden", null).orEmpty()

    /** 위쪽에 보일 소스 = 이 차에 있는 것 − 설정에서 끈 것 */
    private fun railSources(): List<String> = availableSources().filter { it !in hiddenSources() }

    private fun sourceLabel(key: String): String = getString(RAIL_SOURCES.first { it.first == key }.second)

    /** 설정 → 위쪽 음악 버튼: 보일 소스 고르기 (이 차에 없는 소스의 설정은 그대로 둠) */
    private fun editSources() {
        val all = availableSources()
        val checked = BooleanArray(all.size) { all[it] !in hiddenSources() }
        AlertDialog.Builder(this)
            .setTitle(R.string.sources_title)
            .setMultiChoiceItems(all.map { sourceLabel(it) }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (checked.none { it }) return@setPositiveButton toast(getString(R.string.sources_need_one))
                val hidden = hiddenSources().filter { it !in all } + all.filterIndexed { i, _ -> !checked[i] }
                prefs.edit().putStringSet("rail_hidden", hidden.toSet()).apply()
                updateRailButtons()
                settingsPanel.refresh()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun radioComponent(): ComponentName? {
        val comp = ComponentName(MEDIA_PKG, RADIO_ACTIVITY)
        return comp.takeIf { runCatching { packageManager.getActivityInfo(it, 0) }.isSuccess }
    }

    private fun btService(): ComponentName? =
        packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(BT_PKG), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    /** 휴대폰이 블루투스 음악으로 연결돼 있나 (모르면 null). A2DP 받기 = 프로필 11 (공개 API 에 상수만 숨김) */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun btPhoneConnected(): Boolean? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val a = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return false
            a.isEnabled && a.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        }.getOrNull()
    }

    private var btBrowser: MediaBrowser? = null
    private var btCtl: MediaController? = null
    private val btPending = mutableListOf<(MediaController) -> Unit>()

    /** 블루투스 플레이어 컨트롤러로 cb 실행: 재생 세션 → 열어 둔 연결 → 새 연결 순 */
    private fun withBtController(cb: (MediaController) -> Unit) {
        activeSessions().firstOrNull { it.packageName == BT_PKG }?.let { return cb(it) }
        btCtl?.let { return cb(it) }
        btPending.add(cb)
        if (btBrowser != null) return // 연결 중
        val comp = btService() ?: return toast(getString(R.string.bt_unavailable))
        lateinit var b: MediaBrowser
        b = MediaBrowser(this, comp, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val c = MediaController(this@MainActivity, b.sessionToken)
                YtLog.e("블루투스 플레이어 연결됨: 상태=${c.playbackState?.state} 곡='${c.metadata?.description?.title ?: ""}'")
                btCtl = c
                val todo = btPending.toList()
                btPending.clear()
                todo.forEach { it(c) }
            }
            override fun onConnectionFailed() {
                YtLog.e("블루투스 플레이어 연결 실패")
                btBrowser = null
                btPending.clear()
                toast(getString(R.string.bt_unavailable))
            }
            override fun onConnectionSuspended() {
                YtLog.e("블루투스 플레이어 연결 끊김")
                btBrowser = null
                btCtl = null
            }
        }, null)
        btBrowser = b
        runCatching { b.connect() }.onFailure { btBrowser = null; btPending.clear() }
    }

    // ---- USB / MicroSD ----

    /** 플레이어로 USB/SD 선택: 라이브러리에 저장소 폴더를 보여준다 */
    private fun selectLocal() {
        YtEngine.quiet()
        pin(LOCAL_KEY)
        rememberOtherApps()
        pickSession(force = true)
        listMode = ListMode.LOCAL
        localPath = null
        if (!listOpen && !compact) {
            listOpen = true
            applyLayout()
            resetAutoClose()
        }
        if (listOpen) loadList()
        updatePlayerChips()
    }

    private fun storagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasStorage() = storagePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** USB/SD 라이브러리: 저장소 → 폴더 → 파일 (폴더 우선) */
    private fun showLocal() {
        val path = localPath
        setHeader(if (path == null) getString(R.string.local_title) else path.substringAfterLast('/'))
        if (!hasStorage()) {
            if (!storageAsked) {
                storageAsked = true
                runCatching { requestPermissions(storagePermissions(), REQ_STORAGE) }
            }
            return showMessage(getString(R.string.local_need_permission))
        }
        if (!LocalEngine.scanned) {
            showMessage(getString(R.string.local_scanning))
            return LocalEngine.scan(this) { if (listOpen && listMode == ListMode.LOCAL) showLocal() }
        }
        if (path == null) {
            val vols = LocalEngine.rootFolders()
            return setRows(vols.map {
                val (a, v) = LocalEngine.countUnder(it.label)
                Row(Kind.LOCAL_FOLDER, it.label, getString(R.string.local_counts, a, v), id = it.label, playable = it.removable)
            }, getString(R.string.local_empty))
        }
        val (audio, _) = LocalEngine.countUnder(path)
        val head = listOf(Row(Kind.UP, getString(R.string.go_up))) +
            if (audio > 0) listOf(Row(Kind.LOCAL_PLAY_ALL, getString(R.string.yt_play_all), getString(R.string.local_songs, audio), id = path))
            else emptyList()
        val folders = LocalEngine.subFolders(path).map { name ->
            val (a, v) = LocalEngine.countUnder("$path/$name")
            Row(Kind.LOCAL_FOLDER, name, getString(R.string.local_counts, a, v), id = "$path/$name")
        }
        val files = LocalEngine.filesIn(path).map {
            Row(Kind.LOCAL_FILE, it.title, it.artist.ifEmpty { if (it.video) getString(R.string.local_video) else "" },
                id = it.uri.toString(), queueId = if (it.video) 1 else 0)
        }
        setRows(head + folders + files, getString(R.string.empty_folder))
    }

    private fun localUp() {
        val path = localPath ?: return
        localPath = if (LocalEngine.volumes.any { it.label == path }) null else path.substringBeforeLast('/', "").ifEmpty { null }
        showLocal()
    }

    /** 파일 재생: 음악은 그 폴더의 음악을 이어서, 영상은 전체화면 */
    private fun playLocalFile(row: Row) {
        val folder = localPath ?: return
        val files = LocalEngine.filesIn(folder)
        val item = files.firstOrNull { it.uri.toString() == row.id } ?: return
        if (item.video) {
            LocalEngine.play(this, listOf(item), 0, shuffle = false)
            startActivity(Intent(this, VideoActivity::class.java))
        } else {
            val songs = files.filter { !it.video }
            LocalEngine.play(this, songs, songs.indexOf(item), shuffle = false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && listOpen && listMode == ListMode.LOCAL) showLocal()
    }

    /** 권한 창이 막힌 차량: 차량 ADB 로 저장소 권한 부여 */
    private fun grantStorageViaAdb() {
        showMessage(getString(R.string.local_granting))
        Thread {
            val ok = AdbProvisioner.grantPermissions(this, storagePermissions().toList())
            main.post {
                if (!ok) toast(getString(R.string.grant_failed_short))
                if (listOpen && listMode == ListMode.LOCAL) showLocal()
            }
        }.start()
    }

    /** 지금 플레이어 앱: 보고 있는 세션의 앱, 없으면 고정한 앱 */
    private fun currentPlayer(): String? = controller?.let { key(it) } ?: pinned()

    /** 지금 플레이어가 YouTube Music 인가 */
    private fun isYtPlayer(): Boolean = currentPlayer() == packageName

    /** 지금 플레이어가 Spotify 인가 */
    private fun isSpotifyPlayer(): Boolean = currentPlayer()?.startsWith("com.spotify") == true

    /** 지금 플레이어가 USB/SD 인가 */
    private fun isLocalPlayer(): Boolean = currentPlayer() == LOCAL_KEY

    /** 라이브러리는 지금 선택된 플레이어의 목록: YT Music 이면 내 재생목록, 다른 앱이면 그 앱의 대기열/라이브러리 */
    private fun followPlayer() {
        val library = isLibraryMode()
        listMode = when {
            isYtPlayer() -> if (library) ListMode.YOUTUBE else ListMode.YT_QUEUE
            isLocalPlayer() -> if (library) ListMode.LOCAL else ListMode.QUEUE
            else -> if (library) ListMode.LIBRARY else ListMode.QUEUE
        }
    }

    /**
     * 다른 앱이 재생을 시작해 플레이어가 그 앱으로 바뀌면 YT Music 선택을 해제한다 (라이브러리도 그 앱 것으로).
     * 해제했으면 true.
     */
    private fun checkOtherAppPlaying(): Boolean {
        val c = controller ?: return false
        val pinnedPkg = pinned() ?: return false
        if (key(c) == pinnedPkg || c.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        pin(key(c))
        return true
    }

    private fun updatePlayerChips() {
        val player = currentPlayer()
        btnYtMusic.isActivated = player == packageName
        btnSpotify.isActivated = player != null && player.startsWith("com.spotify")
        btnLocal.isActivated = player == LOCAL_KEY
        btnBt.isActivated = player == BT_PKG
        if (::btnRadio.isInitialized) btnRadio.isActivated = player == MEDIA_PKG
        if (::btnFlo.isInitialized) btnFlo.isActivated = player == FLO_PKG
    }

    private fun showYtLibrary() {
        val top = ytTop
        setHeader(top?.title ?: if (ytVideoMode) YtEngine.Site.VIDEO.label else YtEngine.Site.MUSIC.label)
        when (YtEngine.loggedIn) {
            null -> showMessage(getString(R.string.loading)) // 로그인 확인이 끝나면 이어서
            false -> showMessage(getString(if (YtEngine.notPremium) R.string.yt_not_premium_tap else R.string.yt_need_login))
            true -> when (top?.type) {
                null -> if (ytVideoMode) showYtVideoRoot() else {
                    showYtRoot()
                    // 홈 추천은 5분 동안 그대로 쓰고(바로 보임), 지나면 뒤에서 새로 받음
                    if (ytHome == null || SystemClock.elapsedRealtime() - ytHomeAt > 5 * 60_000) {
                        ytHomeFailed = false
                        YtEngine.requestHome(this)
                    }
                }
                YtPageType.MYLIB -> {
                    showMessage(getString(R.string.loading))
                    YtEngine.requestLibrary(this)
                }
                YtPageType.PLAYLIST -> {
                    showMessage(getString(R.string.loading))
                    YtEngine.requestPlaylist(this, top.id)
                }
                YtPageType.BROWSE, YtPageType.SEARCH -> {
                    val key = top.type.name + ":" + top.id
                    val cached = ytPageCache[key]
                    if (cached != null) showYtShelves(top, cached) else showMessage(getString(R.string.loading))
                    if (top.type == YtPageType.SEARCH) YtEngine.requestSearch(this, top.id)
                    else YtEngine.requestBrowse(this, top.id, top.params)
                }
            }
        }
    }

    /** 첫 화면: 검색 · 내 재생목록 · 홈 추천(칸마다 제목 줄) */
    private fun showYtRoot() {
        val recent = recentSearches().firstOrNull()
        val head = listOf(
            Row(Kind.YT_SEARCH, getString(R.string.yt_search),
                recent?.let { getString(R.string.yt_search_recent, it) } ?: getString(R.string.yt_search_desc)),
            Row(Kind.YT_MYLIB, getString(R.string.yt_my_playlists), getString(R.string.yt_my_playlists_desc)),
        )
        val home = ytHome
        val tail = when {
            home != null -> shelvesToRows(home)
            ytHomeFailed -> listOf(Row(Kind.YT_RETRY, getString(R.string.yt_home_failed), getString(R.string.yt_home_retry)))
            else -> listOf(Row(Kind.YT_HEADER, getString(R.string.yt_home_loading)))
        }
        setRows(head + tail, "")
    }

    /**
     * YouTube 영상 첫 화면: 위쪽 탭(검색 · 맞춤 동영상 · 구독 · 기록 · 나중에 볼 동영상) + 고른 탭의 영상 카드.
     * 전에 받은 내용이 있으면 바로 보여 주고 뒤에서 새로 받는다. failed = 받기 실패 이유 (다시 시도 줄)
     */
    private fun showYtVideoRoot(request: Boolean = true, failed: String? = null) {
        val tabs = Row(Kind.YT_TABS, "", id = ytVideoTab)
        val page = ytPageCache[YtPageType.BROWSE.name + ":" + ytVideoTab]
        val shelves = page?.optJSONArray("shelves")
        val body = when {
            shelves != null -> toGrid(shelvesToRows(shelves, headers = shelves.length() > 1))
                .ifEmpty { listOf(Row(Kind.YT_HEADER, getString(R.string.yt_video_none))) }
            failed != null -> listOf(Row(Kind.YT_RETRY, getString(R.string.yt_load_failed, failed).substringBefore('\n'),
                getString(R.string.yt_home_retry)))
            else -> listOf(Row(Kind.YT_HEADER, getString(R.string.loading)))
        }
        setRows(listOf(tabs) + body, "")
        if (request) YtEngine.requestBrowse(this, ytVideoTab)
    }

    private fun selectYtVideoTab(id: String) {
        if (id == "search") return askYtSearch()
        ytVideoTab = id
        showYtVideoRoot()
        listView.setSelection(0)
    }

    /** 영상 · 재생목록 · 채널 줄을 썸네일 카드 줄로 묶는다 (칸 제목 · 위로 · 전체 재생 줄은 그대로) */
    private fun toGrid(items: List<Row>): List<Row> {
        val w = listView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val cols = gridCols(w)
        ytGridCols = cols
        val out = ArrayList<Row>()
        val run = ArrayList<Row>()
        fun flush() {
            run.chunked(cols).forEach { out.add(Row(Kind.YT_GRID, "", cards = it)) }
            run.clear()
        }
        for (r in items) {
            if (r.kind == Kind.YT_SONG || r.kind == Kind.YT_LIST || r.kind == Kind.YT_BROWSE) run.add(r)
            else { flush(); out.add(r) }
        }
        flush()
        return out
    }

    /** 카드 한 칸이 280dp 안팎이 되게 (분할 화면 2칸 · 전체화면 4칸) */
    private fun gridCols(widthPx: Int) = (widthPx / dp(280)).coerceIn(1, 5)

    /** 앨범·재생목록·믹스·아티스트 화면, 검색 결과 */
    private fun showYtShelves(page: YtPage, msg: org.json.JSONObject) {
        val shelves = msg.optJSONArray("shelves") ?: org.json.JSONArray()
        val list = msg.optString("playlistId").ifEmpty { null }
        ytPageList = list
        msg.optString("title").takeIf { it.isNotEmpty() && page.type == YtPageType.BROWSE }?.let { setHeader(it) }
        val body = shelvesToRows(shelves, headers = shelves.length() > 1)
        val songs = body.count { it.kind == Kind.YT_SONG }
        // 영상은 하나씩 보는 것이라 '전체 재생'(음악 셔플)은 YT Music 쪽에서만
        val head = listOf(Row(Kind.UP, getString(R.string.go_up))) +
            if (list != null && songs > 1 && !ytVideoMode) listOf(Row(Kind.YT_PLAY_ALL, getString(R.string.yt_play_all), page.title, id = list))
            else emptyList()
        if (ytVideoMode && body.isEmpty()) return setRows(head + Row(Kind.YT_HEADER,
            getString(if (page.type == YtPageType.SEARCH) R.string.yt_search_none else R.string.yt_video_none)), "")
        setRows(head + if (ytVideoMode) toGrid(body) else body,
            getString(if (page.type == YtPageType.SEARCH) R.string.yt_search_none else R.string.yt_no_songs))
    }

    /** 칸 [{title, items}] → 제목 줄 + 항목 줄 */
    private fun shelvesToRows(shelves: org.json.JSONArray, headers: Boolean = true): List<Row> {
        val out = ArrayList<Row>()
        for (i in 0 until shelves.length()) {
            val s = shelves.optJSONObject(i) ?: continue
            val items = s.optJSONArray("items") ?: continue
            if (headers && s.optString("title").isNotEmpty()) out.add(Row(Kind.YT_HEADER, s.optString("title")))
            for (j in 0 until items.length()) {
                val it = items.optJSONObject(j) ?: continue
                val icon = it.optString("thumb").takeIf { u -> u.isNotEmpty() }?.let(Uri::parse)
                val title = it.optString("title")
                // 영상 카드는 채널 · 조회수 · 올린 때를 모두, 길이는 썸네일 위에
                val sub = if (ytVideoMode) it.optString("info").ifEmpty { it.optString("subtitle") } else it.optString("subtitle")
                val badge = if (ytVideoMode) it.optString("duration") else ""
                out.add(when (it.optString("kind")) {
                    "song" -> Row(Kind.YT_SONG, title, sub, id = it.optString("videoId"), iconUri = icon,
                        extra = it.optString("playlistId").ifEmpty { null }, badge = badge)
                    "list" -> Row(Kind.YT_LIST, title, sub, id = it.optString("playlistId"), iconUri = icon)
                    else -> Row(Kind.YT_BROWSE, title, sub, id = it.optString("browseId"), iconUri = icon,
                        extra = it.optString("params").ifEmpty { null })
                })
            }
        }
        return out
    }

    private fun recentSearches(key: String = "yt_recent_search"): List<String> =
        prefs.getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun askYtSearch() = askSearch("yt_recent_search") { query ->
        ytStack.add(YtPage(YtPageType.SEARCH, query, getString(R.string.yt_search_title, query)))
        showYtLibrary()
    }

    /** 검색 창: 검색어 입력 + 최근 검색어 (눌러서 바로 검색). key = 최근 검색어 저장 이름 (YouTube Music · Spotify 따로) */
    private fun askSearch(key: String, onQuery: (String) -> Unit) {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.yt_search_hint)
            textSize = 20f
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        box.addView(input)
        var dialog: AlertDialog? = null
        val go = { q: String ->
            val query = q.trim()
            if (query.isNotEmpty()) {
                val recent = (listOf(query) + recentSearches(key).filter { it != query }).take(8)
                prefs.edit().putString(key, recent.joinToString("\n")).apply()
                dialog?.dismiss()
                onQuery(query)
                resetAutoClose()
            }
        }
        recentSearches(key).forEach { q ->
            box.addView(TextView(this).apply {
                text = q
                textSize = 18f
                setTextColor(getColor(R.color.text))
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = dp(56)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_timer, 0, 0, 0)
                compoundDrawablePadding = dp(14)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_dim))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { go(q) }
            })
        }
        input.setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
        searchDialogOpen = true
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.yt_search)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.yt_search_go, null)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { searchDialogOpen = false; resetAutoClose() }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go(input.text.toString()) }
            input.requestFocus()
        }
        dialog.show()
    }

    /**
     * Spotify 앱에 재생 명령 (spotify:playlist:… / spotify:track:…). 소리는 Spotify 앱이 낸다.
     * Spotify 재생 정보(세션)가 아직 없으면 깨운 뒤 다시 시도한다.
     */
    private fun spotifyPlay(uri: String, shuffle: Boolean, attempt: Int = 0) {
        val pkg = spotifyPackage() ?: return toast(getString(R.string.spotify_missing))
        val c = activeSessions().firstOrNull { it.packageName == pkg }
        if (c == null && attempt == 0) return withSpotifyController { ctl ->
            if (shuffle) setShuffleOn(ctl)
            ctl.transportControls.playFromUri(Uri.parse(uri), null)
            if (shuffle) {
                main.postDelayed({ setShuffleOn(controller ?: ctl) }, 2000)
                shuffleOn = true
                btnShuffle.isActivated = true
            }
        }
        if (c == null) {
            when {
                attempt == 0 -> bindSpotifyWrapper()
                attempt == 2 -> return launchSpotifyBriefly { spotifyPlay(uri, shuffle, attempt + 1) }
            }
            if (attempt < 4) main.postDelayed({ spotifyPlay(uri, shuffle, attempt + 1) }, 2000)
            else toast(getString(R.string.sp_not_ready))
            return
        }
        if (shuffle) setShuffleOn(c)
        c.transportControls.playFromUri(Uri.parse(uri), null)
        if (shuffle) {
            main.postDelayed({ setShuffleOn(c) }, 2000) // 재생이 시작된 뒤 한 번 더
            shuffleOn = true
            btnShuffle.isActivated = true
        }
    }

    /**
     * YouTube 로그인 (TV 방식, 0.66): 차 화면에 코드를 띄우면 휴대폰으로 google.com/device 에 들어가 입력 · 허용.
     * 비밀번호는 D-Player 가 다루지 않는다. 프리미엄 계정이 아니면 로그인하지 않은 것으로 되돌린다.
     */
    private fun openLogin() {
        if (loginDialog != null) return
        prefs.edit().putBoolean("yt_used", true).apply()
        val pad = dp(24)
        val msg = TextView(this).apply {
            text = getString(R.string.yt_login_preparing)
            textSize = 18f
            setTextColor(getColor(R.color.text))
            setPadding(pad, dp(12), pad, 0)
        }
        val code = TextView(this).apply {
            textSize = 40f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accent))
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(pad, dp(16), pad, dp(8))
            // 코드가 두 줄로 잘리지 않게 칸 폭에 맞춰 글자를 줄임
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(20, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        // QR 코드 (0.69): 휴대폰으로 찍으면 코드가 들어간 google.com/device 화면이 바로 열림
        val qr = ImageView(this).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(msg)
            addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, pad, 0)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(12) })
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.yt_login)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener {
                loginCancel?.invoke()
                loginCancel = null
                loginDialog = null
                resetAutoClose()
            }
            .create()
        loginDialog = dlg
        dlg.show()
        bigButtons(dlg)
        loginCancel = YtEngine.login(this, onCode = { d ->
            msg.text = getString(R.string.yt_login_steps, d.url.removePrefix("https://").removePrefix("www."))
            code.text = d.userCode
            // google.com/device 는 user_code 를 받아 그 코드의 확인 화면으로 바로 넘겨 준다
            runCatching { qrBitmap("${d.url}?user_code=${java.net.URLEncoder.encode(d.userCode, "UTF-8")}", dp(184)) }
                .onSuccess { qr.setImageBitmap(it); qr.visibility = View.VISIBLE }
        }, onDone = { ok, text ->
            loginCancel = null
            if (ok) {
                dlg.dismiss()
                toast(getString(R.string.yt_login_done))
                settingsPanel.refresh()
                if (listOpen && listMode == ListMode.YOUTUBE) loadList()
            } else {
                code.text = ""
                qr.visibility = View.GONE
                msg.text = getString(R.string.yt_login_failed, text)
            }
        })
    }

    /** 글자 → QR 코드 그림 (검은 점 · 흰 바탕, 가장자리 여백 1칸) */
    private fun qrBitmap(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.RGB_565)
    }

    private fun onEmptyClick() {
        if (listMode == ListMode.LOCAL && !hasStorage()) return grantStorageViaAdb()
        if (listMode == ListMode.LOCAL && LocalEngine.scanned) {
            showMessage(getString(R.string.local_scanning))
            return LocalEngine.scan(this, force = true) { if (listOpen && listMode == ListMode.LOCAL) showLocal() }
        }
        if (listMode == ListMode.YOUTUBE && YtEngine.loggedIn == false) openLogin() else loadList()
    }

    /** 엔진 → 화면: 로그인 상태, 재생목록, 곡 목록, 오류 */
    private fun onYtMessage(msg: org.json.JSONObject) {
        val inYtList = listOpen && listMode == ListMode.YOUTUBE
        when (msg.optString("type")) {
            "status" -> {
                settingsPanel.refresh()
                if (inYtList) showYtLibrary()
            }
            "home" -> {
                ytHome = msg.optJSONArray("shelves")
                ytHomeAt = SystemClock.elapsedRealtime()
                if (inYtList && ytTop == null) showYtRoot()
            }
            "browse", "search" -> {
                val type = if (msg.optString("type") == "search") YtPageType.SEARCH else YtPageType.BROWSE
                val id = msg.optString(if (type == YtPageType.SEARCH) "query" else "id")
                ytPageCache[type.name + ":" + id] = msg
                if (ytPageCache.size > 30) ytPageCache.keys.firstOrNull()?.let { ytPageCache.remove(it) }
                val top = ytTop
                if (inYtList && top != null && top.type == type && top.id == id) showYtShelves(top, msg)
                if (inYtList && top == null && ytVideoMode && type == YtPageType.BROWSE && id == ytVideoTab) showYtVideoRoot(request = false)
            }
            "library" -> if (inYtList && ytTop?.type == YtPageType.MYLIB) {
                val items = msg.optJSONArray("items")
                val list = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }.map {
                    Row(Kind.YT_PLAYLIST, it.optString("title"), it.optString("subtitle"),
                        id = it.optString("id"), iconUri = it.optString("thumb").takeIf { u -> u.isNotEmpty() }?.let(Uri::parse))
                }
                setRows(listOf(Row(Kind.UP, getString(R.string.go_up))) + list, getString(R.string.yt_no_playlists))
            }
            "queue" -> if (listOpen && listMode == ListMode.YT_QUEUE) {
                ytQueueList = msg.optString("list")
                val items = msg.optJSONArray("items")
                val list = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }.map {
                    Row(Kind.YT_QUEUE, it.optString("title"), it.optString("subtitle"),
                        id = it.optString("videoId"), active = it.optBoolean("selected"),
                        iconUri = it.optString("thumb").takeIf { u -> u.isNotEmpty() }?.let(Uri::parse))
                }
                setRows(list, getString(R.string.yt_no_queue))
                scrollToActive()
            }
            "playlist" -> {
                val pl = ytTop?.takeIf { it.type == YtPageType.PLAYLIST }
                if (!inYtList || pl == null || pl.id != msg.optString("id")) return
                val items = msg.optJSONArray("items")
                val songs = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }.map {
                    Row(Kind.YT_SONG, it.optString("title"), it.optString("subtitle"),
                        id = it.optString("videoId"), iconUri = it.optString("thumb").takeIf { u -> u.isNotEmpty() }?.let(Uri::parse))
                }
                val head = listOf(Row(Kind.UP, getString(R.string.go_up))) +
                    if (songs.isEmpty()) emptyList() else listOf(Row(Kind.YT_PLAY_ALL, getString(R.string.yt_play_all), pl.title, id = pl.id))
                setRows(head + songs, getString(R.string.yt_no_songs))
            }
            "error" -> when (msg.optString("cmd")) {
                // 재생 실패: 웹으로 대신하지 않고 이유를 보여 줌 (자세한 것은 진단 로그)
                "play" -> toast(getString(R.string.yt_play_failed, msg.optString("message")))
                // 홈 추천을 못 받아도 검색 · 내 재생목록은 그대로 쓸 수 있게
                "home" -> {
                    ytHomeFailed = true
                    if (inYtList && ytTop == null && ytHome == null) showYtRoot()
                }
                // 영상 첫 화면: 전에 받은 영상이 있으면 그대로 두고, 없으면 탭 아래에 다시 시도 줄
                "browse" if inYtList && ytVideoMode && ytTop == null -> {
                    if (ytPageCache.containsKey(YtPageType.BROWSE.name + ":" + ytVideoTab))
                        toast(getString(R.string.yt_load_failed, msg.optString("message")).substringBefore('\n'))
                    else showYtVideoRoot(request = false, failed = msg.optString("message"))
                }
                "library", "playlist", "queue", "search", "browse" -> if (listOpen && (inYtList || listMode == ListMode.YT_QUEUE)) {
                    if (inYtList && ytTop != null && rows.size > 1) toast(getString(R.string.yt_load_failed, msg.optString("message")))
                    else setRows(listOfNotNull(ytTop?.let { Row(Kind.UP, getString(R.string.go_up)) }),
                        getString(R.string.yt_load_failed, msg.optString("message")))
                }
            }
        }
    }

    private fun isListVisible() = listOpen

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        updateRailButtons() // 차에 생기거나 없어진 소스 반영
        // 설치 · 업데이트 뒤 처음: 이용 안내에 동의(카페 닉네임 포함)해야 권한 설정 · 재생을 시작한다 (0.58)
        if (!termsAgreed()) return showTerms(firstRun = true)
        CafeRegister.onAppStart(this) // 닉네임을 이 버전에 아직 안 보냈으면 한 번 보냄 (중복은 안에서 막음)
        if (granting) {
            grantEvents.append(grantTime()).append(" 화면 돌아옴 (onResume)\n")
            // 허용 창에서 답하고 돌아온 것 → 응답이 없으면 곧바로 새 연결로 확인하게
            if (pausedDuringGrant) AdbClient.dialogClosedAt = SystemClock.elapsedRealtime()
        }
        refreshAccess()
        resetAutoClose()
        refreshShortcuts() // 앱을 지웠거나 새로 깔았을 때
        ReturnBubble.hide(this) // 돌아왔으면 '돌아가기' 버튼은 치움
    }

    // ---- 앱 바로가기 (위쪽 버튼 줄 오른쪽) ----

    /** 바로가기 버튼 다시 그리기. 누르면 실행, 길게 누르면 고르기 */
    private fun refreshShortcuts() {
        appShortcuts.removeAllViews()
        AppShortcuts.list(this).forEachIndexed { i, pkg ->
            val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return@forEachIndexed
            appShortcuts.addView(ImageButton(this).apply {
                setImageDrawable(icon)
                setBackgroundResource(R.drawable.bg_player_icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                contentDescription = AppShortcuts.appLabel(this@MainActivity, pkg)
                setOnClickListener { launchShortcut(pkg) }
                setOnLongClickListener { editShortcuts(); true }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { if (i > 0) marginStart = dp(4) })
        }
    }

    private fun launchShortcut(pkg: String) {
        val i = AppShortcuts.launchIntent(this, pkg) ?: return toast(getString(R.string.shortcut_missing))
        // 열기 전에 D-Player 창 위치를 기억 → 열린 앱 위의 '돌아가기' 버튼을 그 자리 왼쪽에
        val loc = IntArray(2).also { window.decorView.getLocationOnScreen(it) }
        val pane = android.graphics.Rect(loc[0], loc[1], loc[0] + window.decorView.width, loc[1] + window.decorView.height)
        runCatching { startActivity(i) }
            .onSuccess { main.postDelayed({ if (!resumed) ReturnBubble.show(this, pane) }, 600) }
            .onFailure { toast(getString(R.string.shortcut_missing)) }
    }

    /** 바로가기 앱 고르기 (최대 4개, 고른 순서대로) */
    private fun editShortcuts() {
        val apps = AppShortcuts.launchableApps(this).filter { it.first != packageName }
        val chosen = ArrayList(AppShortcuts.list(this))
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.shortcut_title, AppShortcuts.MAX))
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, on ->
                val pkg = apps[which].first
                if (on && chosen.size >= AppShortcuts.MAX) {
                    checked[which] = false
                    dialog.listView.setItemChecked(which, false)
                    toast(getString(R.string.shortcut_max, AppShortcuts.MAX))
                    return@setMultiChoiceItems
                }
                if (on) chosen.add(pkg) else chosen.remove(pkg)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppShortcuts.set(this, chosen)
                refreshShortcuts()
                settingsPanel.refresh()
            }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun grantTime() = String.format(java.util.Locale.US, "[%5.1fs]", (SystemClock.elapsedRealtime() - grantStart) / 1000f)

    // ---- 이용 안내 및 면책 고지 (res/raw/disclaimer.txt) + 카페 닉네임 (CafeRegister) ----

    private var termsDialog: AlertDialog? = null
    private var playersStarted = false

    private fun disclaimerText(): String =
        resources.openRawResource(R.raw.disclaimer).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

    private fun versionCode(): Long = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    /** 이 버전에서 이용 안내에 동의했는지 — 새로 설치 · 업데이트할 때마다 다시 묻는다 (agreed_code = 동의한 versionCode, 0.58) */
    private fun termsAgreed(): Boolean = prefs.getLong(AGREED_CODE, 0L) == versionCode()

    /** 재생 엔진 · 이어 듣기 시작 (동의한 뒤 한 번) */
    private fun startPlayers() {
        if (playersStarted) return
        playersStarted = true
        YtEngine.cleanOldWebEngine(this) // 0.65 까지의 웹 엔진 파일 (한 번만, 0.66)
        // 마지막에 듣던 플레이어가 YouTube Music 이면 엔진을 미리 켜 둠 (로그인 확인 · 재생목록 준비 · 시동 후 이어 듣기).
        // Spotify 등 다른 앱을 듣던 중이면 켜지 않음 — 숨긴 YouTube Music 페이지가 메모리 약 1GB 와 CPU 를 써서
        // 시동 직후 바쁜 차(TMAP 등)를 더 느리게 함. YouTube Music 을 고르면 그때 켬 (0.44)
        if (prefs.getBoolean("yt_used", false) && pinned() == packageName) YtEngine.start(this)
        // 마지막 플레이어가 USB 면 시동 후 마지막 곡을 이어서 (끄기 전 재생 중이었으면 바로 재생, 아니면 준비만) — 0.55
        if (pinned() == LOCAL_KEY && LocalEngine.current == null && YtEngine.isResumeOnStart(this) && LocalEngine.hasLast(this)) {
            LocalEngine.restoreLast(this, autoPlay = LocalEngine.lastWasPlaying(this)) { ok ->
                if (ok) main.postDelayed({ pickSession(force = true) }, 500)
            }
        }
        // 시동 직후에도 Spotify 가 바로 재생 · 라이브러리를 받게 — 마지막 플레이어가 Spotify 일 때만 (0.51)
        if (pinned()?.startsWith("com.spotify") == true) {
            bindSpotifyWrapper()
            // 끄기 전에 Spotify 가 재생 중이었으면 이어서 재생 (0.64, '시동 켜면 마지막 곡 이어서 재생' 설정)
            if (YtEngine.isResumeOnStart(this) && SpotifyMemory.wasPlaying(this)) {
                withSpotifyController { c ->
                    val st = c.playbackState?.state
                    YtLog.e("Spotify 이어 재생 (시동): 지금 상태=$st")
                    if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_BUFFERING) c.transportControls.play()
                    pickSession(force = true)
                }
            }
        }
    }

    /** 이용 안내 글 (스크롤) */
    private fun termsBody(heightPx: Int?): View {
        val pad = dp(20)
        val text = TextView(this).apply {
            text = disclaimerText()
            textSize = 15f
            setPadding(pad, dp(8), pad, dp(8))
        }
        return android.widget.ScrollView(this).apply {
            addView(text)
            if (heightPx != null) layoutParams = LinearLayout.LayoutParams(-1, heightPx)
        }
    }

    /** 차에서 누르기 쉽게 버튼을 크게 (글자 19sp, 높이 56dp) */
    private fun bigButtons(dlg: AlertDialog) {
        for (w in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dlg.getButton(w)?.apply {
                textSize = 19f
                minHeight = dp(56)
            }
        }
        // 분할 칸이 좁으면 대화상자가 화면 전체 폭 기준으로 커져 버튼이 칸 밖으로 잘림 → 칸 폭에 맞춤 (0.61)
        val paneW = window.decorView.width
        val real = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it) }
        if (paneW > 0 && paneW < real.widthPixels * 0.8f) {
            dlg.window?.setLayout(paneW - dp(16), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * firstRun = 설치 · 업데이트 뒤 동의 창 (위: 이용 안내 · 아래: 카페 닉네임 필수, 동의하지 않으면 앱 종료),
     * 아니면 설정에서 이용 안내 다시 보기 (읽기만)
     */
    private fun showTerms(firstRun: Boolean) {
        if (termsDialog?.isShowing == true) return
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.terms_title)
        if (!firstRun) {
            termsDialog = b.setView(termsBody(null)).setPositiveButton(R.string.close, null).show().also { bigButtons(it) }
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(termsBody((resources.displayMetrics.heightPixels * 0.34f).toInt()))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            setText(R.string.cafe_required)
            textSize = 17f
            paint.isFakeBoldText = true
        })
        val form = CafeRegister.buildForm(this, box)
        root.addView(android.widget.ScrollView(this).apply { addView(box) })
        val dlg = b.setView(root)
            .setCancelable(false)
            .setPositiveButton(R.string.terms_agree, null)
            .setNegativeButton(R.string.terms_disagree) { _, _ ->
                termsDialog = null
                finishAndRemoveTask()
            }
            .create()
        dlg.setOnShowListener {
            bigButtons(dlg)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!form.validateAndSave()) return@setOnClickListener // 빈칸이면 창을 닫지 않음
                prefs.edit().putLong(AGREED_CODE, versionCode()).apply()
                termsDialog = null
                dlg.dismiss()
                startPlayers()
                if (resumed) {
                    refreshAccess()
                    resetAutoClose()
                }
            }
        }
        termsDialog = dlg
        runCatching { dlg.show() }
    }

    override fun onPause() {
        super.onPause()
        if (granting) {
            pausedDuringGrant = true
            grantEvents.append(grantTime()).append(" 화면 가려짐 (onPause) — 허용 창이 떴을 가능성\n")
        }
        resumed = false
        updateCarPanel() // 화면이 가려지면 패널을 닫아 DolphinControl 이 차 상태를 자주 읽지 않게
        main.removeCallbacks(ticker)
        main.removeCallbacks(autoClose)
        if (sessionsRegistered) runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        sessionsRegistered = false
        controller?.unregisterCallback(controllerCallback)
        controller = null
        disconnectBrowser()
    }

    // ---- 권한 ----

    private fun hasListenerAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == listener }
    }

    /** 권한이 있으면 플레이어 시작, 없으면 (실행 후 최초 1회) ADB 자동 권한 설정을 바로 시작 */
    /** 알림 접근이 이미 있으면 'ADB 권한 설정' 화면으로 제목·버튼을 바꾼다 */
    private fun updateSetupTexts() {
        val granted = hasListenerAccess()
        findViewById<TextView>(R.id.setupTitle).setText(if (granted) R.string.setup_title_adb else R.string.setup_title)
        findViewById<TextView>(R.id.btnOpenSettings).setText(if (granted) R.string.setup_later else R.string.try_settings)
    }

    private fun refreshAccess() {
        updateSetupTexts()
        val granted = hasListenerAccess()
        // 권한 설정 중(허용 창이 닫혀 돌아온 직전 등)에는 진행 화면을 그대로 둔다
        val showSetup = !granted || granting
        setupPanel.visibility = if (showSetup) View.VISIBLE else View.GONE
        playerPanel.visibility = if (showSetup) View.GONE else View.VISIBLE
        if (granted) startPlayer()
        if (granting) return
        if (!granted && !autoGrantTried) {
            autoGrantTried = true
            autoGrant()
        } else if (granted && !AdbProvisioner.isReady(this) && !prefs.getBoolean(ADB_ASKED_KEY, false)) {
            // 설치 후 한 번만: 자동 업데이트에 쓸 ADB '항상 허용'을 받아 둔다
            setupPanel.visibility = View.VISIBLE
            playerPanel.visibility = View.GONE
            autoGrant()
        }
    }

    private fun startPlayer() {
        if (sessionsRegistered || !resumed) return
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
        sessionsRegistered = true
        pickSession(force = true)
        main.removeCallbacks(ticker)
        main.post(ticker)
        if (!updateChecked) {
            updateChecked = true
            AppUpdater.checkAuto(this)
        }
    }

    // ---- 설정 ----

    private fun showSettings() = settingsPanel.show()

    /** 설정 화면에서 누른 항목의 실제 동작 */
    private val settingsActions = object : SettingsPanel.Actions {
        override fun favoritesChanged() = renderFavorites()
        override fun carPanelChanged() {
            carRetryAt = 0L
            updateCarPanel()
        }
        override fun checkUpdate() = AppUpdater.checkManual(this@MainActivity)
        override fun regrant() {
            setupPanel.visibility = View.VISIBLE
            playerPanel.visibility = View.GONE
            autoGrant()
        }
        override fun openListenerSettings() = this@MainActivity.openListenerSettings()
        override fun saveDiagnostics() {
            toast(getString(R.string.set_diag_saving))
            val players = playerReport()
            Thread {
                val probe = AdbClient(this@MainActivity)
                val r = probe.connect() // 허용 창 없이 지금 키로 접속되는지만 시험
                // 시스템이 보는 재생 세션 · 블루투스 음악 상태 (블루투스 세션은 휴대폰이 틀기 전엔 앱 목록에 안 보여서)
                val sys = if (probe.isConnected) runCatching {
                    val ms = probe.shell("dumpsys media_session").lines()
                    val from = ms.indexOfFirst { "Sessions Stack" in it }.coerceAtLeast(0)
                    val bt = probe.shell("dumpsys bluetooth_manager | grep -i -E 'a2dpsink|avrcpcontroller|mediabrowser|state:|enabled:' | head -40")
                    // 누가 소리를 쥐고 있는지 · 최근 누가 가져갔는지 (음악이 갑자기 멈출 때 원인 찾기, 0.56)
                    val focus = probe.shell("dumpsys audio | grep -i -A14 'focus stack' | head -20") + "\n[최근 오디오 포커스 요청 · 반납 (앱 이름 포함)]\n" +
                        probe.shell("dumpsys audio | grep -E 'requestAudioFocus|abandonAudioFocus|dispatching|loss' | tail -40")
                    "[시스템 재생 세션]\n" + ms.drop(from).take(150).joinToString("\n") + "\n\n[시스템 블루투스]\n" + bt +
                        "\n\n[오디오 포커스]\n" + focus
                }.getOrElse { "(시스템 정보 못 읽음: ${it.message})" } else "(ADB 연결 안 됨 → 시스템 정보 없음)"
                if (probe.isConnected) AdbDiag.cleanCarTmp(probe) // 예전 버전이 차에 남긴 임시 기록도 지움
                probe.close()
                val path = AdbDiag.save(this@MainActivity, "manual",
                    "[지금 접속 시험] $r\n${probe.log}\n[마지막 권한 설정 기록]\n${lastAdbLog.ifEmpty { "(없음)" }}\n\n" +
                        "[재생 앱 · 블루투스]\n$players\n\n$sys\n\n" +
                        "[YouTube · YouTube Music 동작 기록 (최근 800줄, 앱이 꺼져도 남음)]\n" +
                        com.dolphin.mplayer.youtube.YtLog.dump())
                main.post {
                    if (path == null) toast(getString(R.string.set_diag_failed))
                    else AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.set_diag)
                        .setMessage(getString(R.string.adb_log_saved, path) + "\n\n[지금 접속 시험] $r\n" + probe.log)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.start()
        }

        /**
         * 삭제 전 정리: 앱을 지워도 남는 것(공용 Download 폴더의 진단 파일, 차의 /data/local/tmp 임시 파일)을 지운다.
         * 앱 데이터 · 권한 · 알림 접근 · 웹 로그인 정보는 앱을 지우면 Android 가 함께 지움. (0.50)
         */
        override fun cleanBeforeUninstall() {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.set_clean)
                .setMessage(R.string.set_clean_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.set_clean_btn) { _, _ ->
                    Thread {
                        val files = AdbDiag.prune(this@MainActivity, 0)
                        AppUpdater.clearDownloads(this@MainActivity)
                        val adb = AdbClient(this@MainActivity)
                        val car = adb.connect() == AdbClient.Result.OK
                        if (car) AdbDiag.cleanCarTmp(adb)
                        adb.close()
                        main.post {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(R.string.set_clean)
                                .setMessage(getString(R.string.set_clean_done, files) +
                                    (if (car) "" else "\n\n" + getString(R.string.set_clean_no_adb)))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }.start()
                }
                .show()
        }
        override fun ytLogin() = openLogin()
        override fun ytLogout() {
            YtEngine.logout()
            toast(getString(R.string.yt_logout_done))
        }
        override fun spotifyInstalled() = spotifyPackage() != null
        override fun spotifyBrowse(): String? = prefs.getString("sp_browse", null)
        override fun probeSpotify() = probeSpotifyLibrary()
        override fun showTerms() = showTerms(firstRun = false)
        override fun editShortcuts() = this@MainActivity.editShortcuts()
        override fun editSources() = this@MainActivity.editSources()
        override fun sourcesSummary() = railSources().joinToString(" · ") { sourceLabel(it) }
        override fun autoCloseSec() = this@MainActivity.autoCloseSec()
        override fun setAutoCloseSec(sec: Int) {
            prefs.edit().putInt("auto_close_sec", sec).apply()
            resetAutoClose()
        }
    }

    /** 차량 ADB 에 접속해 스스로 알림 접근 권한을 부여 (차량 화면의 'USB 디버깅 허용' 승인 필요) */
    private fun autoGrant() {
        if (granting) return
        granting = true
        prefs.edit().putBoolean(ADB_ASKED_KEY, true).apply()
        updateSetupTexts()
        grantStart = SystemClock.elapsedRealtime()
        grantEvents.setLength(0)
        pausedDuringGrant = false
        setupSpinner.visibility = View.VISIBLE
        btnAutoGrant.isEnabled = false
        Thread {
            val report = AdbProvisioner.grantAll(this) { msg ->
                main.post { setupStatus.text = msg }
            }
            val outcome = report.outcome
            // 분석용 전체 기록 (환경 + 화면 가려짐 여부 + 연결 기록) → 로컬 저장
            val dialogSeen = pausedDuringGrant
            val body = "[결과] $outcome · 항상 허용 저장=${report.remembered} · 허용 창 표시 추정=${if (dialogSeen) "예" else "아니오"}\n\n" +
                "[화면 이벤트]\n" + grantEvents.ifEmpty { "(없음 — 권한 설정 중 화면이 한 번도 가려지지 않음)\n" } + "\n" +
                "[요약]\n" + report.summary + "\n[연결 기록]\n" + report.log
            val saved = AdbDiag.save(this, "grant", body)
            main.post {
                granting = false
                lastAdbLog = (if (saved != null) getString(R.string.adb_log_saved, saved) + "\n\n" else "") +
                    "허용 창 표시 추정: ${if (dialogSeen) "예" else "아니오"}\n" + grantEvents + "\n" + report.log
                if (isDestroyed) return@post
                setupSpinner.visibility = View.GONE
                btnAutoGrant.isEnabled = true
                btnAutoGrant.setText(R.string.retry)
                if (outcome == AdbProvisioner.Outcome.GRANTED && hasListenerAccess()) {
                    refreshAccess()
                    // 무엇이 됐는지 (특히 '항상 허용' 저장 여부) 한 번 보여준다
                    AlertDialog.Builder(this)
                        .setTitle(if (report.remembered) R.string.grant_ok else R.string.grant_partly)
                        .setMessage(report.summary)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@post
                }
                // 실패하면 연결 기록을 바로 보여준다 (사진으로 보내 주시면 원인 확인)
                if (outcome != AdbProvisioner.Outcome.UNREACHABLE) showAdbLog()
                setupStatus.setText(
                    when {
                        outcome == AdbProvisioner.Outcome.UNREACHABLE -> R.string.grant_unreachable
                        // 화면이 한 번도 가려지지 않음 = 차량에 허용 창이 뜨지 않음
                        outcome == AdbProvisioner.Outcome.DENIED && !dialogSeen -> R.string.grant_no_dialog
                        outcome == AdbProvisioner.Outcome.DENIED -> R.string.grant_denied
                        else -> R.string.grant_failed
                    }
                )
            }
        }.start()
    }

    private fun showAdbLog() {
        if (lastAdbLog.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.adb_log)
            .setMessage(lastAdbLog)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** DiLink 는 설정 화면 진입을 막는 경우가 있어 여러 경로를 차례로 시도 */
    private fun openListenerSettings() {
        val candidates = listOf(
            Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", listener.flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        if (candidates.none { runCatching { startActivity(it) }.isSuccess }) {
            toast(getString(R.string.settings_unavailable))
        }
    }

    // ---- 세션 선택 ----

    private fun activeSessions(): List<MediaController> =
        runCatching { sessionManager.getActiveSessions(listener) }.getOrDefault(emptyList())

    private fun pickSession(sessions: List<MediaController>? = null, force: Boolean = false) {
        val list = sessions ?: activeSessions()
        val preferred = prefs.getString("package", null)
        // 플레이어를 골랐으면(YT Music·Spotify 버튼 등) 그 앱의 세션만 따른다 (아직 없으면 빈 플레이 화면)
        val pinnedPkg = pinned()
        val pick = if (pinnedPkg != null) list.firstOrNull { key(it) == pinnedPkg }
            ?: btCtl?.takeIf { pinnedPkg == BT_PKG } // 블루투스는 휴대폰이 재생하기 전엔 세션 목록에 안 보일 수 있음
        else list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && key(it) == preferred }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { key(it) == preferred }
            ?: list.firstOrNull()
        if (!force && pick?.sessionToken == controller?.sessionToken) return
        bind(pick)
    }

    private fun bind(c: MediaController?) {
        val samePackage = c?.let { key(it) } == lastPackage
        controller?.unregisterCallback(controllerCallback)
        controller = c
        lastPackage = c?.let { key(it) }
        c?.registerCallback(controllerCallback, main)
        if (!samePackage) shuffleOn = false
        if (!samePackage && c?.packageName == BT_PKG)
            YtLog.e("블루투스 음악 표시: 상태=${c.playbackState?.state} 곡='${c.metadata?.description?.title ?: ""}'")
        checkOtherAppPlaying()
        renderMetadata()
        renderState()
        if (isListVisible() && !samePackage) {
            val wasMode = listMode
            followPlayer()
            // YT Music·USB 목록은 보던 화면 그대로 유지 (곡을 재생해 세션이 새로 생겨도)
            if (!(wasMode == listMode && (listMode == ListMode.YOUTUBE || listMode == ListMode.LOCAL))) loadList()
        } else if (isListVisible() && browser == null && listMode == ListMode.LIBRARY) {
            loadList()
        }
    }

    private fun showSources() {
        val sessions = activeSessions()
        val labels = sessions.map { (if (it.sessionToken == controller?.sessionToken) "▶ " else "") + appLabel(key(it)) } +
            getString(R.string.open_media_app) + getString(R.string.listener_settings)
        AlertDialog.Builder(this)
            .setTitle(R.string.sources)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    sessions.size -> showMediaAppsDialog()
                    sessions.size + 1 -> openListenerSettings()
                    else -> {
                        pin(key(sessions[which]))
                        bind(sessions[which])
                        if (listOpen) setListOpen(true) // 라이브러리를 새 플레이어 것으로
                    }
                }
            }
            .show()
    }

    // ---- 재생 제어 ----

    private fun togglePlay() {
        val c = controller ?: return when (val p = pinned()) {
            null, packageName -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            LOCAL_KEY -> LocalEngine.play()
            BT_PKG -> withBtController { it.transportControls.play() }
            else -> if (isSpotifyPlayer()) withSpotifyController { it.transportControls.play() } else wakeApp(p)
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    /** 정지를 지원하면 stop, 아니면 일시정지 + 곡 처음으로 */
    private fun stop() {
        val c = controller ?: return mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
        val actions = c.playbackState?.actions ?: 0
        if (actions and PlaybackState.ACTION_STOP != 0L) {
            c.transportControls.stop()
        } else {
            c.transportControls.pause()
            c.transportControls.seekTo(0)
        }
    }

    /**
     * 셔플: 앱이 셔플 커스텀 액션을 노출하면 그걸 쓰고,
     * 아니면 AndroidX(MediaSessionCompat/Media3) 표준 셔플 명령을 보낸다.
     */
    private fun toggleShuffle() {
        val c = controller ?: return toast(getString(R.string.no_session))
        val custom = shuffleCustomAction(c.playbackState)
        shuffleOn = !shuffleOn
        if (custom != null) {
            c.transportControls.sendCustomAction(custom, null)
        } else {
            c.transportControls.sendCustomAction(
                COMPAT_SET_SHUFFLE,
                Bundle().apply { putInt(COMPAT_SHUFFLE_ARG, if (shuffleOn) 1 else 0) }
            )
        }
        btnShuffle.isActivated = shuffleOn
    }

    private fun shuffleCustomAction(state: PlaybackState?) = state?.customActions?.firstOrNull {
        it.action.contains("shuffle", true) || it.name.toString().contains("shuffle", true) ||
            it.name.toString().contains("셔플")
    }

    /** 세션이 없을 때: 마지막으로 재생하던 앱에 미디어 키 전달 */
    private fun mediaKey(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- 화면 갱신 ----

    private fun renderMetadata() {
        val c = controller
        val m = c?.metadata
        sourceLabel.text = c?.let { appLabel(key(it)) }
            ?: pinned()?.let { appLabel(it) } ?: getString(R.string.no_session)
        updatePlayerChips()
        when (val p = currentPlayer()) {
            LOCAL_KEY -> sourceIcon.setImageResource(R.drawable.ic_logo_usb)
            BT_PKG -> sourceIcon.setImageResource(R.drawable.ic_logo_bt)
            MEDIA_PKG -> sourceIcon.setImageResource(R.drawable.ic_logo_radio)
            // D-Player 안의 YouTube 재생: D-Player 아이콘 대신 YT Music / YouTube 로고 (0.68)
            packageName -> sourceIcon.setImageResource(
                if (YtEngine.site == YtEngine.Site.VIDEO) R.drawable.ic_logo_youtube else R.drawable.ic_logo_ytmusic)
            null -> sourceIcon.setImageResource(R.drawable.ic_music)
            else -> playerIcon(p)?.let { sourceIcon.setImageDrawable(it) } ?: sourceIcon.setImageResource(R.drawable.ic_music)
        }
        if (c == null || m == null || (currentPlayer() == BT_PKG && m.description.title.isNullOrBlank())) {
            title.setText(R.string.nothing_playing)
            artist.setText(if (currentPlayer() == BT_PKG) R.string.bt_start else R.string.start_music)
            cover.setImageResource(R.drawable.ic_music)
            coverBackdrop.tag = null
            coverBackdrop.setImageDrawable(null)
            return
        }
        val desc = m.description
        val newTitle = desc.title ?: m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.nothing_playing)
        if (title.text.toString() != newTitle.toString()) {
            title.text = newTitle
            title.isSelected = false // 곡이 바뀌면 다시 두 번 흐르게
            title.isSelected = true
        }
        val who = desc.subtitle?.toString() ?: m.getString(MediaMetadata.METADATA_KEY_ARTIST)
        artist.text = who.orEmpty()
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: desc.iconBitmap
        val artUri = (m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI))?.let(Uri::parse) ?: desc.iconUri
        images.load(cover, art, artUri, R.drawable.ic_music)
        images.load(coverBackdrop, art, artUri, android.R.color.transparent)
    }

    private fun renderState() {
        val state = controller?.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (currentPlayer() == LOCAL_KEY) shuffleOn = LocalEngine.shuffleEnabled // USB/SD 는 실제 셔플 상태
        btnShuffle.isActivated = shuffleOn
        renderProgress()
        updateCarPanel()
    }

    private fun renderProgress() {
        val c = controller
        val state = c?.playbackState
        val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        if (state == null || duration <= 0) {
            progress.progress = 0
            timeElapsed.text = formatTime(0)
            timeTotal.text = formatTime(0)
            return
        }
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        progress.max = duration.toInt()
        pos = pos.coerceIn(0, duration)
        progress.progress = pos.toInt()
        timeElapsed.text = formatTime(pos)
        timeTotal.text = formatTime(duration)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---- 목록: 라이브러리 / 대기열 / 앱 ----

    private fun loadList() {
        disconnectBrowser()
        if (!isListVisible()) return
        applyLayout() // 왼쪽 버튼 선택 표시
        if (listMode == ListMode.YOUTUBE) return showYtLibrary()
        if (listMode == ListMode.YT_QUEUE) return showYtQueue()
        if (listMode == ListMode.LOCAL) return showLocal()
        val pkg = controller?.packageName?.let { if (it == FLO_BYD) FLO_PKG else it }
            ?: (if (isSpotifyPlayer()) spotifyPackage() else if (pinned() == FLO_PKG) FLO_PKG else null)
            ?: return showMediaApps()
        if (listMode == ListMode.QUEUE) return showQueue()
        // 라이브러리 탐색 (곡을 재생해 대기열이 바뀌어도 탐색 화면은 그대로)
        val service = packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0
        ).firstOrNull()?.serviceInfo ?: return libraryUnavailable()

        showMessage(getString(R.string.loading))
        connectBrowser(ComponentName(pkg, service.name), recent = false)
    }

    /** 이번 라이브러리가 '최근 재생' 목록인지 (앱이 전체 라이브러리를 공개하지 않을 때) */
    private var browsingRecent = false

    /**
     * 앱의 라이브러리(MediaBrowser)에 연결. Spotify 처럼 허용한 앱에만 공개하는 경우가 있어,
     * 거절되면 Android 시스템이 쓰는 '최근 재생' 방식으로 한 번 더 요청하고, 그래도 안 되면 이유를 안내한다.
     */
    private fun connectBrowser(component: ComponentName, recent: Boolean) {
        val hints = if (recent) Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) } else null
        lateinit var b: MediaBrowser
        b = MediaBrowser(this, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (browser !== b) return
                if (component.packageName.startsWith("com.spotify")) setSpotifyBrowse(SP_OK)
                browsingRecent = recent
                browseStack.clear()
                browseTo(b.root)
            }
            override fun onConnectionFailed() {
                if (browser !== b) return
                if (!recent) connectBrowser(component, recent = true) else spotifyRetryOr(component) { libraryUnavailable() }
            }
            override fun onConnectionSuspended() {
                if (browser === b) libraryUnavailable()
            }
        }, hints)
        browser = b
        runCatching { b.connect() }.onFailure {
            if (!recent) connectBrowser(component, recent = true) else spotifyRetryOr(component) { libraryUnavailable() }
        }
    }

    /** 이번 라이브러리 요청에서 Spotify 를 이미 깨워 봤는지 */
    private var spBrowseWoken = false

    /**
     * Spotify 라이브러리 연결이 실패했을 때: 시동 직후라 준비가 안 됐을 수 있으니
     * 차량 Spotify 를 깨워(서비스 → 안 되면 화면을 잠깐) 한 번 더 시도한다. 그래도 안 되면 orElse.
     */
    private fun spotifyRetryOr(component: ComponentName, orElse: () -> Unit) {
        if (!component.packageName.startsWith("com.spotify") || spBrowseWoken) return orElse()
        spBrowseWoken = true
        showMessage(getString(R.string.sp_waking))
        bindSpotifyWrapper()
        val retry = { if (listOpen && isSpotifyPlayer()) connectBrowser(component, recent = false) }
        if (spotifySessionReady()) main.postDelayed(retry, 1500)
        else launchSpotifyBriefly(retry)
    }

    // ---- Spotify 앱 연동 상태 (설정에 '연동됨' / 웹 로그인 표시용) ----

    private val SP_OK = "ok"
    private val SP_REFUSED = "refused"
    private var spProbe: MediaBrowser? = null

    private fun setSpotifyBrowse(v: String) {
        if (prefs.getString("sp_browse", null) == v) return
        prefs.edit().putString("sp_browse", v).apply()
        main.post { settingsPanel.refresh() }
    }

    /** 설정을 열 때: 차의 Spotify 앱이 라이브러리를 열어 주는지 잠깐 연결해 확인 (거절되면 '최근 재생' 방식도 한 번) */
    private fun probeSpotifyLibrary() {
        if (spProbe != null) return
        val pkg = spotifyPackage() ?: return
        val svc = packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return setSpotifyBrowse(SP_REFUSED)
        fun attempt(recent: Boolean) {
            val hints = if (recent) Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) } else null
            lateinit var b: MediaBrowser
            val failed = {
                spProbe = null
                if (!recent) attempt(true) else setSpotifyBrowse(SP_REFUSED)
            }
            b = MediaBrowser(this, ComponentName(pkg, svc.name), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    runCatching { b.disconnect() }
                    spProbe = null
                    setSpotifyBrowse(SP_OK)
                }
                override fun onConnectionFailed() = failed()
            }, hints)
            spProbe = b
            runCatching { b.connect() }.onFailure { failed() }
        }
        attempt(false)
    }

    private fun disconnectBrowser() {
        browser?.let { b -> browseStack.lastOrNull()?.let { runCatching { b.unsubscribe(it) } }; runCatching { b.disconnect() } }
        browser = null
        browseStack.clear()
        spSearchQuery = null
        runCatching { spSearchBrowser?.disconnect() } // 검색 연결도 목록과 함께 끊음 (붙잡아 두지 않음)
        spSearchBrowser = null
    }

    private fun browseTo(parentId: String) {
        val b = browser ?: return
        browseStack.lastOrNull()?.let { runCatching { b.unsubscribe(it) } }
        browseStack.addLast(parentId)
        showMessage(getString(R.string.loading))
        b.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(id: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (browser !== b || browseStack.lastOrNull() != id) return
                val items = children.map {
                    val d = it.description
                    Row(
                        if (it.isBrowsable) Kind.FOLDER else Kind.PLAYABLE,
                        d.title?.toString() ?: "", d.subtitle?.toString() ?: "",
                        id = it.mediaId, icon = d.iconBitmap, iconUri = d.iconUri,
                        playable = it.isBrowsable && it.isPlayable,
                    )
                }
                val inFolder = browseStack.size > 1
                // 시동 직후 Spotify 는 연결은 되지만 빈 목록을 줄 수 있다 → 깨워서 다시
                val comp = ComponentName(spotifyPackage() ?: "", "")
                if (!inFolder && items.isNotEmpty() && isSpotifyPlayer()) spBrowseWoken = false // 준비됨 → 다음에 다시 깨울 수 있게
                if (!inFolder && items.isEmpty() && isSpotifyPlayer() && !spBrowseWoken) {
                    val svc = packageManager.queryIntentServices(
                        Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(comp.packageName), 0
                    ).firstOrNull()?.serviceInfo
                    if (svc != null) return spotifyRetryOr(ComponentName(svc.packageName, svc.name)) {}
                }
                val up = if (inFolder || (browsingRecent && isSpotifyPlayer())) listOf(Row(Kind.UP, getString(R.string.go_up)))
                // Spotify 라이브러리 첫 화면: 맨 위에 노래 검색 (차량 Spotify 앱의 검색을 그대로 씀, 0.54) · 최근 들은 곡 (0.55)
                else if (isSpotifyPlayer()) listOf(
                    Row(Kind.SP_SEARCH, getString(R.string.yt_search),
                        recentSearches(SP_RECENT).firstOrNull()?.let { getString(R.string.yt_search_recent, it) }
                            ?: getString(R.string.yt_search_desc)),
                    // Spotify 안에 '최근 재생' 폴더가 있으면 그곳으로, 없으면 Android 표준 '최근 재생' 목록을 요청
                    Row(Kind.SP_RECENT, getString(R.string.sp_recent), getString(R.string.sp_recent_desc),
                        id = items.firstOrNull { r -> r.kind == Kind.FOLDER &&
                            Regex("최근|recent|recently", RegexOption.IGNORE_CASE).containsMatchIn(r.title) }?.id),
                )
                else emptyList()
                // 곡이 있는 폴더(재생목록)면 맨 위에 전체 재생 (셔플)
                val playAll = if (inFolder && (items.any { it.kind == Kind.PLAYABLE } || folderPlayable[id] == true))
                    listOf(Row(Kind.PLAY_ALL, getString(R.string.yt_play_all), id = id)) else emptyList()
                val up2 = up + playAll
                setHeader(getString(if (browsingRecent) R.string.header_recent else R.string.header_library,
                    appLabel(controller?.let { key(it) } ?: pinned())))
                setRows(up2 + items, getString(R.string.empty_folder))
            }
            override fun onError(id: String) {
                if (browser === b) fallbackToQueue()
            }
        })
    }

    /** Spotify 검색 중이면 그 검색어 (위로 / 뒤로 가기 = 라이브러리 첫 화면) */
    private var spSearchQuery: String? = null

    /**
     * Spotify 검색: 차량 Spotify 앱의 라이브러리 연결(MediaBrowser)에 검색을 맡긴다 — Android Auto 가 쓰는 표준 방식.
     * 웹 페이지를 띄우지 않고 검색할 때 한 번만 요청해서 차에 부담이 없다. 결과를 못 주면 '바로 재생'(playFromSearch).
     */
    private fun spotifySearch(query: String) {
        val b = browser?.takeIf { it.isConnected }
        spSearchQuery = query
        setHeader(getString(R.string.yt_search_title, query))
        val playRow = Row(Kind.SP_PLAY_QUERY, getString(R.string.sp_play_query, query), getString(R.string.sp_play_query_desc), id = query)
        val up = Row(Kind.UP, getString(R.string.go_up))
        if (b == null) return setRows(listOf(up, playRow), "")
        showMessage(getString(R.string.loading))
        withSpotifySearchBrowser(b.serviceComponent) { sb ->
            if (spSearchQuery != query) return@withSpotifySearchBrowser
            if (sb == null) return@withSpotifySearchBrowser setRows(listOf(up, playRow), "")
            val sent = runCatching<Unit> {
                sb.search(query, null, object : MediaBrowserCompat.SearchCallback() {
                    override fun onSearchResult(q: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                        if (spSearchQuery != query) return
                        val found = items.take(50).map {
                            val d = it.description
                            Row(if (it.isBrowsable) Kind.FOLDER else Kind.PLAYABLE,
                                d.title?.toString() ?: "", d.subtitle?.toString() ?: "",
                                id = it.mediaId, icon = d.iconBitmap, iconUri = d.iconUri,
                                playable = it.isBrowsable && it.isPlayable)
                        }
                        setRows(listOf(up) + found + playRow, "")
                    }
                    override fun onError(q: String, extras: Bundle?) {
                        if (spSearchQuery == query) setRows(listOf(up, playRow), "")
                    }
                })
            }.isSuccess
            if (!sent) setRows(listOf(up, playRow), "")
        }
    }

    /** 검색 전용 연결 (AndroidX). 검색할 때만 붙고, 목록을 닫으면 끊음 */
    private var spSearchBrowser: MediaBrowserCompat? = null

    private fun withSpotifySearchBrowser(comp: ComponentName, cb: (MediaBrowserCompat?) -> Unit) {
        spSearchBrowser?.takeIf { it.isConnected && it.serviceComponent == comp }?.let { return cb(it) }
        runCatching { spSearchBrowser?.disconnect() }
        lateinit var b: MediaBrowserCompat
        b = MediaBrowserCompat(this, comp, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() = cb(b)
            override fun onConnectionFailed() { spSearchBrowser = null; cb(null) }
            override fun onConnectionSuspended() { spSearchBrowser = null }
        }, null)
        spSearchBrowser = b
        runCatching { b.connect() }.onFailure { spSearchBrowser = null; cb(null) }
    }

    /** 검색 결과에서 라이브러리 첫 화면으로 */
    private fun closeSpotifySearch() {
        spSearchQuery = null
        val root = browseStack.lastOrNull() ?: return loadList()
        browseStack.removeLast()
        browseTo(root)
    }

    private fun spotifyPlayFromSearch(query: String) {
        val c = controller?.takeIf { isSpotifyPlayer() }
        if (c != null) c.transportControls.playFromSearch(query, Bundle())
        else withSpotifyController { it.transportControls.playFromSearch(query, Bundle()) }
    }

    private fun goUp() {
        val b = browser ?: return
        if (browseStack.size < 2) return
        runCatching { b.unsubscribe(browseStack.removeLast()) }
        browseTo(browseStack.removeLast())
    }

    private fun fallbackToQueue() = libraryUnavailable()

    /** 앱이 라이브러리를 공개하지 않음: 대기열로 바꾸지 않고 이유와 대안을 안내 */
    private fun libraryUnavailable() {
        disconnectBrowser()
        if (isSpotifyPlayer()) setSpotifyBrowse(SP_REFUSED) // 설정 화면에 '연동 안 됨' (0.66 부터 Spotify 웹 예비 목록은 없음)
        val app = appLabel(controller?.let { key(it) })
        setHeader(getString(R.string.header_library, app))
        showMessage(getString(R.string.library_unavailable, app))
    }

    private fun showQueue() {
        val c = controller ?: return showMediaApps()
        val queue = c.queue.orEmpty()
        val activeId = c.playbackState?.activeQueueItemId
        setHeader(getString(R.string.header_now_playing, appLabel(key(c))))
        setRows(queue.map {
            val d = it.description
            Row(Kind.QUEUE, d.title?.toString() ?: "", d.subtitle?.toString() ?: "",
                queueId = it.queueId, icon = d.iconBitmap, iconUri = d.iconUri, active = it.queueId == activeId)
        }, getString(R.string.no_queue, appLabel(key(c))))
        scrollToActive()
    }

    /** YouTube Music 지금 재생 목록(다음 트랙) */
    private fun showYtQueue() {
        setHeader(getString(R.string.header_now_playing, YtEngine.Site.MUSIC.label))
        if (YtEngine.loggedIn == false) return showMessage(getString(R.string.yt_need_login))
        if (rows.none { it.kind == Kind.YT_QUEUE }) showMessage(getString(R.string.loading))
        YtEngine.requestQueue(this)
    }

    /** 재생 중인 곡이 보이도록 목록을 스크롤 */
    private fun scrollToActive() {
        val i = rows.indexOfFirst { it.active }
        if (i >= 0) listView.post { listView.setSelection(maxOf(0, i - 1)) }
    }

    /** 미디어 앱(MediaBrowserService 보유) 목록 */
    private fun mediaApps(): List<String> =
        packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }.distinct()
            .filter { it != packageName && packageManager.getLaunchIntentForPackage(it) != null }
            .sortedBy { appLabel(it) }

    /** 세션이 없을 때 리스트 영역: 미디어 앱 목록 → 탭하면 실행 */
    private fun showMediaApps() {
        disconnectBrowser()
        setHeader(getString(R.string.header_apps))
        setRows(mediaApps().map { Row(Kind.APP, appLabel(it), it, id = it) }, getString(R.string.no_media_apps))
    }

    /** 리스트가 닫혀 있거나 분할화면에서도 쓸 수 있게 대화상자로 */
    private fun showMediaAppsDialog() {
        val apps = mediaApps()
        if (apps.isEmpty()) return toast(getString(R.string.no_media_apps))
        AlertDialog.Builder(this)
            .setTitle(R.string.header_apps)
            .setItems(apps.map { appLabel(it) }.toTypedArray()) { _, which ->
                packageManager.getLaunchIntentForPackage(apps[which])?.let { runCatching { startActivity(it) } }
            }
            .show()
    }

    /** 리스트 헤더를 누르면 라이브러리 ↔ 재생 목록 */
    private fun toggleListMode() {
        listMode = if (isLibraryMode()) ListMode.QUEUE else ListMode.LIBRARY
        ytStack.clear()
        followPlayer()
        loadList()
    }

    private fun onRowClick(row: Row) {
        val c = controller
        when (row.kind) {
            Kind.UP -> if (listMode == ListMode.LOCAL) {
                localUp()
            } else if (listMode == ListMode.YOUTUBE) {
                if (ytStack.isNotEmpty()) ytStack.removeAt(ytStack.lastIndex)
                showYtLibrary()
            } else if (spSearchQuery != null) {
                closeSpotifySearch()
            } else if (browsingRecent && browseStack.size <= 1 && isSpotifyPlayer()) {
                loadList() // 최근 들은 곡 → 라이브러리 첫 화면
            } else {
                goUp()
            }
            Kind.YT_HEADER, Kind.YT_GRID, Kind.YT_TABS -> Unit
            Kind.YT_SEARCH -> askYtSearch()
            Kind.SP_SEARCH -> askSearch(SP_RECENT) { spotifySearch(it) }
            Kind.SP_PLAY_QUERY -> row.id?.let { spotifyPlayFromSearch(it) }
            Kind.SP_RECENT -> {
                val folder = row.id
                if (folder != null) browseTo(folder)
                else browser?.serviceComponent?.let { comp ->
                    disconnectBrowser()
                    showMessage(getString(R.string.loading))
                    connectBrowser(comp, recent = true)
                }
            }
            Kind.YT_MYLIB -> {
                ytStack.add(YtPage(YtPageType.MYLIB, "", getString(R.string.yt_my_playlists)))
                showYtLibrary()
            }
            Kind.YT_RETRY -> {
                ytHome = null
                showYtLibrary()
            }
            Kind.YT_PLAYLIST -> row.id?.let {
                ytStack.add(YtPage(YtPageType.PLAYLIST, it, row.title))
                showYtLibrary()
            }
            Kind.YT_BROWSE -> row.id?.let {
                ytStack.add(YtPage(YtPageType.BROWSE, it, row.title, row.extra.orEmpty()))
                showYtLibrary()
            }
            Kind.YT_LIST -> row.id?.let { YtEngine.playPlaylist(this, it) }
            // 곡이 속한 재생목록(라디오·앨범)이 있으면 그 목록을 이어서, 없으면 YouTube Music 이 알아서 이어 줌
            Kind.YT_SONG -> row.id?.let {
                val list = row.extra ?: when (ytTop?.type) {
                    YtPageType.PLAYLIST -> ytTop?.id
                    YtPageType.BROWSE -> ytPageList
                    else -> null
                }
                if (ytVideoMode) YtEngine.playVideo(this, it, row.title, row.subtitle, row.iconUri?.toString().orEmpty())
                else YtEngine.playSong(this, it, list, title = row.title, subtitle = row.subtitle, thumb = row.iconUri?.toString().orEmpty())
            }
            // 전체 재생 = 셔플: 무작위 곡부터 재생하고 셔플을 켠다
            Kind.YT_PLAY_ALL -> row.id?.let { listId ->
                val songs = rows.filter { it.kind == Kind.YT_SONG && it.id != null }
                if (songs.isEmpty()) return@let YtEngine.playPlaylist(this, listId)
                val first = songs.random()
                YtEngine.playSong(this, first.id!!, listId, shuffle = true, title = first.title, subtitle = first.subtitle,
                    thumb = first.iconUri?.toString().orEmpty())
                shuffleOn = true
                btnShuffle.isActivated = true
            }
            Kind.YT_QUEUE -> row.id?.let { YtEngine.playSong(this, it, ytQueueList.ifEmpty { null }, title = row.title, subtitle = row.subtitle) }
            Kind.FOLDER -> row.id?.let {
                folderPlayable[it] = row.playable
                spSearchQuery = null // 검색 결과에서 앨범 · 재생목록으로 들어가면, 위로 = 라이브러리
                browseTo(it)
            }
            Kind.PLAY_ALL -> row.id?.let { playFolderShuffled(it) }
            Kind.LOCAL_FOLDER -> row.id?.let {
                localPath = it
                showLocal()
            }
            Kind.LOCAL_FILE -> playLocalFile(row)
            Kind.LOCAL_PLAY_ALL -> row.id?.let { folder ->
                val songs = LocalEngine.audioUnder(folder)
                if (songs.isNotEmpty()) {
                    LocalEngine.play(this, songs, songs.indices.random(), shuffle = true)
                    shuffleOn = true
                    btnShuffle.isActivated = true
                }
            }
            Kind.PLAYABLE -> row.id?.let { id ->
                if (c == null && isSpotifyPlayer()) withSpotifyController { it.transportControls.playFromMediaId(id, null) }
                else c?.transportControls?.playFromMediaId(id, null)
            }
            Kind.QUEUE -> c?.transportControls?.skipToQueueItem(row.queueId)
            Kind.APP -> row.id?.let { pkg ->
                packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
            }
        }
    }

    private fun setHeader(text: String) {
        listHeader.text = text
    }

    private fun setRows(items: List<Row>, emptyMessage: String) {
        rows = items
        adapter.notifyDataSetChanged()
        emptyText.text = emptyMessage
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMessage(msg: String) {
        rows = emptyList()
        adapter.notifyDataSetChanged()
        emptyText.text = msg
        emptyText.visibility = View.VISIBLE
    }

    // ---- 유틸 ----

    private fun appLabel(pkg: String?): String = if (pkg == LOCAL_KEY) LocalEngine.label
    else if (pkg == BT_PKG) getString(R.string.bt_title)
    else if (pkg == MEDIA_PKG) getString(R.string.radio_title)
    else if (pkg == packageName) YtEngine.site.label else pkg?.let {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
            .getOrDefault(it)
    } ?: ""

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** YouTube 영상 카드 한 줄 (칸 수 = ytGridCols, 마지막 줄의 빈 칸은 자리만) */
    private fun gridView(convertView: View?, row: Row): View {
        val cols = maxOf(ytGridCols, 1)
        val line = (convertView as? LinearLayout)?.takeIf { it.childCount == cols } ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(8))
            repeat(cols) { addView(layoutInflater.inflate(R.layout.item_video_card, this, false)) }
        }
        for (i in 0 until cols) {
            val card = line.getChildAt(i)
            val r = row.cards.getOrNull(i)
            card.visibility = if (r == null) View.INVISIBLE else View.VISIBLE
            if (r == null) continue
            card.findViewById<TextView>(R.id.cardTitle).text = r.title
            card.findViewById<TextView>(R.id.cardSub).apply {
                text = r.subtitle
                visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
            card.findViewById<TextView>(R.id.cardBadge).apply {
                text = r.badge
                visibility = if (r.badge.isEmpty()) View.GONE else View.VISIBLE
            }
            images.load(card.findViewById(R.id.cardThumb), r.icon, r.iconUri, 0)
            card.setOnClickListener { onRowClick(r) }
            card.setOnLongClickListener { onRowLongClick(r) }
        }
        return line
    }

    /** YouTube 영상 첫 화면 위쪽 탭 (row.id = 고른 탭) */
    private fun tabsView(convertView: View?, row: Row): View {
        val bar = convertView as? android.widget.HorizontalScrollView ?: android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(20), dp(10), dp(20), dp(8))
            val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            YT_VIDEO_TABS.forEach { (id, label) ->
                strip.addView(TextView(this@MainActivity).apply {
                    tag = id
                    text = getString(label)
                    textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(getColorStateList(R.color.chip_text))
                    background = getDrawable(R.drawable.bg_chip)
                    setPadding(dp(18), 0, dp(18), 0)
                    if (id == "search") {
                        getDrawable(R.drawable.ic_search)?.mutate()?.let { d ->
                            d.setBounds(0, 0, dp(22), dp(22))
                            d.setTintList(getColorStateList(R.color.chip_text))
                            setCompoundDrawablesRelative(d, null, null, null)
                            compoundDrawablePadding = dp(6)
                        }
                    }
                    setOnClickListener { selectYtVideoTab(id) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(10) })
            }
            addView(strip)
        }
        val strip = bar.getChildAt(0) as LinearLayout
        for (i in 0 until strip.childCount) strip.getChildAt(i).let { it.isActivated = it.tag == row.id }
        return bar
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when (rows[position].kind) {
            Kind.YT_HEADER -> 1
            Kind.YT_GRID -> 2
            Kind.YT_TABS -> 3
            else -> 0
        }
        override fun areAllItemsEnabled() = false
        // 카드 줄 · 탭 줄은 그 안의 칸이 눌림을 받는다
        override fun isEnabled(position: Int) = rows[position].kind !in setOf(Kind.YT_HEADER, Kind.YT_GRID, Kind.YT_TABS)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row.kind == Kind.YT_GRID) return gridView(convertView, row)
            if (row.kind == Kind.YT_TABS) return tabsView(convertView, row)
            if (row.kind == Kind.YT_HEADER) {
                // YouTube Music 칸 제목 (오늘의 발견 · K-Pop hits …)
                val h = convertView as? TextView ?: TextView(this@MainActivity).apply {
                    textSize = 19f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.accent))
                    setPadding(dp(20), dp(18), dp(20), dp(6))
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                h.text = row.title
                return h
            }
            val v = convertView ?: layoutInflater.inflate(R.layout.item_library, parent, false)
            val name = v.findViewById<TextView>(R.id.itemName)
            name.text = row.title
            v.findViewById<TextView>(R.id.itemSub).apply {
                text = row.subtitle
                visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
            // 일반 앱 재생 목록은 재생 상태에서 바로 판단 (곡이 바뀌면 즉시 반영)
            val active = if (row.kind == Kind.QUEUE) row.queueId == controller?.playbackState?.activeQueueItemId
            else row.active
            name.setTextColor(getColor(if (active) R.color.accent else R.color.text))

            val thumb = v.findViewById<ImageView>(R.id.itemThumb)
            when (row.kind) {
                Kind.UP -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_back) }
                Kind.YT_PLAY_ALL, Kind.PLAY_ALL, Kind.LOCAL_PLAY_ALL -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_shuffle) }
                Kind.LOCAL_FOLDER -> { thumb.tag = null; thumb.setImageResource(if (row.playable) R.drawable.ic_logo_usb else R.drawable.ic_folder) }
                Kind.LOCAL_FILE -> { thumb.tag = null; thumb.setImageResource(if (row.queueId == 1L) R.drawable.ic_video else R.drawable.ic_music) }
                Kind.APP -> {
                    thumb.tag = null
                    runCatching { thumb.setImageDrawable(packageManager.getApplicationIcon(row.id!!)) }
                }
                Kind.YT_SEARCH, Kind.SP_SEARCH -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_search) }
                Kind.SP_PLAY_QUERY -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_play) }
                Kind.SP_RECENT -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_timer) }
                Kind.YT_MYLIB -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_library_music) }
                Kind.YT_RETRY -> { thumb.tag = null; thumb.setImageResource(R.drawable.ic_refresh) }
                Kind.FOLDER, Kind.YT_BROWSE -> images.load(thumb, row.icon, row.iconUri, R.drawable.ic_folder)
                else -> images.load(thumb, row.icon, row.iconUri, R.drawable.ic_music)
            }
            return v
        }
    }

    private companion object {
        /** 이용 안내 동의 여부 (문구를 크게 바꾸면 v 를 올려 다시 동의받기) */
        const val TERMS_KEY = "terms_accepted_v1"
        /** 이용 안내에 동의한 versionCode (0.58~ : 버전마다 다시 묻기) */
        const val AGREED_CODE = "agreed_code"
        /** USB/SD 플레이어 구분 키 (D-Player 안의 두 번째 세션) */
        const val LOCAL_KEY = "dplayer.local"
        /** 차의 블루투스 음악 플레이어 (AOSP Bluetooth 앱의 AVRCP 조정 세션) */
        const val BT_PKG = "com.android.bluetooth"
        const val MEDIA_PKG = "com.byd.mediacenter"
        const val RADIO_ACTIVITY = "com.byd.mediacenter.radio.view.RadioActivity"
        /** 라디오 방송 표시로 보이는 글자: FM · AM · MHz · kHz · 89.1 같은 주파수 */
        val RADIO_TEXT = Regex("""(?i)\b(FM|AM)\b|MHz|kHz|\b\d{2,3}\.\d\b""")
        /** Spotify 최근 검색어 저장 이름 */
        const val SP_RECENT = "sp_recent_search"
        /** YouTube 영상 첫 화면 탭: browse id → 이름 (search = 검색 창) */
        val YT_VIDEO_TABS = listOf(
            "search" to R.string.yt_video_search,
            "FEwhat_to_watch" to R.string.yt_video_home,
            "FEsubscriptions" to R.string.yt_video_subs,
            "FEhistory" to R.string.yt_video_history,
            "VLWL" to R.string.yt_video_later,
        )
        const val REQ_STORAGE = 31
        const val RAIL_DP = 0 // 왼쪽 버튼 줄 없음 (재생 목록 / 라이브러리는 재생 버튼 아래)
        const val SPOTIFY = "com.spotify.music"
        /** FLO 재생 엔진 (세션 · 라이브러리 · 미디어 버튼) · 차량 FLO 화면 앱 */
        const val FLO_PKG = "skplanet.musicmate"
        const val FLO_BYD = "com.byd.music.kr"
        /** 위쪽 음악 소스 버튼 (설정에서 고르는 순서 = 버튼 순서) */
        val RAIL_SOURCES = listOf(
            "spotify" to R.string.spotify,
            "flo" to R.string.flo_title,
            "youtube" to R.string.yt_video,
            "usb" to R.string.local_title,
            "bt" to R.string.bt_title,
            "radio" to R.string.radio_title,
            "ytmusic" to R.string.yt_music,
        )
        /** 차량(BYD)용 Spotify 화면 앱. 이 앱이 한 번 떠야 Spotify 엔진(com.spotify.music)이 로그인 상태로 준비된다 */
        const val SPOTIFY_BYD = "com.vivid.music.byd"
        const val SPOTIFY_BYD_SERVICE = "com.vivid.spotify.service.action.MediaManagerService"
        // AndroidX MediaSessionCompat 이 인식하는 셔플 명령 (Media3 도 호환)
        const val COMPAT_SET_SHUFFLE = "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        const val COMPAT_SHUFFLE_ARG = "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE"
    }
}
