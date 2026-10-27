package com.dolphin.mplayer

import android.content.Context
import android.net.Uri

/**
 * DolphinControl(1.069~) 분할 모드 상태 읽기 — 진단 로그 한 줄용 (읽기만).
 * 화면 분할은 DolphinControl 이 맡는다. D-Player 의 자체 분할 기능은 0.52 에서 제거했다.
 * 인터페이스: DolphinControl/docs/split-interface.md (같은 서명 키 앱만 읽힘)
 */
object DcSplit {

    private val URI: Uri = Uri.parse("content://com.dolphin.control.split/state")

    /** 진단 로그 한 줄 */
    fun describe(c: Context): String = try {
        c.contentResolver.query(URI, null, null, null, null).use { cur ->
            if (cur == null) "DolphinControl 분할 모드: 읽기 실패 (provider 없음 — DolphinControl 미설치 또는 1.068 이하)"
            else if (!cur.moveToFirst()) "DolphinControl 분할 모드: 읽기 실패 (빈 결과)"
            else {
                fun int(n: String) = cur.getColumnIndex(n).takeIf { it >= 0 }?.let { cur.getInt(it) } ?: 0
                fun str(n: String) = cur.getColumnIndex(n).takeIf { it >= 0 }?.let { cur.getString(it) }.orEmpty()
                val ri = cur.getColumnIndex("ratio")
                "DolphinControl 분할 모드: enabled=${int("enabled")} active=${int("active")} " +
                    "ratio=${if (ri >= 0) cur.getFloat(ri) else 0f} swapped=${int("swapped")} " +
                    "nav=${str("navPackage").ifEmpty { "-" }} secondary=${str("secondaryPackage").ifEmpty { "-" }}"
            }
        }
    } catch (t: Throwable) {
        "DolphinControl 분할 모드: 읽기 실패 (${t.javaClass.simpleName}: ${t.message})"
    }
}
