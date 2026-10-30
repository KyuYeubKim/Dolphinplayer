package com.dolphin.mplayer

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** 폭에 맞춰 16:9 높이가 되는 썸네일 (YouTube 영상 카드) */
class VideoThumbView(context: Context, attrs: AttributeSet?) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 9 / 16)
    }
}
