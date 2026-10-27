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
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListenerService::class.java)
    private val prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    private val watched = HashMap<SessionKey, Pair<MediaController, MediaController.Callback>>()
    private var view: TextView? = null
    private var shownMode: Mode? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY) render()
        if (key == POS_X || key == POS_Y) view?.let { if (!dragging) place(it) }
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(sessions.getActiveSessions(component))
        }
        render()
    }

    fun stop() {
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
            val playing = watched.values.map { it.first }
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val text = playing?.let { describe(it) }
            if (mode == Mode.OFF || text == null || !Settings.canDrawOverlays(ctx)) hide() else show(mode, text)
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
        if (runCatching { wm.addView(t, p) }.isSuccess) {
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
