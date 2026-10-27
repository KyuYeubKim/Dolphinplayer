package com.dolphin.mplayer.local

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.dolphin.mplayer.R

/**
 * 영상 전체화면. USB/SD(LocalEngine) 또는 YouTube(YtEngine, 0.66) 의 플레이어를 이 화면에 붙여 보여준다.
 * 화면을 누르면 조작 버튼이 나왔다가 몇 초 뒤 사라진다. 나가면 USB 영상은 일시정지, YouTube 는 소리만 계속.
 */
class VideoActivity : Activity() {

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_YT = "yt"
    }

    private lateinit var src: VideoSource
    private lateinit var loading: View

    private val main = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var controls: View
    private lateinit var title: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private var dragging = false

    private val hideControls = Runnable { controls.visibility = View.GONE }

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
        surface = findViewById(R.id.videoSurface)
        controls = findViewById(R.id.videoControls)
        title = findViewById(R.id.videoTitle)
        btnPlay = findViewById(R.id.videoPlay)
        seek = findViewById(R.id.videoSeek)
        time = findViewById(R.id.videoTime)
        loading = findViewById(R.id.videoLoading)

        src = if (intent.getStringExtra(EXTRA_SOURCE) == SOURCE_YT) com.dolphin.mplayer.youtube.YtEngine.videoSource
        else LocalEngine.videoSource
        src.attach(surface)
        findViewById<View>(R.id.videoBack).setOnClickListener { finish() }
        findViewById<View>(R.id.videoRoot).setOnClickListener { toggleControls() }
        btnPlay.setOnClickListener {
            if (src.playing) src.pause() else src.play()
            showControls()
        }
        findViewById<View>(R.id.videoBack10).setOnClickListener { jump(-10_000) }
        findViewById<View>(R.id.videoFwd10).setOnClickListener { jump(10_000) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
                main.removeCallbacks(hideControls)
            }
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
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        src.detach(surface)
    }

    private fun jump(deltaMs: Long) {
        val pos = src.position() + deltaMs
        src.seekTo(pos.coerceAtLeast(0))
        showControls()
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls()
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, 4000)
        render()
    }

    /** 영상 원래 비율 유지 (화면에 꽉 차게, 남는 곳은 검은 여백) */
    private fun fitVideo() {
        val aspect = src.videoAspect()
        val root = findViewById<View>(R.id.videoRoot)
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
        title.text = src.title()
        loading.visibility = if (src.loading()) View.VISIBLE else View.GONE
        btnPlay.setImageResource(if (src.playing) R.drawable.ic_pause else R.drawable.ic_play)
        val dur = src.duration()
        val pos = src.position()
        if (!dragging) {
            seek.max = dur.coerceAtLeast(1).toInt()
            seek.progress = pos.coerceIn(0, dur.coerceAtLeast(0)).toInt()
        }
        time.text = "${fmt(pos)} / ${fmt(dur)}"
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
