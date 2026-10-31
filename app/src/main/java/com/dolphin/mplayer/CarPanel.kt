package com.dolphin.mplayer

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 재생 중 차량 제어 패널 (0.61) — 음악이 재생 중일 때 앨범 이미지 자리에 DolphinControl 하단바에서 고른 기능을 보여 준다.
 * 차량 제어·상태 읽기는 전부 DolphinControl 이 한다 (content://com.dolphin.control.car, 같은 서명 키 앱만 부를 수 있음).
 * D-Player 는 화면만 그리고, 누르면 DolphinControl 에 실행을 부탁한다. 썬쉐이드는 하단바처럼 길게 눌러야 실행
 * (창문은 0.71 부터 한 번 누르면 실행 — 사용자 요청). 패널을 좌우로 밀면 앨범 이미지로 (onSwipe).
 * 패널이 보이는 동안만 DolphinControl 이 차 상태를 자주 읽도록 watch 를 보낸다 (60초마다 갱신, 끊기면 DC 가 90초 뒤 스스로 해제).
 */
class CarPanel(private val a: Activity, private val onUnavailable: () -> Unit, private val onSwipe: () -> Unit) {

    companion object {
        const val DC_PKG = "com.dolphin.control"
        private const val AUTHORITY = "com.dolphin.control.car"
        private val URI: Uri = Uri.parse("content://$AUTHORITY/state")
        private const val PREF = "car_panel"

        fun isEnabled(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean(PREF, false)
        fun setEnabled(c: Context, on: Boolean) =
            c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

        /** 설치된 DolphinControl 이 제어 통로를 가지고 있는지 (1.118~) */
        fun supported(c: Context) =
            runCatching { c.packageManager.resolveContentProvider(AUTHORITY, 0) != null }.getOrDefault(false)

        /** 진단 로그 한 줄 (차량은 건드리지 않음) */
        fun describe(c: Context) = "차량 제어 패널: 설정=${if (isEnabled(c)) "켜짐" else "꺼짐"} · " +
            "DolphinControl 통로=${if (supported(c)) "있음" else "없음 (DolphinControl 미설치 또는 예전 버전)"}"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val columnsRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    val view: ScrollView = object : ScrollView(a) {
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop

        // 옆으로 미는 움직임이 보이면 누르던 타일은 취소(스치며 기능이 실행되지 않게)하고 손을 떼면 앨범 이미지로
        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false }
                android.view.MotionEvent.ACTION_MOVE -> if (!swiping) {
                    val dx = kotlin.math.abs(e.rawX - downX)
                    val dy = kotlin.math.abs(e.rawY - downY)
                    if (dx > slop * 2 && dx > dy * 1.5f) {
                        swiping = true
                        val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                android.view.MotionEvent.ACTION_UP -> if (swiping) {
                    swiping = false
                    if (kotlin.math.abs(e.rawX - downX) > dp(60)) onSwipe()
                    return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> if (swiping) { swiping = false; return true }
            }
            return swiping || super.dispatchTouchEvent(e)
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(columnsRow)
    }

    private var shown = false
    private var structure = ""
    private val tiles = HashMap<String, Tile>()
    private var dcContext: Context? = null

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, 150)
        }
    }
    private val reload = Runnable { load() }
    private val renew = object : Runnable {
        override fun run() {
            if (!shown) return
            io.execute { call("watch", null, Bundle().apply { putBoolean("on", true) }) }
            main.postDelayed(this, 60_000)
        }
    }

    fun show() {
        if (shown) return
        shown = true
        runCatching { a.contentResolver.registerContentObserver(URI, false, observer) }
        renew.run()
        load()
    }

    fun hide() {
        if (!shown) return
        shown = false
        main.removeCallbacks(renew)
        main.removeCallbacks(reload)
        runCatching { a.contentResolver.unregisterContentObserver(observer) }
        io.execute { call("watch", null, Bundle().apply { putBoolean("on", false) }) }
    }

    private fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        runCatching { a.contentResolver.call(URI, method, arg, extras) }.getOrNull()

    private fun load() {
        io.execute {
            val b = call("list", null, null)
            main.post {
                if (!shown) return@post
                val items = b?.let { runCatching { it.getParcelableArrayList<Bundle>("items") }.getOrNull() }
                if (b == null || b.getInt("api") < 1 || items.isNullOrEmpty()) onUnavailable() else render(b, items)
            }
        }
    }

    private fun run(key: String, label: String) {
        io.execute {
            val r = call("run", key, null)
            main.post {
                when {
                    r == null -> toast(a.getString(R.string.car_no_dc))
                    !r.getBoolean("ok") -> toast(r.getString("error")?.let { a.getString(R.string.car_failed_why, label, it) }
                        ?: a.getString(R.string.car_failed, label))
                }
                if (shown) load()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()

    // ---- 화면 ----

    private fun render(b: Bundle, items: List<Bundle>) {
        val columns = b.getStringArray("columns") ?: arrayOf("HEAT", "WINDOW", "AIR")
        val titles = b.getStringArray("columnTitles")
        val colors = b.getIntArray("columnColors")
        val sig = columns.joinToString(",") + "|" + items.joinToString(",") { it.getString("key").orEmpty() }
        if (sig != structure) {
            // 고른 기능이 바뀌었을 때만 새로 짓는다 (상태만 바뀌면 그 자리에서 고쳐 길게 누르는 중에도 끊기지 않게)
            structure = sig
            tiles.clear()
            columnsRow.removeAllViews()
            dcContext = runCatching { a.createPackageContext(DC_PKG, 0) }.getOrNull()
            columns.forEachIndexed { i, cat ->
                val inCat = items.filter { it.getString("category") == cat }
                if (inCat.isEmpty()) return@forEachIndexed
                val accent = colors?.getOrNull(i) ?: 0xFF6FB2FF.toInt()
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(a).apply {
                    text = titles?.getOrNull(i) ?: cat
                    setTextColor(accent)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(4), 0, 0, dp(6))
                })
                inCat.forEach { item ->
                    val t = Tile(item.getString("key").orEmpty(), accent)
                    tiles[t.key] = t
                    col.addView(t.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                columnsRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = if (columnsRow.childCount == 0) 0 else dp(8)
                })
            }
        }
        items.forEach { tiles[it.getString("key")]?.update(it) }
    }

    /** 아이콘 상자 + 이름 + 상태 (DolphinControl 펼친 하단바 타일과 같은 모양) */
    private inner class Tile(val key: String, private val columnColor: Int) {
        private val iconBox = FrameLayout(a)
        private val icon = ImageView(a)
        // 이름이 길면 두 줄 (0.64) — 띄어쓰기에서만 줄을 바꿔 '운전석 열/선' 처럼 낱말 가운데서 끊기지 않게
        private val label = TextView(a).apply {
            setTextColor(0xFFDCE4EE.toInt())
            textSize = 11.5f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 0.95f)
        }
        private val state = TextView(a).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        private var iconRes = 0
        private var name = ""
        private var longPress = false

        val view = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            iconBox.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            addView(iconBox, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label, LinearLayout.LayoutParams(-1, -2))
                addView(state, LinearLayout.LayoutParams(-1, dp(23)))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFFFFFFFF.toInt()) })
            isHapticFeedbackEnabled = true
            setOnClickListener {
                if (longPress) toast(a.getString(R.string.car_hold, name))
                else {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    run(key, name)
                }
            }
            setOnLongClickListener {
                if (!longPress) return@setOnLongClickListener false
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                run(key, name)
                true
            }
        }

        fun update(b: Bundle) {
            name = b.getString("label").orEmpty()
            // DolphinControl 은 창문 칸 전체를 길게 누르게 하지만, 패널에서는 썬쉐이드만 (창문은 한 번 누름, 0.71)
            longPress = b.getBoolean("longPress") && key.startsWith("SUNSHADE")
            val on = b.getBoolean("on")
            val momentary = b.getBoolean("momentary")
            val accent = if (on) b.getInt("color", columnColor) else columnColor
            // 글자 사이에 줄바꿈 금지 문자(U+2060)를 넣어 띄어쓰기에서만 줄이 바뀌게
            label.text = name.split(' ').joinToString(" ") { w -> w.toList().joinToString("\u2060") }
            state.text = b.getString("state").orEmpty()
            view.contentDescription = name + " " + state.text
            view.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (on) {
                    setColor((accent and 0x00FFFFFF) or 0x40000000)
                    setStroke(dp(2), accent)
                } else {
                    setColor(0xFF16233A.toInt())
                    setStroke(dp(1), 0xFF2C4466.toInt())
                }
            }
            iconBox.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (on) (accent and 0x00FFFFFF) or 0x55000000 else 0xFF22324A.toInt())
            }
            val res = b.getInt("icon")
            if (res != iconRes) {
                iconRes = res
                icon.setImageDrawable(loadIcon(res))
            }
            icon.imageTintList = if (b.getBoolean("colorIcon")) null
            else ColorStateList.valueOf(if (on) accent else if (momentary) 0xFF6FB2FF.toInt() else 0xFFDCE4EE.toInt())
            state.setTextColor(if (on) accent else if (momentary) 0xFFEEF3F8.toInt() else 0xFF8E99A8.toInt())
        }
    }

    private fun loadIcon(res: Int): Drawable =
        runCatching { dcContext?.getDrawable(res)?.mutate() }.getOrNull() ?: ColorDrawable(0)
}
