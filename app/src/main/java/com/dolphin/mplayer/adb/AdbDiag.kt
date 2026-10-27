package com.dolphin.mplayer.adb

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ADB 문제 분석용 진단 기록. 차량 ADB 상태(시스템 속성·설정)와 연결 기록을 텍스트 파일로 남긴다.
 * 저장 위치: Download/D-Player/logs/ (Android 10+ 는 MediaStore, 권한 불필요) — 파일 관리자·USB 로 꺼내 볼 수 있다.
 */
object AdbDiag {

    private const val FOLDER = "D-Player/logs"

    /**
     * 공용 Download 폴더의 진단 파일은 앱을 지워도 남으므로(Android 10) 최근 것만 둔다 (0.50).
     * 앱이 만든 파일만 보이고 지울 수 있음.
     */
    private const val KEEP = 5

    /** D-Player 가 만든 진단 파일 중 최근 keep 개만 남기고 지움. 지운 개수 */
    fun prune(c: Context, keep: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        return runCatching {
            val uris = ArrayList<android.net.Uri>()
            c.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cur ->
                while (cur.moveToNext()) uris.add(android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0)))
            }
            uris.drop(keep).sumOf { u -> runCatching { c.contentResolver.delete(u, null, null) }.getOrDefault(0) }
        }.getOrDefault(0)
    }

    /** 차량 /data/local/tmp 에 D-Player 가 남겼을 수 있는 임시 파일 지우기 (예전 버전이 남긴 기록 포함) */
    fun cleanCarTmp(adb: AdbClient) {
        for (f in listOf("dplayer-update.apk", "dplayer-update.log", "dplayer_split.log"))
            adb.shell("rm -f /data/local/tmp/$f")
    }

    /** 차량 ADB 와 관련된 상태 (ADB 없이 앱에서 읽을 수 있는 것만) */
    fun environment(c: Context): String {
        val sb = StringBuilder()
        val app = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        sb.append("앱: D-Player ").append(app).append('\n')
        sb.append("기기: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("펌웨어: ").append(Build.DISPLAY).append('\n')
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n')
        // 화면 (펌웨어에 밀도 고정 값이 없어 실제 값은 차에서만 알 수 있음 — 에뮬레이터를 차와 같게 맞추는 용도)
        runCatching {
            val wm = c.getSystemService(android.view.WindowManager::class.java)
            val real = android.graphics.Point()
            val usable = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(real)
            @Suppress("DEPRECATION") wm.defaultDisplay.getSize(usable)
            val dm = c.resources.displayMetrics
            sb.append("화면: 실제 ").append(real.x).append('x').append(real.y)
                .append(" · 앱 영역 ").append(usable.x).append('x').append(usable.y)
                .append(" · 밀도 ").append(dm.densityDpi).append("dpi (")
                .append(c.resources.configuration.screenWidthDp).append('x')
                .append(c.resources.configuration.screenHeightDp).append("dp)\n")
            sb.append("display_density_forced = ")
                .append(Settings.Secure.getString(c.contentResolver, "display_density_forced") ?: "(없음)").append('\n')
        }
        listOf(
            "service.adb.tcp.port", "persist.adb.tcp.port", "init.svc.adbd", "ro.adb.secure",
            "ro.debuggable", "sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "ro.secure",
        ).forEach { sb.append(it).append(" = ").append(prop(it)).append('\n') }
        sb.append("Settings.Global.adb_enabled = ")
            .append(runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED) }.getOrNull()).append('\n')
        sb.append("Settings.Global.development_settings_enabled = ")
            .append(runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) }.getOrNull())
            .append('\n')
        sb.append("5555 포트 열림 = ").append(AdbClient.isPortOpen()).append('\n')
        sb.append("이 앱 키 파일 있음 = ").append(AdbClient(c).hasKey()).append('\n')
        sb.append("서명 형식 자체 검사 = ").append(AdbClient(c).signatureSelfTest()).append('\n')
        sb.append("'항상 허용' 확인됨(앱 기록) = ").append(AdbProvisioner.isReady(c)).append('\n')
        sb.append(com.dolphin.mplayer.DcSplit.describe(c)).append('\n')
        sb.append(com.dolphin.mplayer.CarPanel.describe(c)).append('\n')
        sb.append(com.dolphin.mplayer.youtube.YtEngine.describe()).append('\n')
        // 메모리 (차 4GB: 부족하면 웹 프로세스가 꺼져 페이지를 다시 읽느라 느려짐) · 켜진 뒤 시간 · 앱 중요도
        runCatching {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            sb.append("메모리: 여유 ").append(mi.availMem / 1048576).append("MB / 전체 ").append(mi.totalMem / 1048576)
                .append("MB · 부족 상태=").append(mi.lowMemory).append(" (기준 ").append(mi.threshold / 1048576).append("MB)\n")
            val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
            sb.append("D-Player 중요도=").append(me.importance).append(" · 켜진 뒤 ")
                .append(android.os.SystemClock.elapsedRealtime() / 60000).append("분\n")
            val dc = runCatching { c.packageManager.getPackageInfo("com.dolphin.control", 0).versionName }.getOrNull()
            sb.append("DolphinControl: ").append(dc ?: "없음").append('\n')
        }
        return sb.toString()
    }

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("?").ifEmpty { "(없음)" }

    /**
     * 진단 파일 저장. kind 는 파일 이름에 들어갈 종류(grant, split 등). 저장한 위치(사람이 읽을 경로) 또는 null.
     * 백그라운드 스레드에서 불러도 된다.
     */
    fun save(c: Context, kind: String, body: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "adb-$kind-$stamp.txt"
        val text = "D-Player ADB 진단 ($kind) · " +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n\n" +
            "[환경]\n" + environment(c) + "\n" + body + "\n"
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert failed")
                c.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                prune(c, KEEP)
                "Download/$FOLDER/$name"
            } else {
                val dir = File(c.getExternalFilesDir(null), "logs").apply { mkdirs() }
                File(dir, name).writeText(text, Charsets.UTF_8)
                File(dir, name).absolutePath
            }
        }.getOrElse {
            // 공용 폴더에 못 쓰면 앱 전용 폴더에라도
            runCatching {
                val dir = File(c.getExternalFilesDir(null) ?: c.filesDir, "logs").apply { mkdirs() }
                File(dir, name).writeText(text, Charsets.UTF_8)
                File(dir, name).absolutePath
            }.getOrNull()
        }
    }
}
