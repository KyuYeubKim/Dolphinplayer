package com.dolphin.mplayer

import android.service.notification.NotificationListenerService

/**
 * 이 서비스에 "알림 접근" 권한이 있어야 MediaSessionManager.getActiveSessions() 로
 * 다른 앱의 재생 세션을 읽을 수 있다. 권한이 있으면 늘 떠 있으므로 상단 바 재생 정보(StatusTicker)도 여기서 돌린다.
 */
class MediaListenerService : NotificationListenerService() {

    private var ticker: StatusTicker? = null

    override fun onListenerConnected() {
        // 화면 없이 도는 부분: 이용 안내에 한 번이라도 동의했는지만 확인 (창은 화면이 열릴 때 MainActivity 가 띄움, 0.58)
        val p = getSharedPreferences("player", MODE_PRIVATE)
        val agreed = p.getLong("agreed_code", 0L) > 0L || p.getBoolean("terms_accepted_v1", false) // MainActivity.AGREED_CODE · TERMS_KEY
        if (agreed && ticker == null) ticker = StatusTicker(this).also { it.start() }
    }

    override fun onListenerDisconnected() {
        ticker?.stop()
        ticker = null
    }

    override fun onDestroy() {
        ticker?.stop()
        ticker = null
        super.onDestroy()
    }
}
