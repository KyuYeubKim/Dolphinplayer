package com.dolphin.mplayer.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dolphin.mplayer.adb.AdbClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 자체 업데이트 (Dolphin Control 의 AppUpdater 와 같은 방식).
 *  - releases/latest 조회 → tag_name(버전) 비교 → .apk 에셋 다운로드
 *  - 설치: 앱 내장 ADB 로 조용히(pm install) 시도 → 실패 시 표준 설치 화면
 * 저장소가 공개라서 앱에는 토큰이 필요 없다. 릴리스 업로드는 scripts/release.ps1.
 */
object AppUpdater {

    const val REPO = "KyuYeubKim/Dolphinplayer"
    private const val TAG = "AppUpdater"
    private const val PREFS = "update"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_BETA = "beta_channel"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var busy = false

    fun isAutoCheck(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoCheck(c: Context, on: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()

    /** 베타 채널: 켜면 시험판(Pre-release)도 받는다 (0.45, DolphinControl 과 같은 방식) */
    fun isBetaChannel(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA, false)

    fun setBetaChannel(c: Context, on: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA, on).apply()

    /** 앱 시작 시: 설정이 켜져 있고 새 버전일 때만 안내 */
    fun checkAuto(a: Activity) {
        clearDownloads(a) // 지난 업데이트 때 받은 APK(약 90MB)는 설치가 끝났으니 지움
        if (isAutoCheck(a)) check(a, verbose = false)
    }

    /** 업데이트용으로 받아 둔 APK 지우기 (앱 전용 폴더) */
    fun clearDownloads(c: Context) {
        if (busy) return
        runCatching { File(c.getExternalFilesDir(null), "update").listFiles()?.forEach { it.delete() } }
    }

    /** 설정 메뉴에서: 결과를 항상 알려줌 */
    fun checkManual(a: Activity) = check(a, verbose = true)

    fun currentVersion(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun check(a: Activity, verbose: Boolean) {
        if (busy) return
        if (verbose) toast(a, "업데이트 확인 중…")
        Thread {
            try {
                val rel = if (isBetaChannel(a)) fetchNewestIncludingPre() else fetchLatest()
                val tag = rel.optString("tag_name").removePrefix("v").removePrefix("V").trim()
                val shown = if (rel.optBoolean("prerelease")) "$tag (시험판)" else tag
                val apkUrl = findApkUrl(rel)
                val cur = currentVersion(a)
                when {
                    tag.isEmpty() || apkUrl == null -> if (verbose) toast(a, "릴리스에 APK 가 없습니다")
                    compare(tag, cur) <= 0 -> if (verbose) toast(a, "최신 버전입니다 (현재 $cur)")
                    else -> main.post { promptUpdate(a, tag, shown, rel.optString("body"), apkUrl) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "update check failed", t)
                if (verbose) {
                    val m = rootMsg(t)
                    toast(a, if ("404" in m) "아직 게시된 릴리스가 없습니다" else "업데이트 확인 실패: $m")
                }
            }
        }.start()
    }

    private fun fetchLatest(): JSONObject = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))

    /** 베타 채널: 시험판까지 포함해 이 기기용 APK 가 있는 가장 높은 버전 */
    private fun fetchNewestIncludingPre(): JSONObject {
        val arr = org.json.JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=30"))
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || findApkUrl(r) == null) continue
            val tag = r.optString("tag_name").removePrefix("v").removePrefix("V").trim()
            val bestTag = best?.optString("tag_name")?.removePrefix("v")?.removePrefix("V")?.trim()
            if (best == null || compare(tag, bestTag!!) > 0) best = r
        }
        return best ?: fetchLatest()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "D-Player")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code >= 400) throw Exception("HTTP $code")
        return body
    }

    /**
     * 0.04 부터 ABI 별 APK(D-Player-<버전>-arm64.apk / -armv7.apk / -x64.apk)를 올린다.
     * 이 기기 CPU 에 맞는 것을 고르고, ABI 표시가 없는 예전 형식이면 첫 .apk 를 쓴다.
     */
    private fun findApkUrl(rel: JSONObject): String? {
        val assets = rel.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").lowercase().endsWith(".apk") }
        val tags = Build.SUPPORTED_ABIS.mapNotNull { ABI_TAGS[it] }
        for (tag in tags) {
            apks.firstOrNull { it.optString("name").lowercase().endsWith("-$tag.apk") }
                ?.let { return it.optString("browser_download_url") }
        }
        val untagged = apks.firstOrNull { a -> ABI_TAGS.values.none { a.optString("name").lowercase().endsWith("-$it.apk") } }
        return untagged?.optString("browser_download_url")
    }

    private val ABI_TAGS = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "armv7", "x86_64" to "x64")

    private fun promptUpdate(a: Activity, tag: String, shown: String, notes: String, apkUrl: String) {
        if (a.isFinishing || a.isDestroyed) return
        busy = true
        runCatching {
            AlertDialog.Builder(a)
                .setTitle("새 버전 $shown")
                .setMessage(notes.ifBlank { "업데이트가 있습니다." } + "\n\n지금 다운로드하여 설치할까요?\n(현재 ${currentVersion(a)})")
                .setPositiveButton("다운로드/설치") { _, _ -> download(a, tag, apkUrl) }
                .setNegativeButton("나중에") { _, _ -> busy = false }
                .setOnCancelListener { busy = false }
                .show()
        }.onFailure { busy = false }
    }

    private fun download(a: Activity, tag: String, apkUrl: String) {
        val dlg = AlertDialog.Builder(a).setTitle("업데이트 다운로드").setMessage("0%").setCancelable(false).show()
        Thread {
            try {
                val dir = File(a.getExternalFilesDir(null), "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // 이전 버전 APK 정리
                val apk = File(dir, "D-Player-$tag.apk")
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "D-Player")
                c.connectTimeout = 10000
                c.readTimeout = 20000
                val total = c.contentLength
                c.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post { dlg.setMessage("$pct%") }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                // 받은 파일이 D-Player 이고 지금과 같은 서명 키인지 확인한 뒤에만 설치 (다른 앱이 설치되는 일이 없게)
                verifyApk(a, apk)?.let { why ->
                    apk.delete()
                    main.post {
                        busy = false
                        runCatching { dlg.dismiss() }
                        toast(a, "업데이트 파일이 올바르지 않아 설치하지 않았습니다 ($why)")
                    }
                    return@Thread
                }
                main.post { dlg.setMessage("설치 중…\n(설치가 끝나면 앱이 다시 시작됩니다)") }
                val silent = tryAdbInstall(a, apk)
                main.post {
                    busy = false
                    runCatching { dlg.dismiss() }
                    if (silent) toast(a, "설치 완료. 앱을 다시 실행하세요.") else launchInstaller(a, apk)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "download failed", t)
                main.post {
                    busy = false
                    runCatching { dlg.dismiss() }
                    toast(a, "다운로드 실패: ${rootMsg(t)}")
                }
            }
        }.start()
    }

    /**
     * 앱 내장 ADB 로 조용히 설치 (알림 접근 권한 때 이미 '항상 허용' 된 키 재사용).
     * 설치되는 순간 이 앱 프로세스가 종료되므로, 설치와 재실행을 adbd 쪽 백그라운드 셸(nohup)에 맡기고
     * 결과 파일을 읽어 성공/실패를 판단한다. 성공하면 셸이 새 버전을 바로 다시 띄운다.
     */
    private fun tryAdbInstall(c: Context, apk: File): Boolean = runCatching {
        val adb = AdbClient(c)
        if (adb.connect() != AdbClient.Result.OK) return false
        val log = "/data/local/tmp/dplayer-update.log"
        val tmpApk = "/data/local/tmp/dplayer-update.apk"
        val relaunch = "am start -n ${c.packageName}/${c.packageName}.MainActivity"

        // 1) Android 11+ 에선 셸이 앱 폴더(Android/data)의 APK 를 못 읽으므로 ADB 로 /data/local/tmp 에 전송
        val size = apk.length()
        val pushed = adb.execWithInput("sh -c 'head -c $size > $tmpApk'", apk) &&
            adb.shell("stat -c %s $tmpApk")?.trim() == size.toString()
        if (!pushed) {
            Log.w(TAG, "apk push failed")
            adb.shell("rm -f $tmpApk") // 반쯤 옮겨진 파일도 남기지 않음
            adb.close()
            return false
        }

        // 2) 설치 + 재실행. AdbClient 의 shell: 은 pty 세션이라 세션이 끝나면 nohup 만으로는 백그라운드 작업이
        //    같이 죽는다. setsid 로 새 세션을 만들고 입출력을 끊어야 앱이 종료돼도 설치·재실행이 이어진다.
        //    끝의 sleep 1: 바깥 셸이 곧바로 끝나면 자식이 setsid 로 분리되기 전에 같이 종료되는 경쟁이 있다.
        //    결과 기록은 앱이 읽을 시간(10초)을 준 뒤 지운다 → 차에 남는 파일 없음 (0.50)
        adb.shell(
            "rm -f $log; setsid sh -c 'pm install -r $tmpApk > $log 2>&1; rm -f $tmpApk; " +
                "grep -q Success $log && $relaunch; sleep 10; rm -f $log' < /dev/null > /dev/null 2>&1 & sleep 1"
        )
        var ok = false
        for (i in 0 until 120) { // 설치 완료 시 이 프로세스는 종료됨 → 실패만 여기서 감지
            Thread.sleep(1000)
            val out = adb.shell("cat $log 2>/dev/null").orEmpty()
            if ("Success" in out) { ok = true; break }
            if ("Failure" in out || "Error" in out || "Exception" in out) {
                Log.w(TAG, "adb install failed: $out")
                break
            }
        }
        adb.shell("rm -f $log")
        adb.shell("rm -f $tmpApk")
        adb.close()
        ok
    }.getOrDefault(false)

    /** 받은 APK 확인: 패키지가 D-Player 이고 서명 인증서가 지금 설치된 것과 같으면 null, 아니면 이유 */
    @Suppress("DEPRECATION")
    private fun verifyApk(c: Context, apk: File): String? = runCatching {
        val pm = c.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "APK 를 읽을 수 없음"
        if (info.packageName != c.packageName) return "다른 앱 (${info.packageName})"
        val mine = pm.getPackageInfo(c.packageName, flags).signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val theirs = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        if (mine.isEmpty() || mine != theirs) return "서명이 다름"
        null
    }.getOrElse { "확인 실패: ${it.message}" }

    /** 표준 설치 화면 (처음 한 번 '이 출처의 앱 설치 허용' 이 필요할 수 있음) */
    private fun launchInstaller(a: Activity, apk: File) {
        runCatching {
            val uri = Uri.parse("content://${a.packageName}.apk/${apk.name}")
            a.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(a, "설치 실행 실패: ${rootMsg(it)}") }
    }

    /**
     * 버전 비교 (a > b → 양수). 버전명은 "정수.소수" 형태(0.03, 0.1, 0.123)라
     * 소수부는 자릿수를 맞춰 비교한다 (0.1 > 0.09). Dolphin Control 과 같은 규칙.
     */
    fun compare(a: String, b: String): Int {
        val pa = a.split(".", limit = 2)
        val pb = b.split(".", limit = 2)
        val ma = pa[0].filter(Char::isDigit).toIntOrNull() ?: 0
        val mb = pb[0].filter(Char::isDigit).toIntOrNull() ?: 0
        if (ma != mb) return ma - mb
        var fa = pa.getOrNull(1)?.filter(Char::isDigit).orEmpty()
        var fb = pb.getOrNull(1)?.filter(Char::isDigit).orEmpty()
        val len = maxOf(fa.length, fb.length)
        fa = fa.padEnd(len, '0')
        fb = fb.padEnd(len, '0')
        return fa.compareTo(fb)
    }

    private fun rootMsg(t: Throwable): String {
        var r = t
        while (r.cause != null) r = r.cause!!
        return r.javaClass.simpleName + (r.message?.let { ": $it" } ?: "")
    }

    private fun toast(a: Activity, msg: String) = main.post {
        if (!a.isFinishing) Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
    }
}
