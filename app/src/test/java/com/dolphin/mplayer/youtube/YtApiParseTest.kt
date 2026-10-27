package com.dolphin.mplayer.youtube

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TV 데이터 해석 시험. 실제 응답(개인 보관함 · 기록이 들어 있음)은 저장소에 넣지 않고,
 * -DytFixtures=<폴더> 로 받은 PC 의 임시 폴더에서 읽는다. 없으면 건너뜀.
 *   gradlew :app:testArm64DebugUnitTest -DytFixtures=...  (app/build.gradle.kts 가 시스템 속성을 넘김)
 */
class YtApiParseTest {

    private fun load(name: String): JSONObject? {
        val dir = System.getProperty("ytFixtures") ?: return null
        val f = File(dir, "$name.json")
        return if (f.exists()) JSONObject(f.readText()) else null
    }

    private fun show(label: String, r: JSONObject) {
        val shelves = YtApi.shelves(r)
        println("== $label: 칸 ${shelves.length()}")
        for (i in 0 until shelves.length()) {
            val s = shelves.getJSONObject(i)
            val items = s.getJSONArray("items")
            println("  [${s.optString("title")}] ${items.length()}개")
            for (j in 0 until minOf(4, items.length())) {
                val it = items.getJSONObject(j)
                println("    ${it.optString("kind")} '${it.optString("title")}' / ${it.optString("subtitle")} " +
                    "v=${it.optString("videoId")} l=${it.optString("playlistId")} b=${it.optString("browseId")} thumb=${it.optString("thumb").take(40)}")
            }
        }
    }

    @Test
    fun parseFixtures() {
        assumeTrue(System.getProperty("ytFixtures") != null)
        for (name in listOf("topics_music", "tv_search", "tv_music_lib_landing", "tv_playlist", "tv_next_radio", "whattowatch", "subs", "tv_history")) {
            load(name)?.let { show(name, it) }
        }
        load("tv_playlist")?.let { println("재생목록 제목: '${YtApi.playlistTitle(it)}'") }
        load("tv_next_radio")?.let { r ->
            val shelf = YtApi.find(r, "shelfRenderer").first()
            println("다음 곡 칸 항목 수: " + YtApi.shelves(JSONObject().put("contents", JSONObject().put("shelfRenderer", shelf)))
                .getJSONObject(0).getJSONArray("items").length())
        }
    }
}
