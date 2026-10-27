package com.dolphin.mplayer

import android.content.Context
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Spotify 가 끄기 전에 재생 중이었는지 (0.64, 시동 켜면 이어서 재생).
 * 늘 떠 있는 StatusTicker 가 Spotify 재생 상태를 받아 기록한다.
 * 멈춤은 10초 동안 이어질 때만 기록 — 시동을 끌 때 차가 먼저 멈추고 바로 앱이 꺼지는 경우는 '재생 중'으로 남게.
 * 앱이 뜬 뒤 한 번도 재생을 못 본 멈춤(시동 직후 Spotify 가 준비하며 보내는 상태)은 무시.
 */
object SpotifyMemory {

    private const val PREF = "sp_was_playing"
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var seenPlaying = false

    fun wasPlaying(c: Context) = prefs(c).getBoolean(PREF, false)

    fun onState(c: Context, pkg: String, state: Int?) {
        if (!pkg.startsWith("com.spotify")) return
        val app = c.applicationContext
        pending?.let { main.removeCallbacks(it) }
        pending = null
        when (state) {
            PlaybackState.STATE_PLAYING -> {
                seenPlaying = true
                if (!wasPlaying(app)) prefs(app).edit().putBoolean(PREF, true).apply()
            }
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> {
                if (!seenPlaying || !wasPlaying(app)) return
                val r = Runnable { prefs(app).edit().putBoolean(PREF, false).apply() }
                pending = r
                main.postDelayed(r, 10_000)
            }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
}

/** 앱별 음량 기억 설정 (0.64, 기본 켜짐). 실제 저장 · 되돌리기는 MainActivity.rememberVolume */
object VolumeMemory {

    private const val PREF = "vol_memory"

    fun isEnabled(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(c: Context, on: Boolean) =
        c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
}
