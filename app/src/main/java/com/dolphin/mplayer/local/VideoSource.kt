package com.dolphin.mplayer.local

import android.view.SurfaceView

/** 영상 전체화면(VideoActivity)이 보여 주는 재생원 — USB/SD(LocalEngine) · YouTube(YtEngine, 0.66) */
interface VideoSource {
    val playing: Boolean
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun position(): Long
    fun duration(): Long
    fun title(): String
    /** 아직 영상을 받는 중 (화면에 '불러오는 중') */
    fun loading(): Boolean = false
    /** 영상 가로/세로 비율 (모르면 0) */
    fun videoAspect(): Float
    fun attach(view: SurfaceView)
    /** 화면을 닫을 때 */
    fun detach(view: SurfaceView)
}
