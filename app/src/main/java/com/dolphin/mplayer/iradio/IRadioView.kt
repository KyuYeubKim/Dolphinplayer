package com.dolphin.mplayer.iradio

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dolphin.mplayer.ImageLoader
import com.dolphin.mplayer.R
import com.dolphin.mplayer.SwipeScrollView

/**
 * 인터넷 라디오 화면 (0.78) — 플레이 화면의 앨범 이미지 자리에 ① 지금 방송 정보 카드 ② 채널 목록.
 * 카드: 프로그램 사진 · 방송 이름 · 주파수 · 프로그램 · 진행자 · 시간 · 제작진 · 소개 · 다음 프로그램 · 스트림 형식 · 스트림의 지금 곡.
 * 채널: 로고 · 이름 · 주파수 · 지금 프로그램. 누르면 바로 그 방송.
 */
class IRadioView(
    private val a: Activity,
    private val images: ImageLoader,
    private val onPick: (IRadioApi.Station) -> Unit,
    private val onInfo: (IRadioApi.Station) -> Unit,
    private val onEdit: () -> Unit,
    onSwipe: () -> Unit,
) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    val view = SwipeScrollView(a, onSwipe).apply { addView(box) }

    private val dim = 0xFF8E99A8.toInt()
    private val text = 0xFFEEF3F8.toInt()

    /** 다시 그림. 화면에 안 보이면 부르지 않아도 됨 */
    fun render() {
        box.removeAllViews()
        val cur = IRadioEngine.station
        if (cur != null) box.addView(card(cur), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        box.addView(TextView(a).apply {
            setText(R.string.iradio_channels)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(a.getColor(R.color.accent))
            setPadding(dp(2), 0, 0, dp(6))
        })
        val mine = IRadioApi.mine(a)
        val favs = IRadioApi.favorites(a)
        mine.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 2) {
                val cell = pair.getOrNull(i)?.let { tile(it, it.id == cur?.id, it.id in favs) } ?: View(a)
                row.addView(cell, LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginStart = if (i == 0) 0 else dp(8) })
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        // 채널 편집 (즐겨찾기 · 순서 · 숨기기 · 지역 방송 추가, 0.80)
        box.addView(TextView(a).apply {
            setText(R.string.iradio_edit)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(a.getColor(R.color.accent))
            background = bg(false)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onEdit()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        // 화면에 보이는 채널의 지금 프로그램을 받아 둠 (받으면 다시 그림)
        mine.forEach { s -> IRadioApi.refreshIfStale(s) { later() } }
    }

    private val again = Runnable { render() }

    /** 정보 · 로고가 잇달아 도착하면 한 번에 다시 그림 */
    private fun later() {
        view.removeCallbacks(again)
        view.postDelayed(again, 300)
    }

    private fun bg(on: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        if (on) {
            setColor(0x401DB954)
            setStroke(dp(2), a.getColor(R.color.accent))
        } else {
            setColor(0xFF16233A.toInt())
            setStroke(dp(1), 0xFF2C4466.toInt())
        }
    }

    private fun label(t: CharSequence, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(a).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun logoView(s: IRadioApi.Station, size: Int): ImageView = ImageView(a).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        val url = IRadioApi.logo(a, s) { later() }
        if (url != null) images.load(this, null, Uri.parse(url), R.drawable.ic_logo_iradio) else setImageResource(R.drawable.ic_logo_iradio)
    }

    /** 지금 방송 카드 */
    private fun card(s: IRadioApi.Station): View {
        val info = IRadioApi.cached(s)
        val now = info?.now
        return LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pic = ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF0F1A2B.toInt()) }
                }
                val picUrl = now?.image?.takeIf { it.isNotEmpty() } ?: IRadioApi.logo(a, s) { later() }
                if (picUrl != null) images.load(pic, null, Uri.parse(picUrl), R.drawable.ic_logo_iradio) else pic.setImageResource(R.drawable.ic_logo_iradio)
                addView(pic, LinearLayout.LayoutParams(dp(78), dp(78)).apply { marginEnd = dp(12) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("${s.name} · ${s.freqText}", 12.5f, a.getColor(R.color.accent), bold = true))
                    addView(label(now?.title ?: s.about, 17f, text, bold = true, lines = 2))
                    listOfNotNull(
                        now?.dj?.takeIf { it.isNotBlank() }?.let { a.getString(R.string.iradio_dj, it) },
                        now?.timeText?.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { addView(label(it, 13f, 0xFFDCE4EE.toInt())) }
                    (if (IRadioEngine.trouble != null && IRadioEngine.station?.id == s.id) a.getString(R.string.iradio_reconnecting)
                    else IRadioEngine.streamTitle?.let { "♪ $it" })?.let { addView(label(it, 13f, 0xFF6FB2FF.toInt())) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            now?.subtitle?.takeIf { it.isNotBlank() }?.let { addView(label(it, 13f, 0xFFDCE4EE.toInt(), lines = 2).apply { setPadding(0, dp(8), 0, 0) }) }
            now?.about?.takeIf { it.isNotBlank() }?.let { addView(label(it, 12.5f, dim, lines = 3).apply { setPadding(0, dp(6), 0, 0) }) }
            now?.staff?.takeIf { it.isNotBlank() }?.let { addView(label(a.getString(R.string.iradio_staff, it), 12f, dim).apply { setPadding(0, dp(4), 0, 0) }) }
            info?.next?.let { n ->
                addView(label(a.getString(R.string.iradio_next, IRadioApi.hm(n.start), n.title +
                    (n.dj.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")), 12.5f, 0xFFDCE4EE.toInt()).apply { setPadding(0, dp(6), 0, 0) })
            }
            IRadioEngine.streamFormat?.takeIf { IRadioEngine.station?.id == s.id }?.let {
                addView(label(a.getString(R.string.iradio_stream, it), 11.5f, dim).apply { setPadding(0, dp(4), 0, 0) })
            }
            setOnClickListener { onInfo(s) }
        }
    }

    private fun tile(s: IRadioApi.Station, on: Boolean, fav: Boolean): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg(on)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(logoView(s, 30).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label((if (fav) "★ " else "") + s.name, 14f, if (on) a.getColor(R.color.accent) else text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(s.freqText.removePrefix("FM ").removePrefix("AM "), 11f, dim).apply { setPadding(dp(4), 0, 0, 0) })
            })
            val now = IRadioApi.cached(s)?.now
            addView(label(now?.title ?: s.about.substringAfter(" · ", s.about), 11.5f, 0xFFDCE4EE.toInt()))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        contentDescription = "${s.name} ${s.freqText}"
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onPick(s)
        }
        setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onInfo(s)
            true
        }
    }
}
