package com.dolphin.mplayer

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 세로로 넘기는 칸 + 좌우로 밀면 onSwipe (차량 제어 패널 · 라디오 채널, 0.71~).
 * 옆으로 미는 움직임이 보이면 누르던 버튼은 취소(스치며 기능이 실행되지 않게)하고, 60dp 넘게 밀고 떼면 onSwipe.
 */
class SwipeScrollView(context: Context, private val onSwipe: () -> Unit) : ScrollView(context) {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDistance = 60 * resources.displayMetrics.density

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = abs(e.rawX - downX)
                val dy = abs(e.rawY - downY)
                if (dx > slop * 2 && dx > dy * 1.5f) {
                    swiping = true
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false
                if (abs(e.rawX - downX) > minDistance) onSwipe()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (swiping) { swiping = false; return true }
        }
        return swiping || super.dispatchTouchEvent(e)
    }
}
