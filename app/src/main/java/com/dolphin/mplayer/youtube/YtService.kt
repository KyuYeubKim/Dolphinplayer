package com.dolphin.mplayer.youtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import com.dolphin.mplayer.MainActivity
import com.dolphin.mplayer.R

/**
 * 앱 안에서 직접 재생할 때(YouTube 웹 / USB·SD) 앱이 화면에서 사라져도 프로세스가 종료되지 않게 하는 포그라운드 서비스.
 * 재생 중엔 포그라운드, 일시정지면 포그라운드만 내려 알림은 남긴다.
 */
class YtService : Service() {

    /** 서비스가 따르는 재생원 (YtEngine / LocalEngine) */
    interface Source {
        val playing: Boolean
        val label: String
        fun currentTitle(): String
        fun sessionToken(): MediaSession.Token?
        fun play()
        fun pause()
        fun next()
        fun previous()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTI_ID = 7
        private const val ACTION_UPDATE = "update"
        private const val ACTION_PLAY_PAUSE = "play_pause"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREV = "prev"
        private const val EXTRA_PLAYING = "playing"

        private var source: Source? = null
        private var lastPlaying: Boolean? = null
        private var lastTitle = ""

        /** 상태가 바뀔 때만 서비스/알림 갱신. 재생 중인 쪽이 알림·버튼의 대상이 된다 */
        fun update(c: Context, src: Source, playing: Boolean) {
            // 다른 재생원이 재생 중이면 멈춘 쪽의 알림 갱신은 무시
            if (!playing && source !== src && source?.playing == true) return
            val title = src.currentTitle()
            if (src === source && playing == lastPlaying && title == lastTitle) return
            source = src
            lastPlaying = playing
            lastTitle = title
            val i = Intent(c, YtService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_PLAYING, playing)
            runCatching { if (playing) c.startForegroundService(i) else c.startService(i) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val src = source ?: run { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (src.playing) src.pause() else src.play()
            ACTION_NEXT -> src.next()
            ACTION_PREV -> src.previous()
        }
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, src.playing) ?: src.playing
        val n = notification(src, playing)
        if (playing) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTI_ID, n)
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, n)
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        return START_NOT_STICKY
    }

    private fun notification(src: Source, playing: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "재생", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun action(icon: Int, title: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, act.hashCode(), Intent(this, YtService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE)
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(src.currentTitle().ifEmpty { src.label })
            .setContentText(src.label)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_prev, "이전", ACTION_PREV))
            .addAction(action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, "재생/일시정지", ACTION_PLAY_PAUSE))
            .addAction(action(R.drawable.ic_next, "다음", ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(src.sessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
