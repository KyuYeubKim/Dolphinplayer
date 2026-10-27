package com.dolphin.mplayer.youtube

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * YouTube / YouTube Music 동작 기록 (진단 로그용). 차에서 무엇이 느린지·실패하는지 나중에 보기 위해
 * 페이지 로드, 목록 요청↔응답, 재생 요청↔소리 시작, 다음 곡, 웹 프로세스 종료, 오디오 포커스 등을 시각과 함께 남긴다.
 * 앱이 꺼져도 남도록 앱 전용 폴더(files/ytlog.txt, 256KB 넘으면 ytlog.old.txt 로 넘김)에 바로 덧붙인다.
 * 로그인 정보·쿠키는 남기지 않는다 (주소·곡 id·시간만).
 */
object YtLog {

    private const val TAG = "YtLog"
    private const val MAX_FILE = 256 * 1024L
    private val io = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var dir: File? = null

    fun init(c: Context) {
        if (dir == null) dir = c.applicationContext.filesDir
    }

    fun e(msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + msg
        Log.d(TAG, msg)
        val d = dir ?: return
        io.execute {
            runCatching {
                val f = File(d, "ytlog.txt")
                if (f.length() > MAX_FILE) f.renameTo(File(d, "ytlog.old.txt"))
                f.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    /** 진단 파일에 넣을 최근 기록 (이전 파일 + 지금 파일의 마지막 lines 줄) */
    fun dump(lines: Int = 800): String {
        val d = dir ?: return "(기록 없음)"
        // 기록 쓰기가 끝난 뒤 읽도록 같은 스레드에서
        return runCatching {
            io.submit<String> {
                val all = listOf("ytlog.old.txt", "ytlog.txt").map { File(d, it) }.filter { it.exists() }
                    .flatMap { it.readLines(Charsets.UTF_8) }
                if (all.isEmpty()) "(기록 없음)" else all.takeLast(lines).joinToString("\n")
            }.get()
        }.getOrElse { "(기록 읽기 실패: $it)" }
    }
}
