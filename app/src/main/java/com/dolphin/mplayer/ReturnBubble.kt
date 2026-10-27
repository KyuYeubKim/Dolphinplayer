package com.dolphin.mplayer

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView

/**
 * 앱 바로가기로 다른 앱을 열면 그 앱 위에 뜨는 'D-Player 로 돌아가기' 동그란 버튼.
 * 누르면 D-Player 를 앞으로 가져오고 사라진다. 끌어서 옮길 수 있고(위치 기억), D-Player 화면이 다시 보이면 자동으로 사라진다.
 * '다른 앱 위에 표시' 권한이 없으면 띄우지 않는다 (뒤로 가기 · 재생 정보 띠로 돌아올 수 있음).
 */
object ReturnBubble {

    private const val POS_X = "return_x"
    private const val POS_Y = "return_y"
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null

    /** paneRect = D-Player 창의 화면 위치 (처음 위치를 D-Player 가 있던 곳 왼쪽 가운데쯤으로) */
    fun show(c: Context, paneRect: Rect) {
        val ctx = c.applicationContext
        if (!Settings.canDrawOverlays(ctx)) return
        hide(ctx)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val size = dp(ctx, 60)
        val v = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE01DB954.toInt()) // D-Player 초록
                setStroke(dp(ctx, 2), 0x66FFFFFF)
            }
            elevation = dp(ctx, 6).toFloat()
            contentDescription = ctx.getString(R.string.return_to_dplayer)
        }
        val prefs = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        val scr = screen(ctx)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "D-Player return"
            if (prefs.contains(POS_X)) {
                x = (scr.x * prefs.getFloat(POS_X, 0f) - size / 2f).toInt()
                y = (scr.y * prefs.getFloat(POS_Y, 0f) - size / 2f).toInt()
            } else {
                x = paneRect.left + dp(ctx, 12)
                y = paneRect.top + (paneRect.height() * 0.45f).toInt()
            }
            x = x.coerceIn(0, (scr.x - size).coerceAtLeast(0))
            y = y.coerceIn(0, (scr.y - size).coerceAtLeast(0))
        }
        lp = p
        v.setOnTouchListener(Drag(ctx, wm))
        if (runCatching { wm.addView(v, p) }.isSuccess) view = v
    }

    fun hide(c: Context) {
        val v = view ?: return
        runCatching { c.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
        lp = null
    }

    /** 끌기 = 옮기기 (놓으면 위치 저장), 짧게 탭 = D-Player 로 돌아가기 */
    private class Drag(private val ctx: Context, private val wm: WindowManager) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) dragging = true
                    if (dragging) {
                        val scr = screen(ctx)
                        p.x = (startX + dx).toInt().coerceIn(0, (scr.x - v.width).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (scr.y - v.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val scr = screen(ctx)
                        ctx.getSharedPreferences("player", Context.MODE_PRIVATE).edit()
                            .putFloat(POS_X, (p.x + v.width / 2f) / scr.x)
                            .putFloat(POS_Y, (p.y + v.height / 2f) / scr.y)
                            .apply()
                    } else {
                        hide(ctx)
                        runCatching {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private fun screen(c: Context): Point = Point().also {
        @Suppress("DEPRECATION") c.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it)
    }

    private fun dp(c: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
}
