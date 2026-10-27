package com.dolphin.mplayer

import android.content.Context
import android.content.pm.PackageManager
import com.dolphin.mplayer.youtube.YtLog

/**
 * 차량 라디오를 화면 없이 켜기 (0.65) — BYD SDK 의 BYDAutoMultimediaDevice.controlMedia(MODE_RADIO, …, withui=0).
 * 미디어센터는 분할 화면을 지원하지 않아(resizeableActivity=false) 라디오 화면을 열면 분할이 풀리므로 이 길을 먼저 쓴다.
 * 권한 BYDAUTO_MULTIMEDIA_SET 이 필요 (DolphinControl 처럼 ADB pm grant 로 D-Player 자신에게만, AdbProvisioner).
 * SDK 클래스는 차 펌웨어에만 있어 리플렉션으로 부른다 (없으면 null → 라디오 화면 열기로 대신).
 */
object CarRadio {

    val PERMISSIONS = listOf(
        "android.permission.BYDAUTO_MULTIMEDIA_COMMON",
        "android.permission.BYDAUTO_MULTIMEDIA_GET",
        "android.permission.BYDAUTO_MULTIMEDIA_SET",
    )
    private const val DEVICE = "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice"

    // SDK 상수 (constant-values: MODE_RADIO=0, ACTION_PLAY=1, ACTION_PAUSE=2, PARAM_WITH_UI="withui")
    private const val MODE_RADIO = 0
    private const val ACTION_PLAY = 1
    private const val PARAM_WITH_UI = "withui"

    fun hasPermission(c: Context) =
        c.checkSelfPermission("android.permission.BYDAUTO_MULTIMEDIA_SET") == PackageManager.PERMISSION_GRANTED

    /** 라디오로 바꿔 재생 (화면 없이). 결과 코드, SDK 가 없거나 실패하면 null */
    fun play(c: Context): Int? = control(c, MODE_RADIO, ACTION_PLAY, mapOf(PARAM_WITH_UI to 0))

    /** 진단용: 지금 미디어 종류 (SDK getMediaType, 0=라디오 1=음악 2=영상). 못 읽으면 null */
    fun mediaType(c: Context): Int? = runCatching {
        val cls = Class.forName(DEVICE)
        val dev = cls.getMethod("getInstance", Context::class.java).invoke(null, c.applicationContext)
        cls.getMethod("getMediaType").invoke(dev) as Int
    }.getOrNull()

    private fun control(c: Context, mode: Int, action: Int, params: Map<String, Int>): Int? = runCatching {
        val cls = Class.forName(DEVICE)
        val dev = cls.getMethod("getInstance", Context::class.java).invoke(null, c.applicationContext)
        val pcls = Class.forName("$DEVICE\$MediaControlParam")
        val p = pcls.getConstructor().newInstance()
        val set = pcls.getMethod("setParam", String::class.java, Int::class.javaPrimitiveType)
        params.forEach { (k, v) -> set.invoke(p, k, v) }
        cls.getMethod("controlMedia", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, pcls)
            .invoke(dev, mode, action, p) as Int
    }.onFailure { YtLog.e("라디오 SDK 명령 실패: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}") }
        .getOrNull()

    fun describe(c: Context) = "라디오: 권한=${if (hasPermission(c)) "있음" else "없음"} · 지금 미디어 종류=${mediaType(c) ?: "모름"}"
}
