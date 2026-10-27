package com.dolphin.mplayer.adb

import android.content.Context
import com.dolphin.mplayer.MediaListenerService

/**
 * 차량 ADB(127.0.0.1:5555)에 접속해 이 앱에 필요한 권한을 한 번에 부여한다. (PC 불필요)
 * 차량 화면의 "USB 디버깅 허용" 창에서 '항상 허용'을 체크하고 승인하면,
 * 이후 자동 업데이트 · 저장소 권한은 허용 창 없이 같은 키로 조용히 동작한다.
 * Dolphin Control 의 AdbProvisioner 와 같은 방식 (최대 4번, 1.2초 간격 재접속).
 */
object AdbProvisioner {

    enum class Outcome { GRANTED, UNREACHABLE, DENIED, FAILED }

    /** 권한 설정 결과. remembered = '항상 허용'이 저장돼 다음부터 허용 창 없이 접속되는지 */
    class Report(val outcome: Outcome, val remembered: Boolean, val summary: String, val log: String)

    private const val PREFS = "adb"
    private const val KEY_READY = "ready"

    /** 이 앱의 키가 '항상 허용'으로 등록된 것을 확인했는지 (권한 설정 성공 시 기록) */
    fun isReady(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READY, false)

    private fun setReady(context: Context, ready: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_READY, ready).apply()

    private val STORAGE_PERMISSIONS = listOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VIDEO",
    )

    /**
     * 권한 한 번에 부여 (백그라운드 스레드). progress 는 진행 메시지(백그라운드 스레드에서 호출됨).
     *  1) 알림 접근 (재생 정보 읽기·제어)
     *  2) USB/SD 음악·영상 읽기, 알림 표시
     *  3) 백그라운드 실행 허용 (YouTube Music 재생이 끊기지 않게, 베스트에포트)
     *  4) '항상 허용'이 저장됐는지 새 연결로 확인 → 이후 업데이트는 허용 창 없이 동작
     */
    fun grantAll(context: Context, progress: (String) -> Unit): Report {
        val app = context.applicationContext
        val pkg = app.packageName
        val adb = AdbClient(app)

        // adbd 는 [허용] 직후 대기 중이던 연결을 완료하지 않고 새 연결을 요구하는 경우가 많아 몇 번 다시 접속한다
        var r = AdbClient.Result.ERROR
        // 첫 시도는 허용 창 응답을 넉넉히(60초) 같은 연결에서 기다린다. 늦게 누르면 이미 닫힌 연결에 대한
        // 승인이 되어 버리므로 짧게 끊지 않는다. 이후 시도는 '항상 허용'으로 저장됐는지 확인 + 짧은 재요청
        for (attempt in 1..4) {
            progress("차량 ADB 접속 중… ($attempt/4)")
            r = adb.connect({ left ->
                progress("차량 화면의 'USB 디버깅 허용' 창에서\n① '이 컴퓨터에서 항상 허용'을 체크하고\n② [허용]을 눌러 주세요.\n\n허용 대기 중… ${left}초 ($attempt/4)")
            }, true, if (attempt == 1) AdbClient.APPROVE_TIMEOUT_MS else 15000)
            if (r == AdbClient.Result.OK || r == AdbClient.Result.UNREACHABLE) break
            adb.close()
            Thread.sleep(1200)
        }
        if (r != AdbClient.Result.OK) {
            setReady(app, false)
            val outcome = when (r) {
                AdbClient.Result.UNREACHABLE -> Outcome.UNREACHABLE
                AdbClient.Result.ERROR -> Outcome.FAILED
                else -> Outcome.DENIED
            }
            return Report(outcome, false, "", adb.log.toString())
        }

        progress("연결됨. 권한 부여 중…")
        val sum = StringBuilder()
        val comp = pkg + "/" + MediaListenerService::class.java.name
        // 일부 펌웨어는 set_enabled 하위 명령을 쓴다
        val listener = runOk(adb, "cmd notification allow_listener $comp") ||
            runOk(adb, "cmd notification set_enabled $comp true")
        sum.append(if (listener) "✔ 알림 접근 (재생 정보)\n" else "✘ 알림 접근\n")

        val storage = STORAGE_PERMISSIONS.map { runOk(adb, "pm grant $pkg $it") }.any { it }
        sum.append(if (storage) "✔ USB/SD 음악·영상 읽기\n" else "· USB/SD 읽기 (이 펌웨어 미지원)\n")
        runOk(adb, "pm grant $pkg android.permission.POST_NOTIFICATIONS")

        val bg = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        ).map { runOk(adb, it) }.any { it }
        sum.append(if (bg) "✔ 백그라운드 재생 허용\n" else "· 백그라운드 허용 (이 펌웨어 미지원)\n")

        // 상단 바 재생 정보 표시용 (다른 앱 위에 표시)
        val overlay = runOk(adb, "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        sum.append(if (overlay) "✔ 다른 앱 위에 표시 (상단 바 재생 정보)\n" else "· 다른 앱 위에 표시 (이 펌웨어 미지원)\n")
        adb.close()

        // '항상 허용'이 저장됐는지: 허용 창 없이 새로 접속해 본다
        progress("'항상 허용' 확인 중…")
        Thread.sleep(500)
        val check = AdbClient(app)
        val remembered = check.connect() == AdbClient.Result.OK
        check.close()
        setReady(app, remembered)
        sum.append(if (remembered) "✔ 항상 허용 (자동 업데이트 준비됨)\n"
        else "✘ '항상 허용'이 저장되지 않음 — 자동 업데이트를 조용히 하려면\n   권한 다시 설정에서 '이 컴퓨터에서 항상 허용'을 체크해 주세요\n")

        return Report(if (listener) Outcome.GRANTED else Outcome.FAILED, remembered, sum.toString(),
            adb.log.toString() + check.log.toString())
    }

    /** 런타임 권한을 차량 ADB 로 부여 (백그라운드 스레드, 허용 창 없음). 하나라도 부여되면 true */
    fun grantPermissions(context: Context, permissions: List<String>): Boolean {
        val app = context.applicationContext
        val adb = AdbClient(app)
        if (adb.connect() != AdbClient.Result.OK) {
            adb.close()
            return false
        }
        val ok = permissions.map { runOk(adb, "pm grant ${app.packageName} $it") }.any { it }
        adb.close()
        return ok
    }

    /** '항상 허용'된 키로 다른 앱 위에 표시 권한만 받기 (허용 창 없음, 백그라운드 스레드) */
    fun grantOverlay(context: Context): Boolean {
        val app = context.applicationContext
        val adb = AdbClient(app)
        if (adb.connect() != AdbClient.Result.OK) {
            adb.close()
            return false
        }
        val ok = runOk(adb, "appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        adb.close()
        return ok
    }

    private fun runOk(adb: AdbClient, cmd: String): Boolean {
        val out = adb.shell(cmd) ?: return false
        // 성공 시 보통 빈 출력. 오류 메시지가 있으면 실패로 본다.
        val t = out.trim().lowercase()
        return t.isEmpty() || "success" in t || "granted" in t || "added" in t
    }
}
