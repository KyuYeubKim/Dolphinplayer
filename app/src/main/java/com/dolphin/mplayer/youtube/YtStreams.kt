package com.dolphin.mplayer.youtube

import android.os.SystemClock
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube 재생 주소 (0.66) — NewPipeExtractor(GPLv3) 로 곡 · 영상의 실제 파일 주소를 얻는다 (서명 풀기 포함).
 * 주소는 몇 시간 뒤 만료되므로 만료 10분 전까지만 다시 쓴다. 실패하면 이유를 그대로 올려 보낸다 (웹 재생으로 대신하지 않음).
 */
object YtStreams {

    /** 소리 파일 하나 (또는 영상 + 소리) */
    class Stream(val url: String, val itag: Int, val format: String, val kbps: Int, val expiresAt: Long,
                 val durationSec: Long, val videoUrl: String? = null, val videoLabel: String = "")

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile private var ready = false
    private val audioCache = HashMap<String, Stream>()

    /** 최근 주소 받기 시간 (진단용, 최근 5번) */
    private val timings = ArrayDeque<String>()
    val lastTimings: String get() = synchronized(timings) { if (timings.isEmpty()) "아직 없음" else timings.joinToString(" / ") }

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(Http, Localization("ko", "KR"), ContentCountry("KR"))
            ready = true
        }
    }

    /** 이미 받아 둔(아직 쓸 수 있는) 주소가 있는지 */
    fun cached(videoId: String): Boolean = synchronized(audioCache) {
        audioCache[videoId]?.let { System.currentTimeMillis() < it.expiresAt - 600_000 } == true
    }

    fun forget(videoId: String) = synchronized(audioCache) { audioCache.remove(videoId) }

    /** 곡의 소리 주소 (백그라운드에서). AAC 128k(가벼운 하드웨어 디코딩) 우선, 없으면 가장 좋은 소리 */
    fun audio(videoId: String): Stream {
        synchronized(audioCache) {
            audioCache[videoId]?.let { if (System.currentTimeMillis() < it.expiresAt - 600_000) return it }
        }
        init()
        val t0 = SystemClock.elapsedRealtime()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val audios = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }
        val pick = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audios.maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("소리 파일 없음 (받은 종류 ${info.audioStreams.size}개)")
        val s = Stream(pick.content, pick.itag, pick.format?.name ?: "?", pick.averageBitrate, expiry(pick.content), info.duration)
        val ms = SystemClock.elapsedRealtime() - t0
        note("${ms}ms")
        YtLog.e("재생 주소 받음 $videoId: ${s.format} ${s.kbps}kbps, ${ms}ms, 만료까지 ${(s.expiresAt - System.currentTimeMillis()) / 60_000}분")
        synchronized(audioCache) {
            if (audioCache.size > 60) audioCache.keys.take(20).forEach { audioCache.remove(it) }
            audioCache[videoId] = s
        }
        return s
    }

    /** 영상 (화면용 영상 + 소리). maxHeight 이하 AVC(하드웨어 디코딩) — 없으면 소리 포함 360p */
    fun video(videoId: String, maxHeight: Int): Stream {
        init()
        val t0 = SystemClock.elapsedRealtime()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        fun height(r: String) = r.substringBefore('p').toIntOrNull() ?: 0
        // 라이브 방송은 HLS (조각 파일 목록) 하나로
        if ((info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) && info.hlsUrl.isNotEmpty()) {
            YtLog.e("영상 주소 받음 $videoId: 라이브(HLS), ${SystemClock.elapsedRealtime() - t0}ms")
            return Stream(info.hlsUrl, 0, "HLS", 0, expiry(info.hlsUrl), 0, null, "라이브")
        }
        val vo = info.videoOnlyStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.MPEG_4 &&
                it.codec?.startsWith("avc") != false && height(it.resolution) in 1..maxHeight
        }.maxByOrNull { height(it.resolution) }
        val audio = info.audioStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format == MediaFormat.M4A &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }.maxByOrNull { it.averageBitrate }
        val s = if (vo != null && audio != null) {
            Stream(audio.content, audio.itag, "M4A", audio.averageBitrate, expiry(vo.content), info.duration, vo.content, vo.resolution)
        } else {
            val muxed = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
                .maxByOrNull { height(it.resolution) } ?: throw IllegalStateException("영상 파일 없음")
            Stream(muxed.content, muxed.itag, muxed.format?.name ?: "?", 0, expiry(muxed.content), info.duration, null, muxed.resolution)
        }
        YtLog.e("영상 주소 받음 $videoId: ${s.videoLabel} ${if (s.videoUrl != null) "영상+소리" else "합본"}, ${SystemClock.elapsedRealtime() - t0}ms")
        return s
    }

    private fun expiry(url: String): Long =
        Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
            ?: (System.currentTimeMillis() + 3 * 3600_000L)

    private fun note(s: String) = synchronized(timings) {
        timings.addFirst(s)
        while (timings.size > 5) timings.removeLast()
    }

    /** NewPipe 가 쓰는 HTTP (앱 기본 HttpURLConnection) */
    private object Http : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.requestMethod = request.httpMethod()
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            for ((k, values) in request.headers()) {
                conn.setRequestProperty(k, values.firstOrNull() ?: continue)
                values.drop(1).forEach { conn.addRequestProperty(k, it) }
            }
            val data = request.dataToSend()
            try {
                if (data != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(data) }
                }
                val code = conn.responseCode
                if (code == 429) throw ReCaptchaException("YouTube 가 잠시 막음 (429)", request.url())
                val body = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
                val headers = conn.headerFields.filterKeys { it != null }
                return Response(code, conn.responseMessage, headers, body, conn.url.toString())
            } finally {
                conn.disconnect()
            }
        }
    }
}
