package com.dolphin.mplayer.iradio

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.dolphin.mplayer.youtube.YtLog
import com.dolphin.mplayer.youtube.YtService
import java.util.concurrent.Executors

/**
 * 인터넷 라디오 재생 (0.78). ExoPlayer(HLS) + Android MediaSession — LocalEngine 과 같은 방식이라
 * D-Player 플레이 화면 · 핸들 미디어 버튼 · 재생 정보 띠가 그대로 동작한다.
 *
 *  - 다음/이전 = 채널 목록의 다음/이전 방송
 *  - 진행바 = 지금 프로그램이 얼마나 지났는지 (편성 정보가 있는 방송만)
 *  - 끊기면(터널 · 지하 · 주소 만료) 주소를 다시 받아 이어서 재생 (2초 → 최대 30초 간격, 사용자가 멈출 때까지)
 *  - 생방송 끝에서 20초 뒤를 재생해 그만큼 미리 받아 둠 (짧게 끊겨도 소리가 이어지게)
 */
object IRadioEngine : YtService.Source {

    const val TAG = "D-Player Internet Radio"
    const val EXTRA_IRADIO = "dplayer.iradio"
    private const val PREFS = "iradio"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var app: Context
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    @Volatile var station: IRadioApi.Station? = null
        private set
    /** 스트림에 실린 지금 곡 (보내 주는 방송만) */
    var streamTitle: String? = null
        private set
    /** 스트림 형식 (예: "AAC · 128kbps · 48kHz") */
    var streamFormat: String? = null
        private set
    /** 다시 연결 중이면 그 이유 */
    var trouble: String? = null
        private set

    /** 화면 갱신 (main 스레드) */
    var listener: (() -> Unit)? = null

    /** 사용자가 재생을 원하는지 (끊겨도 다시 연결) */
    private var wanted = false
    private var retries = 0
    private var startSeq = 0

    override val playing: Boolean get() = player?.isPlaying == true
    override val label: String get() = "인터넷 라디오"
    override fun currentTitle(): String = station?.let { s -> IRadioApi.cached(s)?.now?.title?.let { "${s.name} · $it" } ?: s.name }.orEmpty()
    override fun sessionToken(): MediaSession.Token? = session?.sessionToken
    val buffering: Boolean get() = wanted && player?.isPlaying != true

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    // ---------- 재생 ----------

    fun play(context: Context, s: IRadioApi.Station) {
        init(context)
        ensurePlayer()
        station = s
        streamTitle = null
        streamFormat = null
        trouble = null
        wanted = true
        retries = 0
        prefs().edit().putString("station", s.id).apply()
        refreshInfo(force = false)
        publishMetadata()
        publishState(buffering = true)
        start(s)
    }

    /** 주소를 받아(백그라운드) 재생. 그 사이 다른 채널을 고르면 버림 */
    private fun start(s: IRadioApi.Station) {
        val seq = ++startSeq
        io.execute {
            val url = runCatching { IRadioApi.streamUrl(s) }
            main.post {
                if (seq != startSeq || station?.id != s.id || !wanted) return@post
                url.onFailure { return@post retry("주소 받기 실패: ${it.message}") }
                val p = ensurePlayer()
                p.setMediaItem(
                    MediaItem.Builder().setUri(url.getOrThrow()).setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(20_000).build())
                        .build()
                )
                p.prepare()
                p.play()
                YtLog.e("인터넷 라디오 재생: ${s.name}")
            }
        }
    }

    private fun retry(why: String) {
        val s = station ?: return
        if (!wanted) return
        trouble = why
        val delay = minOf(30_000L, 2_000L shl minOf(retries, 4))
        retries++
        YtLog.e("인터넷 라디오 끊김 (${s.name}, ${retries}번째): $why → ${delay / 1000}초 뒤 다시 연결")
        publishState(buffering = true)
        listener?.invoke()
        main.removeCallbacks(retryRun)
        main.postDelayed(retryRun, delay)
    }

    private val retryRun = Runnable { station?.let { if (wanted) start(it) } }

    /** 마지막 채널 (없으면 첫 채널) */
    fun lastStation(c: Context): IRadioApi.Station {
        init(c)
        return IRadioApi.station(prefs().getString("station", null)) ?: IRadioApi.STATIONS.first()
    }

    fun lastWasPlaying(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("playing", false)

    override fun play() {
        val s = station ?: return play(app, lastStation(app))
        if (player?.playbackState == Player.STATE_IDLE || trouble != null) return play(app, s)
        wanted = true
        player?.play()
    }

    override fun pause() {
        wanted = false
        main.removeCallbacks(retryRun)
        player?.pause()
        // 생방송은 멈춘 곳부터 이어 듣지 않음 — 다시 누르면 지금 방송부터 (오래 멈추면 주소도 만료)
        player?.stop()
        publishState()
    }

    fun stop() = pause()

    override fun next() = step(1)
    override fun previous() = step(-1)

    private fun step(d: Int) {
        val list = IRadioApi.STATIONS
        val i = list.indexOfFirst { it.id == station?.id }
        play(app, list[((if (i < 0) 0 else i + d) % list.size + list.size) % list.size])
    }

    // ---------- 방송 정보 ----------

    private val infoTick = object : Runnable {
        override fun run() {
            refreshInfo(force = false)
            publishState()
            if (wanted) main.postDelayed(this, 30_000)
        }
    }

    private fun refreshInfo(force: Boolean) {
        val s = station ?: return
        IRadioApi.refresh(s, force) { main.post { if (station?.id == s.id) { publishMetadata(); publishState(); listener?.invoke() } } }
    }

    // ---------- 플레이어 ----------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(app)
            // 끊김에 대비해 넉넉히 (생방송은 끝에서 20초 뒤를 재생하므로 실제로는 그만큼)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, 1_500, 3_000).build())
            .build()
        // 오디오 포커스는 ExoPlayer 가 처리 (다른 앱이 재생하면 멈춤, 내비 안내 때 볼륨 낮춤)
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    retries = 0
                    trouble = null
                    main.removeCallbacks(infoTick)
                    main.postDelayed(infoTick, 30_000)
                } else if (p.playbackState == Player.STATE_READY && !p.playWhenReady) {
                    // 다른 앱이 오디오 포커스를 가져가 멈춘 경우 — 사용자가 멈춘 것과 같게 (다시 연결하지 않음)
                    wanted = false
                }
                prefs().edit().putBoolean("playing", isPlaying || wanted).apply()
                publishState()
                listener?.invoke()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // 생방송이 끝났다고 나오면(주소 만료 등) 다시 연결
                if (state == Player.STATE_ENDED && wanted) retry("방송이 끊김")
                publishState()
            }

            override fun onPlayerError(error: PlaybackException) {
                retry(error.errorCodeName.removePrefix("ERROR_CODE_").lowercase())
            }

            override fun onTracksChanged(tracks: Tracks) {
                val f = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } } ?: return
                streamFormat = listOfNotNull(
                    when {
                        f.codecs?.startsWith("mp4a") == true || f.sampleMimeType == MimeTypes.AUDIO_AAC -> "AAC"
                        f.sampleMimeType == MimeTypes.AUDIO_MPEG -> "MP3"
                        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
                    },
                    (if (f.bitrate > 0) f.bitrate else f.averageBitrate).takeIf { it > 0 }?.let { "${it / 1000}kbps" },
                    f.sampleRate.takeIf { it > 0 }?.let { "${it / 1000}kHz" },
                    f.channelCount.takeIf { it > 0 }?.let { if (it == 1) "모노" else "스테레오" },
                ).joinToString(" · ").ifEmpty { null }
                listener?.invoke()
            }

            override fun onMetadata(metadata: Metadata) {
                // 스트림에 실린 곡 정보 (ICY · ID3) — 보내는 방송만
                var t: String? = null
                var artist: String? = null
                for (i in 0 until metadata.length()) {
                    when (val e = metadata.get(i)) {
                        is IcyInfo -> t = e.title?.takeIf { it.isNotBlank() }
                        is TextInformationFrame -> when (e.id) {
                            "TIT2" -> t = e.values.firstOrNull()
                            "TPE1" -> artist = e.values.firstOrNull()
                        }
                    }
                }
                val text = listOfNotNull(t?.trim()?.takeIf { it.isNotEmpty() }, artist?.trim()?.takeIf { it.isNotEmpty() })
                    .joinToString(" - ").ifEmpty { null } ?: return
                if (text == streamTitle) return
                streamTitle = text
                publishMetadata()
                listener?.invoke()
            }
        })
        player = p
        ensureSession()
        return p
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- MediaSession ----------

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(app, TAG).apply {
            // Android 10 은 MediaController.getTag() 가 없어 이 표시로 인터넷 라디오 세션을 알아본다
            setExtras(android.os.Bundle().apply { putBoolean(EXTRA_IRADIO, true) })
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSkipToQueueItem(id: Long) {
                    IRadioApi.STATIONS.getOrNull(id.toInt())?.let { play(app, it) }
                }
            })
            setQueue(IRadioApi.STATIONS.mapIndexed { i, s ->
                MediaSession.QueueItem(android.media.MediaDescription.Builder().setMediaId(s.id)
                    .setTitle(s.name).setSubtitle(s.freqText).build(), i.toLong())
            })
        }
    }

    private fun publishMetadata() {
        val s = session ?: return
        val st = station ?: return
        val now = IRadioApi.cached(st)?.now
        val logo = IRadioApi.logo(app, st) { main.post { publishMetadata(); listener?.invoke() } }
        s.setMetadata(
            MediaMetadata.Builder()
                // 제목 = 지금 프로그램 (모르면 방송 이름), 가수 칸 = 방송 이름 · 주파수 · 진행자 (또는 스트림의 지금 곡)
                .putString(MediaMetadata.METADATA_KEY_TITLE, now?.title ?: st.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, listOfNotNull(
                    st.name.takeIf { now != null } ?: st.freqText,
                    st.freq.takeIf { now != null && it.isNotEmpty() },
                    now?.dj?.takeIf { it.isNotBlank() }?.let { "진행 $it" },
                    streamTitle?.let { "♪ $it" },
                ).joinToString(" · "))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, st.name)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, now?.timeText.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, now?.takeIf { it.end > it.start }?.let { (it.end - it.start) * 60_000L } ?: 0)
                .apply {
                    (now?.image?.takeIf { it.isNotEmpty() } ?: logo)?.let { putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it) }
                }
                .build()
        )
    }

    private fun publishState(buffering: Boolean = false) {
        val s = session ?: return
        val p = player ?: return
        val state = when {
            p.isPlaying -> PlaybackState.STATE_PLAYING
            buffering || (wanted && (p.playbackState == Player.STATE_BUFFERING || trouble != null)) -> PlaybackState.STATE_BUFFERING
            !wanted -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_BUFFERING
        }
        // 진행바 = 지금 프로그램이 지난 시간
        val now = station?.let { IRadioApi.cached(it)?.now }
        val pos = now?.takeIf { it.end > it.start }?.let {
            val cal = java.util.Calendar.getInstance(IRadioApi.KST)
            var m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            if (m < it.start && m + 1440 < it.end) m += 1440
            ((m - it.start) * 60_000L + cal.get(java.util.Calendar.SECOND) * 1000L).coerceAtLeast(0)
        } ?: 0
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .setActiveQueueItemId(IRadioApi.STATIONS.indexOfFirst { it.id == station?.id }.toLong())
                // 진행은 프로그램 시계라 멈춰 있어도 흐름
                .setState(state, pos, 1f, SystemClock.elapsedRealtime())
                .build()
        )
        if (!s.isActive) s.isActive = true
        YtService.update(app, this, p.isPlaying)
    }
}
