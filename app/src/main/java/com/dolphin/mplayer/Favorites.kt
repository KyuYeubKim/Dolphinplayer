package com.dolphin.mplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 즐겨찾기 바로 재생 (0.64) — 라이브러리에서 재생목록 · 폴더를 길게 눌러 넣으면 재생 화면 위에 버튼으로 (최대 4개).
 * src: "yt" = YouTube Music 재생목록 id · "sp" = Spotify 앱 라이브러리 mediaId · "spuri" = spotify: 주소 · "local" = USB/SD 폴더 경로
 */
object Favorites {

    const val MAX = 4
    private const val PREF = "favorites"

    data class Fav(val src: String, val id: String, val title: String)

    fun list(c: Context): List<Fav> = runCatching {
        val a = JSONArray(prefs(c).getString(PREF, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Fav(it.getString("src"), it.getString("id"), it.getString("title")) }
    }.getOrDefault(emptyList())

    fun contains(c: Context, src: String, id: String) = list(c).any { it.src == src && it.id == id }

    /** 넣었으면 true (가득 찼거나 이미 있으면 false) */
    fun add(c: Context, f: Fav): Boolean {
        val l = list(c)
        if (l.size >= MAX || l.any { it.src == f.src && it.id == f.id }) return false
        save(c, l + f)
        return true
    }

    fun remove(c: Context, f: Fav) = save(c, list(c).filterNot { it.src == f.src && it.id == f.id })

    fun clear(c: Context) = save(c, emptyList())

    private fun save(c: Context, l: List<Fav>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("src", it.src).put("id", it.id).put("title", it.title)) }
        prefs(c).edit().putString(PREF, a.toString()).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
}
