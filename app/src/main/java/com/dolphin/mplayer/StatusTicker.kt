package com.dolphin.mplayer

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.dolphin.mplayer.youtube.YtLog

/**
 * 지금 재생 중인 곡(제목 · 가수)을 반투명 띠로 띄운다 (설정 → 재생 → 재생 정보 표시).
 * 어떤 앱이 재생하든(Spotify · YT Music · USB 등) 재생 중인 세션을 따라간다.
 *
 * '다른 앱 위에 표시' 창이라 분할 화면 · 다른 앱 화면 · D-Player 가 뒤에 있을 때도 보인다.
 * 알림 접근 서비스(MediaListenerService) 안에서 돌아 D-Player 화면과 상관없이 동작한다.
 * 띠를 끌어서 원하는 위치로 옮길 수 있고(위치는 화면 비율로 저장), 탭하면 D-Player 를 연다.
 * (Android 8+ 에서 이 창은 시스템 바 아래층이라 차량 상단 바 · 하단 바 위치에서는 가려질 수 있다)
 */
class StatusTicker(private val ctx: Context) {

    enum class Mode(val key: String) { OFF("off"), BELOW("below"), BOTTOM("bottom") }

    companion object {
        const val PREF_KEY = "status_ticker"

        fun mode(c: Context): Mode {
            val k = c.getSharedPreferences("player", Context.MODE_PRIVATE).getString(PREF_KEY, Mode.OFF.key)
            // 설정은 켜기/끄기 하나 (켜면 화면 아래). 예전에 고른 다른 위치도 켜짐으로 본다
            return if (k == null || k == Mode.OFF.key) Mode.OFF else Mode.BOTTOM
        }

        fun setMode(c: Context, m: Mode) =
            c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString(PREF_KEY, m.key).apply()

        /** 띠 위치 (화면 가로 · 세로 비율, 띠 가운데 기준) */
        const val POS_X = "status_ticker_fx"
        const val POS_Y = "status_ticker_fy"

        fun resetPosition(c: Context) =
            c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().remove(POS_X).remove(POS_Y).apply()

        /** 권한을 막 받았을 때 등: 곡이 바뀌지 않아도 다시 그리게 (0.75) */
        const val REFRESH = "status_ticker_refresh"

        fun refresh(c: Context) =
            c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putLong(REFRESH, System.currentTimeMillis()).apply()

        /** 진단용: 띠가 돌고 있는지 · 마지막으로 그린 결과 (0.75) */
        @Volatile var running = false
            private set
        @Volatile var lastState = "아직 안 그림"
            private set

        fun diag(c: Context) = "재생 정보 표시: 설정=${if (mode(c) == Mode.OFF) "꺼짐" else "켜짐"} · " +
            "다른 앱 위 권한=${if (Settings.canDrawOverlays(c)) "있음" else "없음"} · " +
            "띠 서비스=${if (running) "돌고 있음" else "안 돎"} · 마지막=$lastState"
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListenerService::class.java)
    private val prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val watched = HashMap<SessionKey, Pair<MediaController, MediaController.Callback>>()
    private var view: TextView? = null
    private var shownMode: Mode? = null
    private var shownKey: SessionKey? = null
    private val LOADING = setOf(
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY || key == REFRESH) render()
        if (key == POS_X || key == POS_Y) view?.let { if (!dragging) place(it) }
    }

    fun start() {
        running = true
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(sessions.getActiveSessions(component))
        }
        render()
    }

    fun stop() {
        running = false
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        watched.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
        hide()
    }

    /** 재생 세션마다 상태·곡 정보 변화를 받아 바로 다시 그린다 */
    private fun watch(list: List<MediaController>?) {
        val now = (list ?: emptyList()).associateBy { SessionKey(it) }
        (watched.keys - now.keys).forEach { k -> watched.remove(k)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } } }
        now.forEach { (k, c) ->
            if (k in watched) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    SpotifyMemory.onState(ctx, c.packageName, state?.state) // 시동 켜면 Spotify 이어서 재생용 (0.64)
                    render()
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) = render()
                override fun onSessionDestroyed() = render()
            }
            c.registerCallback(cb, main)
            watched[k] = c to cb
            SpotifyMemory.onState(ctx, c.packageName, c.playbackState?.state)
        }
        render()
    }

    private fun render() {
        main.post {
            val mode = mode(ctx)
            // 채널 · 곡을 바꾸는 동안(받는 중)은 보이던 띠를 그대로 둔다 (0.79)
            val hit = watched.entries.firstOrNull { it.value.first.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: watched.entries.firstOrNull { it.key == shownKey && it.value.first.playbackState?.state in LOADING }
            shownKey = hit?.key
            val playing = hit?.value?.first
            val text = playing?.let { describe(it) }
            val canDraw = Settings.canDrawOverlays(ctx)
            if (mode == Mode.OFF || text == null || !canDraw) hide() else show(mode, text)
            val state = when {
                mode == Mode.OFF -> "꺼짐"
                playing == null -> "재생 중인 앱 없음 (세션 ${watched.size}개)"
                text == null -> "${playing.packageName} 곡 정보 없음"
                !canDraw -> "다른 앱 위 권한 없음"
                view == null -> "창 띄우기 실패 ($addError)"
                else -> "보임 (${playing.packageName})"
            }
            if (state != lastState) {
                lastState = state
                YtLog.e("재생 정보 띠: $state")
            }
        }
    }

    private fun describe(c: MediaController): String? {
        val m = c.metadata ?: return null
        val title = m.description?.title?.toString() ?: m.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title.isNullOrBlank()) return null
        val artist = m.description?.subtitle?.toString() ?: m.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return if (artist.isNullOrBlank()) "♪  $title" else "♪  $title  ·  $artist"
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var lp: WindowManager.LayoutParams? = null
    private var addError: String? = null
    private var dragging = false

    private fun screenSize(): android.graphics.Point = android.graphics.Point().also {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(it)
    }

    /** 저장된 비율 위치(없으면 화면 가운데 아래, 차량 하단 바보다 위)로 창을 옮긴다 */
    private fun place(v: TextView) {
        val p = lp ?: return
        val sz = screenSize()
        val w = v.width.takeIf { it > 0 } ?: v.measuredWidth
        val h = v.height.takeIf { it > 0 } ?: dp(32)
        val fx = prefs.getFloat(POS_X, 0.5f)
        val fy = prefs.getFloat(POS_Y, 0.84f)
        p.x = (sz.x * fx - w / 2f).toInt().coerceIn(0, (sz.x - w).coerceAtLeast(0))
        p.y = (sz.y * fy - h / 2f).toInt().coerceIn(0, (sz.y - h).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun show(@Suppress("UNUSED_PARAMETER") mode: Mode, text: String) {
        val v = view
        if (v != null) {
            if (v.text != text) {
                v.text = text
                v.isSelected = false // 새 곡이면 다시 두 번 흐르게
                v.isSelected = true
                v.post { if (!dragging) place(v) } // 글자 길이가 바뀌어도 같은 가운데 위치
            }
            return
        }
        val t = TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = 2 // 계속 흐르면 다른 앱 위에서 매 프레임 다시 그림 → 두 번만
            isSelected = true // 긴 제목은 흐르게
            maxWidth = (screenSize().x * 0.4f).toInt()
            setPadding(dp(16), 0, dp(16), 0)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            background = GradientDrawable().apply {
                setColor(0xA0000000.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(36),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "D-Player now playing"
        }
        lp = p
        t.setOnTouchListener(DragListener(t))
        val added = runCatching { wm.addView(t, p) }
        addError = added.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        if (added.isSuccess) {
            view = t
            shownMode = Mode.BOTTOM
            t.post { place(t) }
        }
    }

    /** 끌기 = 옮기기 (놓으면 위치 저장), 짧게 탭 = D-Player 열기 */
    private inner class DragListener(private val v: TextView) : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        override fun onTouch(view: android.view.View, e: android.view.MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val sz = screenSize()
                        p.x = (startX + dx).toInt().coerceIn(0, (sz.x - v.width).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (sz.y - v.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val sz = screenSize()
                        prefs.edit()
                            .putFloat(POS_X, (p.x + v.width / 2f) / sz.x)
                            .putFloat(POS_Y, (p.y + v.height / 2f) / sz.y)
                            .apply()
                        dragging = false
                    } else {
                        runCatching {
                            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        lp = null
        dragging = false
        shownMode = null
    }
}

/** MediaController 는 equals 가 없어 세션 토큰으로 같은 세션인지 구분 */
private data class SessionKey(val token: android.media.session.MediaSession.Token) {
    constructor(c: MediaController) : this(c.sessionToken)
}
