package com.dolphin.mplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.dolphin.mplayer.youtube.YtLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

/**
 * 음량 맞추기 (0.83, 기본 켜짐): YouTube Music · YouTube 영상 · 인터넷 라디오 · USB 소리를 Spotify 기본 음량에 맞춘다.
 * Spotify 는 음량 평준화('보통')로 곡을 -14 LUFS 에 맞춰 내보내지만 YouTube 원본은 대개 -7~-9 LUFS 라 2배 넘게 크게 들림.
 * 재생 직전 소리를 BS.1770 방식(K 가중)으로 재며 따라가는 자동 음량 — 커지면 빨리 줄이고 작아지면 천천히 올린다(최대 +6dB).
 * 엔진마다 하나씩 쓰고 마지막 이득을 기억해 두어, 다음 시동의 첫 곡부터 맞는 크기로 시작한다.
 */
object Loudness {

    /** Spotify 음량 평준화 '보통' 과 같은 목표 */
    const val TARGET_LUFS = -14f
    private const val PREF = "loudness"

    @Volatile
    private var enabled: Boolean? = null

    fun isEnabled(c: Context): Boolean =
        enabled ?: prefs(c).getBoolean(PREF, true).also { enabled = it }

    fun setEnabled(c: Context, on: Boolean) {
        enabled = on
        prefs(c).edit().putBoolean(PREF, on).apply()
        YtLog.e("음량 맞추기 ${if (on) "켬" else "끔"}")
    }

    /** 음량 맞추기를 넣은 ExoPlayer 용 RenderersFactory. key 는 기억할 이득의 이름, defaultGainDb 는 처음 쓸 때의 이득 */
    @OptIn(markerClass = [UnstableApi::class])
    fun renderers(c: Context, key: String, defaultGainDb: Float): RenderersFactory {
        val app = c.applicationContext
        val proc = LoudnessProcessor(app, key, defaultGainDb)
        return object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(proc))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
    }

    internal fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
}

@OptIn(markerClass = [UnstableApi::class])
private class LoudnessProcessor(
    private val app: Context,
    private val key: String,
    defaultGainDb: Float,
) : BaseAudioProcessor() {

    private companion object {
        const val MIN_GAIN_DB = -20f
        const val MAX_GAIN_DB = 6f
        /** 3초 평균이 목표보다 이만큼 넘으면 바로 줄임 (곡이 바뀌며 갑자기 커질 때) */
        const val OVERSHOOT_DB = 3f
        const val ST_BLOCKS = 30 // 100ms × 30 = 3초
    }

    private val prefKey = "loudness_gain_$key"
    private var gainDb = Loudness.prefs(app).getFloat(prefKey, defaultGainDb)
    private var gain = 10f.pow(gainDb / 20f)
    private var gainGoal = gain

    // K 가중 필터 (고음 강조 + 저음 차단, 채널마다 상태)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var hb0 = 0.0; private var hb1 = 0.0; private var hb2 = 0.0; private var ha1 = 0.0; private var ha2 = 0.0
    private var z = DoubleArray(0)
    private var channels = 0
    private var gainStep = 0f

    // 100ms 단위로 잰 소리 크기
    private var blockLen = 4800
    private var blockFrames = 0
    private var blockSum = 0.0
    private val recent = DoubleArray(ST_BLOCKS)
    private var recentCount = 0
    private var recentPos = 0
    private var longEnergy = 0.0
    private var longCount = 0
    private var blocksSinceSave = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val f = inputAudioFormat
        if (f.sampleRate <= 0 || f.channelCount <= 0) return
        channels = f.channelCount
        val fs = f.sampleRate.toDouble()
        // libebur128 와 같은 계수 (어느 샘플레이트에서나)
        var k = tan(Math.PI * 1681.974450955533 / fs)
        val vh = 10.0.pow(3.999843853973347 / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var q = 0.7071752369554196
        var a0 = 1.0 + k / q + k * k
        b0 = (vh + vb * k / q + k * k) / a0; b1 = 2.0 * (k * k - vh) / a0; b2 = (vh - vb * k / q + k * k) / a0
        a1 = 2.0 * (k * k - 1.0) / a0; a2 = (1.0 - k / q + k * k) / a0
        k = tan(Math.PI * 38.13547087602444 / fs)
        q = 0.5003270373238773
        a0 = 1.0 + k / q + k * k
        hb0 = 1.0; hb1 = -2.0; hb2 = 1.0
        ha1 = 2.0 * (k * k - 1.0) / a0; ha2 = (1.0 - k / q + k * k) / a0
        z = DoubleArray(channels * 4)
        blockLen = (f.sampleRate / 10).coerceAtLeast(1)
        blockFrames = 0
        blockSum = 0.0
        gainStep = 1f / (0.05f * f.sampleRate) // 이득은 50ms 에 걸쳐 부드럽게
        // 기억한 크기와 이득은 그대로 (넘기기 · 곡 바뀜에도 이어서)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val on = Loudness.isEnabled(app)
        val goal = if (on) gainGoal else 1f
        val ch = channels.coerceAtLeast(1)
        while (input.remaining() >= ch * (if (float) 4 else 2)) {
            gain += (goal - gain) * gainStep
            for (c in 0 until ch) {
                val x = if (float) input.float.toDouble() else input.short / 32768.0
                // K 가중 (두 단 직접형 II 전치)
                val zi = c * 4
                val y1 = b0 * x + z[zi]
                z[zi] = b1 * x - a1 * y1 + z[zi + 1]
                z[zi + 1] = b2 * x - a2 * y1
                val y2 = hb0 * y1 + z[zi + 2]
                z[zi + 2] = hb1 * y1 - ha1 * y2 + z[zi + 3]
                z[zi + 3] = hb2 * y1 - ha2 * y2
                blockSum += y2 * y2
                var s = (x * gain).toFloat()
                // 올릴 때 넘치는 것만 부드럽게 눌러 줌
                if (s > 0.95f) s = 0.95f + 0.05f * tanh((s - 0.95f) / 0.05f)
                else if (s < -0.95f) s = -0.95f - 0.05f * tanh((-s - 0.95f) / 0.05f)
                if (float) out.putFloat(s) else out.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            if (++blockFrames >= blockLen) endBlock()
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun lufs(energy: Double) = if (energy <= 1e-12) -120f else (-0.691 + 10.0 * log10(energy)).toFloat()

    private fun endBlock() {
        val e = blockSum / blockFrames
        blockFrames = 0
        blockSum = 0.0
        recent[recentPos] = e
        recentPos = (recentPos + 1) % ST_BLOCKS
        if (recentCount < ST_BLOCKS) recentCount++
        fun mean(n: Int): Double {
            var sum = 0.0
            for (i in 1..n) sum += recent[(recentPos - i + ST_BLOCKS) % ST_BLOCKS]
            return sum / n
        }
        val momentary = lufs(mean(minOf(4, recentCount)))
        val shortTerm = lufs(mean(recentCount))
        // 긴 평균 (약 10초): 조용한 곳(-50 LUFS 아래 · 평균보다 15dB 넘게 작은 곳)은 빼고
        if (momentary > -50f && (longCount < ST_BLOCKS || momentary > lufs(longEnergy) - 15f)) {
            val a = maxOf(1.0 / 100.0, 1.0 / (longCount + 1))
            longEnergy += (e - longEnergy) * a
            longCount++
        }
        var desired = if (longCount >= ST_BLOCKS) Loudness.TARGET_LUFS - lufs(longEnergy) else gainDb
        if (recentCount >= 10 && shortTerm + desired > Loudness.TARGET_LUFS + OVERSHOOT_DB)
            desired = Loudness.TARGET_LUFS + OVERSHOOT_DB - shortTerm
        desired = desired.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        // 줄일 땐 빨리(약 0.3초), 올릴 땐 천천히(약 7초)
        gainDb += (desired - gainDb) * (if (desired < gainDb) 0.3f else 0.015f)
        gainGoal = 10f.pow(gainDb / 20f)
        if (++blocksSinceSave >= 300) { // 30초마다 기억 + 진단 기록
            blocksSinceSave = 0
            Loudness.prefs(app).edit().putFloat(prefKey, gainDb).apply()
            if (longCount >= ST_BLOCKS)
                YtLog.e("음량 맞추기 $key: 원래 %.1f LUFS → %+.1fdB%s".format(lufs(longEnergy), gainDb,
                    if (Loudness.isEnabled(app)) "" else " (꺼짐)"))
        }
    }
}
