package com.dolphin.mplayer

import android.content.Context
import android.content.Intent

/**
 * 위쪽 버튼 줄(플레이어 선택 버튼 오른쪽)의 앱 바로가기 (최대 [MAX]개). 누르면 그 앱을 실행한다.
 * 처음에는 설치돼 있으면 D-Phone · 차량 Spotify 화면 앱. 설정 → 재생 → 바로가기 앱 (또는 버튼 길게 누르기)에서 바꿈.
 */
object AppShortcuts {

    const val MAX = 4
    private const val PREFS = "player"
    private const val KEY = "app_shortcuts"
    private val DEFAULTS = listOf("com.dolphin.dphone", "com.vivid.music.byd")

    /** 실행할 수 있는 앱만 (지워진 앱은 빠짐) */
    fun list(c: Context): List<String> {
        val saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val pkgs = saved?.split(',')?.filter { it.isNotBlank() } ?: DEFAULTS
        return pkgs.filter { launchIntent(c, it) != null }.take(MAX)
    }

    fun set(c: Context, pkgs: List<String>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pkgs.take(MAX).joinToString(",")).apply()
    }

    fun launchIntent(c: Context, pkg: String): Intent? =
        c.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 실행할 수 있는 앱 목록 (패키지, 이름) — 이름순 */
    fun launchableApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun appLabel(c: Context, pkg: String?): String = pkg?.let {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(it, 0)).toString() }
            .getOrDefault(it)
    } ?: "-"
}
