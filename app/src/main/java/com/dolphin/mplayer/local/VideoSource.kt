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
    /** 제목 아래 작은 글씨 (YouTube 는 채널 이름) */
    fun subtitle(): String = ""
    /** 받아 둔 곳까지 (재생 막대의 옅은 부분) */
    fun buffered(): Long = 0
    /** 끝까지 봤음 (가운데 버튼이 '다시 보기') */
    fun ended(): Boolean = false
    /** 라이브 방송 (시간 대신 '실시간') */
    fun live(): Boolean = false
    /** 고를 수 있는 화질 (세로 픽셀, 높은 것부터). 비어 있으면 ⚙ 버튼을 숨김 (0.81) */
    fun qualities(): List<Int> = emptyList()
    /** 고른 화질 · 기본 화질 · 지금 실제로 나오는 화질 글자 */
    fun quality(): Int = 0
    fun defaultQuality(): Int = 0
    fun qualityLabel(): String = ""
    fun setQuality(height: Int) {}
    /** 아직 영상을 받는 중 (화면에 '불러오는 중') */
    fun loading(): Boolean = false
    /** 영상 가로/세로 비율 (모르면 0) */
    fun videoAspect(): Float
    fun attach(view: SurfaceView)
    /** 화면을 닫을 때 */
    fun detach(view: SurfaceView)
}
