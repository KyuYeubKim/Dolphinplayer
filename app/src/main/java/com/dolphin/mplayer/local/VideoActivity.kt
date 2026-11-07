package com.dolphin.mplayer.local

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.dolphin.mplayer.R

/**
 * 영상 전체화면. USB/SD(LocalEngine) 또는 YouTube(YtEngine, 0.66) 의 플레이어를 이 화면에 붙여 보여준다.
 * 0.81 부터 YouTube 앱처럼: 화면을 누르면 어두워지며 가운데 재생 · 양옆 10초 버튼, 아래 빨간 재생 막대가 나오고
 * 재생 중이면 몇 초 뒤 사라진다 (멈춰 있으면 그대로). 양옆을 두 번 누르면 10초씩 이동, ⚙ 로 화질 (YouTube 만).
 * 나가면 USB 영상은 일시정지, YouTube 는 소리만 계속.
 * DolphinControl 1.139~ 가 이 화면 이름(com.dolphin.mplayer.local.VideoActivity)으로 '영상'을 알아보고 분할을 영상 비율로 바꾼다
 * (DC Prefs.DPLAYER_VIDEO) — 클래스 이름 · 패키지를 바꾸면 DC 도 같이.
 */
class VideoActivity : Activity() {

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_YT = "yt"
        private const val HIDE_MS = 4000L
    }

    private lateinit var src: VideoSource
    private lateinit var loading: View

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: View
    private lateinit var surface: SurfaceView
    private lateinit var controls: View
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var quality: TextView
    private lateinit var settings: View
    private lateinit var qualityScrim: View
    private lateinit var qualityPanel: LinearLayout
    private lateinit var seekLeft: TextView
    private lateinit var seekRight: TextView
    private var dragging = false
    private var hideAt = 0L

    /** 두 번 누르기로 이동한 양 (같은 쪽을 계속 누르면 10초씩 더함) */
    private var seekSide = 0
    private var seekSum = 0
    private val hideSeekHint = Runnable {
        seekLeft.visibility = View.GONE
        seekRight.visibility = View.GONE
        seekSide = 0
        seekSum = 0
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = findViewById(R.id.videoRoot)
        surface = findViewById(R.id.videoSurface)
        controls = findViewById(R.id.videoControls)
        title = findViewById(R.id.videoTitle)
        sub = findViewById(R.id.videoSub)
        btnPlay = findViewById(R.id.videoPlay)
        seek = findViewById(R.id.videoSeek)
        time = findViewById(R.id.videoTime)
        quality = findViewById(R.id.videoQuality)
        settings = findViewById(R.id.videoSettings)
        qualityScrim = findViewById(R.id.videoQualityScrim)
        qualityPanel = findViewById(R.id.videoQualityPanel)
        seekLeft = findViewById(R.id.videoSeekLeft)
        seekRight = findViewById(R.id.videoSeekRight)
        loading = findViewById(R.id.videoLoading)

        src = if (intent.getStringExtra(EXTRA_SOURCE) == SOURCE_YT) com.dolphin.mplayer.youtube.YtEngine.videoSource
        else LocalEngine.videoSource
        src.attach(surface)
        findViewById<View>(R.id.videoBack).setOnClickListener { finish() }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = root.width
                when {
                    e.x < w / 3f -> seekBy(-1)
                    e.x > w * 2 / 3f -> seekBy(1)
                    else -> toggleControls()
                }
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
        btnPlay.setOnClickListener {
            when {
                src.ended() -> { src.seekTo(0); src.play() }
                src.playing -> src.pause()
                else -> src.play()
            }
            showControls()
        }
        findViewById<View>(R.id.videoBack10).setOnClickListener { jump(-10_000); showControls() }
        findViewById<View>(R.id.videoFwd10).setOnClickListener { jump(10_000); showControls() }
        settings.setOnClickListener { openQuality() }
        quality.setOnClickListener { openQuality() }
        qualityScrim.setOnClickListener { closeQuality() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = timeText(progress.toLong(), src.duration())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                src.seekTo(sb.progress.toLong())
                showControls()
            }
        })
        showControls()
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
        com.dolphin.mplayer.StatusTicker.videoOnTop = true
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
        com.dolphin.mplayer.StatusTicker.videoOnTop = false
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        src.detach(surface)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (qualityScrim.visibility == View.VISIBLE) closeQuality() else super.onBackPressed()
    }

    private fun jump(deltaMs: Long) {
        val dur = src.duration()
        val pos = (src.position() + deltaMs).coerceAtLeast(0)
        src.seekTo(if (dur > 0) pos.coerceAtMost(dur) else pos)
    }

    /** 양옆 두 번 누르기: YouTube 처럼 그쪽에 '◀◀ 10초' 를 잠깐 보여 주고, 계속 누르면 10초씩 더 */
    private fun seekBy(dir: Int) {
        if (src.live()) return
        if (seekSide != dir) seekSum = 0
        seekSide = dir
        seekSum += 10
        jump(dir * 10_000L)
        val (on, off) = if (dir < 0) seekLeft to seekRight else seekRight to seekLeft
        off.visibility = View.GONE
        on.text = getString(if (dir < 0) R.string.video_seek_back else R.string.video_seek_fwd, seekSum)
        on.visibility = View.VISIBLE
        main.removeCallbacks(hideSeekHint)
        main.postDelayed(hideSeekHint, 900)
        render()
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls()
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        hideAt = SystemClock.elapsedRealtime() + HIDE_MS
        render()
    }

    // ---------- 화질 (YouTube) ----------

    private fun openQuality() {
        val list = src.qualities()
        if (list.isEmpty()) return
        qualityPanel.removeAllViews()
        val dp = resources.displayMetrics.density
        fun row(text: String, size: Float, color: Int, height: Int) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (height * dp).toInt())
        }
        qualityPanel.addView(row(getString(R.string.video_quality_now, src.qualityLabel().ifEmpty { "-" }), 16f, 0xCCFFFFFF.toInt(), 52))
        val chosen = src.quality()
        for (h in list) {
            val label = if (h == src.defaultQuality()) getString(R.string.video_quality_default, "${h}p") else "${h}p"
            qualityPanel.addView(row((if (h == chosen) "✓   " else "      ") + label, 20f, Color.WHITE, 60).apply {
                if (h == chosen) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_yt_row)
                setOnClickListener {
                    closeQuality()
                    if (h != chosen) src.setQuality(h)
                }
            })
        }
        qualityPanel.addView(row(getString(R.string.video_quality_note), 14f, 0x99FFFFFF.toInt(), 52))
        qualityScrim.visibility = View.VISIBLE
    }

    private fun closeQuality() {
        qualityScrim.visibility = View.GONE
        showControls()
    }

    /** 영상 원래 비율 유지 (화면에 꽉 차게, 남는 곳은 검은 여백) */
    private fun fitVideo() {
        val aspect = src.videoAspect()
        val w = root.width
        val h = root.height
        if (aspect <= 0f || w == 0 || h == 0) return
        val (vw, vh) = if (w.toFloat() / h > aspect) (h * aspect).toInt() to h else w to (w / aspect).toInt()
        val lp = surface.layoutParams
        if (lp.width != vw || lp.height != vh) {
            lp.width = vw
            lp.height = vh
            surface.layoutParams = lp
        }
    }

    private fun render() {
        fitVideo()
        // 재생 중에만 저절로 숨김 (멈춰 있거나 막대를 끄는 중 · 화질 고르는 중이면 그대로)
        if (controls.visibility == View.VISIBLE && (src.playing || src.loading()) && !dragging && qualityScrim.visibility != View.VISIBLE &&
            SystemClock.elapsedRealtime() > hideAt) controls.visibility = View.GONE
        title.text = src.title()
        val subText = src.subtitle()
        sub.text = subText
        sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
        val busy = src.loading()
        loading.visibility = if (busy) View.VISIBLE else View.GONE
        btnPlay.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        btnPlay.setImageResource(when {
            src.ended() -> R.drawable.ic_replay
            src.playing -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        })
        val hasQuality = src.qualities().isNotEmpty()
        settings.visibility = if (hasQuality) View.VISIBLE else View.GONE
        val label = src.qualityLabel()
        quality.text = label
        quality.visibility = if (hasQuality && label.isNotEmpty()) View.VISIBLE else View.GONE
        val dur = src.duration()
        val pos = src.position()
        val live = src.live()
        seek.visibility = if (live) View.INVISIBLE else View.VISIBLE
        if (!dragging) {
            seek.max = dur.coerceAtLeast(1).toInt()
            seek.progress = pos.coerceIn(0, dur.coerceAtLeast(0)).toInt()
            seek.secondaryProgress = src.buffered().coerceIn(0, dur.coerceAtLeast(0)).toInt()
            time.text = if (live) getString(R.string.video_live) else timeText(pos, dur)
        }
    }

    private fun timeText(pos: Long, dur: Long) = "${fmt(pos)} / ${fmt(dur)}"

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
