package com.dolphin.mplayer

import android.service.notification.NotificationListenerService

/**
 * 이 서비스에 "알림 접근" 권한이 있어야 MediaSessionManager.getActiveSessions() 로
 * 다른 앱의 재생 세션을 읽을 수 있다. 권한이 있으면 늘 떠 있으므로 상단 바 재생 정보(StatusTicker)도 여기서 돌린다.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        @Volatile private var connected: MediaListenerService? = null

        /** 서비스가 연결돼 있으면 띠를 (아직 안 돌면) 시작. 연결돼 있지 않으면 false (0.75) */
        fun ensureTicker(): Boolean {
            val s = connected ?: return false
            s.startTicker()
            return true
        }
    }

    override fun onListenerConnected() {
        connected = this
        startTicker()
    }

    /** 화면 없이 도는 부분: 이용 안내에 한 번이라도 동의했는지만 확인 (창은 화면이 열릴 때 MainActivity 가 띄움, 0.58) */
    private fun startTicker() {
        val p = getSharedPreferences("player", MODE_PRIVATE)
        val agreed = p.getLong("agreed_code", 0L) > 0L || p.getBoolean("terms_accepted_v1", false) // MainActivity.AGREED_CODE · TERMS_KEY
        if (agreed) StatusTicker.startShared(this, "알림 접근 서비스")
    }

    // 연결이 끊겨도 띠는 그대로 (권한이 남아 있으면 세션은 계속 읽힘). 권한을 끄면 띠도 끔 (0.80)
    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        if (!hasAccess()) StatusTicker.stopShared()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        if (!hasAccess()) StatusTicker.stopShared()
        super.onDestroy()
    }

    private fun hasAccess(): Boolean {
        val me = android.content.ComponentName(this, MediaListenerService::class.java)
        val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
    }
}
