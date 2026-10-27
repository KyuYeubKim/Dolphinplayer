package com.dolphin.mplayer.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dolphin.mplayer.R
import com.dolphin.mplayer.youtube.YtService
import java.io.File
import java.util.concurrent.Executors

/**
 * USB / MicroSD(및 내부 저장소)의 음악·영상 재생.
 *
 *  - 찾기: Android 미디어 목록(MediaStore)을 먼저 읽고, 거기에 없는 이동식 저장소는 폴더를 직접 훑는다.
 *  - 재생: ExoPlayer. 재생 정보와 재생 목록(대기열)을 Android MediaSession 으로 내보내므로
 *    D-Player 플레이 화면·재생 목록·핸들 미디어 버튼이 그대로 동작한다.
 *  - 영상은 같은 플레이어를 VideoActivity 의 화면에 붙여 전체화면으로 보여준다.
 */
object LocalEngine : YtService.Source {

    /** MediaSession 태그 (D-Player 의 YouTube 세션과 구분) */
    const val TAG = "D-Player Local"
    const val EXTRA_LOCAL = "dplayer.local"
    private const val LOG = "LocalEngine"
    private const val ACTION_SHUFFLE = "dplayer.local.shuffle"

    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr", "mka")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "ts", "m4v", "wmv")

    data class Item(
        val uri: Uri,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        /** 표시용 폴더 경로: "<저장소 이름>/Music/Album" */
        val folder: String,
        val video: Boolean,
    )

    /** 저장소 (USB, SD 카드, 내부 저장소) */
    data class Volume(val label: String, val path: String, val removable: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var app: Context
    @Volatile var items: List<Item> = emptyList()
        private set
    @Volatile var volumes: List<Volume> = emptyList()
        private set
    @Volatile var scanned = false
        private set
    private var scanning = false
    private val scanWaiters = ArrayList<() -> Unit>()

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<Item> = emptyList()
    private var art: Bitmap? = null
    private var artFor: Uri? = null

    override val playing: Boolean get() = player?.isPlaying == true
    val shuffleEnabled: Boolean get() = player?.shuffleModeEnabled == true
    override val label: String get() = "USB/SD"

    val current: Item? get() = player?.currentMediaItemIndex?.let { queue.getOrNull(it) }

    override fun currentTitle(): String = current?.title.orEmpty()
    override fun sessionToken(): MediaSession.Token? = session?.sessionToken

    // ---------- 찾기 ----------

    /** 저장소를 훑어 음악·영상 목록을 만든다 (백그라운드). force = 다시 훑기 */
    fun scan(context: Context, force: Boolean = false, done: () -> Unit) {
        app = context.applicationContext
        if (scanned && !force) return done()
        scanWaiters.add(done)
        if (scanning) return
        scanning = true
        io.execute {
            val vols = findVolumes()
            val found = runCatching { scanMediaStore(vols) }.onFailure { Log.w(LOG, "mediastore", it) }
                .getOrDefault(emptyList()).toMutableList()
            // 미디어 목록에 하나도 없는 이동식 저장소는 폴더를 직접 훑는다
            for (v in vols.filter { it.removable }) {
                if (found.none { it.folder == v.label || it.folder.startsWith(v.label + "/") }) {
                    runCatching { scanFiles(File(v.path), v, found) }.onFailure { Log.w(LOG, "files ${v.path}", it) }
                }
            }
            val sorted = found.distinctBy { it.uri }
            main.post {
                items = sorted
                volumes = vols
                scanned = true
                scanning = false
                val waiters = ArrayList(scanWaiters)
                scanWaiters.clear()
                waiters.forEach { it() }
            }
        }
    }

    /** 저장소 목록: 이동식(USB/SD) 먼저, 내부 저장소는 마지막 */
    private fun findVolumes(): List<Volume> {
        val sm = app.getSystemService(StorageManager::class.java)
        val list = sm.storageVolumes.mapNotNull { v ->
            val path = if (Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath
            else runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }.getOrNull()
            path ?: return@mapNotNull null
            val label = v.getDescription(app)?.ifBlank { null } ?: File(path).name
            Volume(if (v.isRemovable) label else "내부 저장소", path, v.isRemovable)
        }
        return list.sortedBy { !it.removable }
    }

    private fun volumeFor(path: String, vols: List<Volume>): Volume? =
        vols.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    /** 절대 경로의 폴더 → 표시용 폴더 경로 */
    private fun displayFolder(dir: String, v: Volume): String =
        (v.label + dir.removePrefix(v.path)).trimEnd('/')

    private fun scanMediaStore(vols: List<Volume>): List<Item> {
        val out = ArrayList<Item>()
        fun query(video: Boolean) {
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val cols = mutableListOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
            )
            cols += if (video) listOf(MediaStore.Video.Media.DURATION, MediaStore.Video.Media.ARTIST, MediaStore.Video.Media.ALBUM)
            else listOf(MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
            val selection = if (video) null else "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            app.contentResolver.query(uri, cols.toTypedArray(), selection, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val iArtist = c.getColumnIndex(if (video) MediaStore.Video.Media.ARTIST else MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndex(if (video) MediaStore.Video.Media.ALBUM else MediaStore.Audio.Media.ALBUM)
                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: continue
                    val v = volumeFor(path, vols) ?: continue
                    val name = c.getString(iName) ?: File(path).name
                    val artist = c.getString(iArtist).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                    out += Item(
                        uri = ContentUris.withAppendedId(uri, c.getLong(iId)),
                        title = c.getString(iTitle)?.ifBlank { null } ?: name.substringBeforeLast('.'),
                        artist = artist,
                        album = c.getString(iAlbum).orEmpty(),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0,
                        folder = displayFolder(File(path).parent ?: v.path, v),
                        video = video,
                    )
                }
            }
        }
        query(video = false)
        query(video = true)
        return out
    }

    /** 미디어 목록에 없는 저장소: 폴더를 직접 훑는다 (깊이·개수 제한) */
    private fun scanFiles(root: File, v: Volume, out: MutableList<Item>) {
        var count = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || count > 5000) return
            val list = dir.listFiles() ?: return
            for (f in list.sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else {
                    val ext = f.extension.lowercase()
                    val video = ext in VIDEO_EXT
                    if (!video && ext !in AUDIO_EXT) continue
                    count++
                    out += Item(Uri.fromFile(f), f.nameWithoutExtension, "", "", 0,
                        displayFolder(f.parent ?: v.path, v), video)
                }
            }
        }
        walk(root, 0)
    }

    // ---------- 폴더 탐색 (표시용 경로 기준) ----------

    /** 폴더 바로 아래의 하위 폴더 이름들 */
    fun subFolders(folder: String): List<String> {
        val prefix = "$folder/"
        return items.asSequence().map { it.folder }.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sortedBy { it.lowercase() }.toList()
    }

    /** 폴더 바로 안의 파일들 */
    fun filesIn(folder: String): List<Item> =
        items.filter { it.folder == folder }.sortedBy { it.title.lowercase() }

    /** 폴더와 그 하위 폴더 전체의 음악 */
    fun audioUnder(folder: String): List<Item> =
        items.filter { !it.video && (it.folder == folder || it.folder.startsWith("$folder/")) }
            .sortedWith(compareBy({ it.folder.lowercase() }, { it.title.lowercase() }))

    fun countUnder(folder: String): Pair<Int, Int> {
        val all = items.filter { it.folder == folder || it.folder.startsWith("$folder/") }
        return all.count { !it.video } to all.count { it.video }
    }

    /** 파일이 있는 저장소만 (첫 화면) */
    fun rootFolders(): List<Volume> = volumes.filter { v -> items.any { it.folder == v.label || it.folder.startsWith(v.label + "/") } }

    // ---------- 재생 ----------

    /** 목록 재생: start 번째 곡부터. shuffle = 셔플 켜고 무작위 곡부터 */
    fun play(context: Context, list: List<Item>, start: Int, shuffle: Boolean) {
        if (list.isEmpty()) return
        val p = ensurePlayer(context)
        queue = list
        queueSource = sourceOf(list)
        p.setMediaItems(list.map { MediaItem.fromUri(it.uri) }, start.coerceIn(0, list.size - 1), 0)
        p.shuffleModeEnabled = shuffle
        p.prepare()
        p.play()
        publishQueue()
    }

    // ---------- 시동 후 이어 듣기 (0.55) ----------
    // 시동을 끄기 전 USB 곡 · 위치 · 목록(폴더)을 기억했다가, 다시 켜면 같은 곡 같은 위치부터.
    // 저장은 곡이 바뀔 때 · 멈출 때 · 재생 중 15초마다 한 번 (가벼운 설정 파일 쓰기만). 영상은 이어 듣기 안 함.

    private const val PREFS = "local_last"

    /** 지금 목록이 어디서 왔는지: "in:<폴더>" (그 폴더 파일) 또는 "under:<폴더>" (하위 폴더까지 음악 전체) */
    private var queueSource = ""

    private fun sourceOf(list: List<Item>): String {
        val folders = list.map { it.folder }.distinct()
        if (folders.size == 1) return "in:" + folders[0]
        var common = folders.first()
        for (f in folders) while (common.isNotEmpty() && f != common && !f.startsWith("$common/")) common = common.substringBeforeLast('/', "")
        return "under:$common"
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveLast() {
        val p = player ?: return
        val item = current ?: return
        if (item.video || queueSource.isEmpty()) return
        prefs(app).edit()
            .putString("uri", item.uri.toString())
            .putString("source", queueSource)
            .putLong("pos", p.currentPosition)
            .putBoolean("playing", p.isPlaying)
            .putBoolean("shuffle", p.shuffleModeEnabled)
            .putString("title", item.title)
            .apply()
    }

    private val saveTick = object : Runnable {
        override fun run() {
            saveLast()
            if (player?.isPlaying == true) main.postDelayed(this, 15_000)
        }
    }

    /** 이어 들을 곡이 있는지 (끄기 전 재생 중이었는지 포함) */
    fun hasLast(c: Context): Boolean = prefs(c).getString("uri", null) != null
    fun lastWasPlaying(c: Context): Boolean = prefs(c).getBoolean("playing", false)

    /**
     * 마지막 곡을 다시 준비 (autoPlay = 바로 재생). USB 가 아직 안 잡혔으면 15초 뒤 한 번 더.
     * done(true) = 준비함. 이미 재생 중이면 아무것도 안 함.
     */
    fun restoreLast(context: Context, autoPlay: Boolean, retry: Boolean = true, done: (Boolean) -> Unit = {}) {
        app = context.applicationContext
        if (player?.isPlaying == true) return done(false)
        val pr = prefs(app)
        val uri = pr.getString("uri", null) ?: return done(false)
        val source = pr.getString("source", "").orEmpty()
        scan(app) {
            val list = when {
                source.startsWith("in:") -> filesIn(source.removePrefix("in:")).filter { !it.video }
                source.startsWith("under:") -> audioUnder(source.removePrefix("under:"))
                else -> emptyList()
            }
            val idx = list.indexOfFirst { it.uri.toString() == uri }
            if (idx < 0) {
                if (retry) main.postDelayed({ restoreLast(app, autoPlay, retry = false, done = done) }, 15_000)
                else done(false)
                return@scan
            }
            val p = ensurePlayer(app)
            queue = list
            queueSource = source
            p.setMediaItems(list.map { MediaItem.fromUri(it.uri) }, idx, pr.getLong("pos", 0))
            p.shuffleModeEnabled = pr.getBoolean("shuffle", false)
            p.prepare()
            if (autoPlay) p.play()
            publishQueue()
            done(true)
        }
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    fun stop() {
        player?.pause()
        player?.seekTo(0)
    }
    override fun next() { player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }
    override fun previous() { player?.seekToPrevious() }
    fun seekTo(ms: Long) { player?.seekTo(ms) }
    /** 영상 가로/세로 비율 (영상이 아니거나 아직 모르면 0) */
    fun videoAspect(): Float {
        val v = player?.videoSize ?: return 0f
        if (v.width == 0 || v.height == 0) return 0f
        return v.width * v.pixelWidthHeightRatio / v.height
    }
    fun position(): Long = player?.currentPosition ?: 0
    fun duration(): Long = player?.duration?.takeIf { it != C.TIME_UNSET } ?: (current?.durationMs ?: 0)
    fun skipTo(index: Int) { player?.seekTo(index, 0) }
    fun toggleShuffle() { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    /** 영상 전체화면(VideoActivity)이 쓰는 재생원. 화면을 닫으면 영상은 일시정지 */
    val videoSource = object : VideoSource {
        override val playing get() = this@LocalEngine.playing
        override fun play() = this@LocalEngine.play()
        override fun pause() = this@LocalEngine.pause()
        override fun seekTo(ms: Long) = this@LocalEngine.seekTo(ms)
        override fun position() = this@LocalEngine.position()
        override fun duration() = this@LocalEngine.duration()
        override fun title() = currentTitle()
        override fun videoAspect() = this@LocalEngine.videoAspect()
        override fun attach(view: SurfaceView) { player?.setVideoSurfaceView(view) }
        override fun detach(view: SurfaceView) {
            if (current?.video == true) pause()
            player?.clearVideoSurfaceView(view)
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        app = context.applicationContext
        val p = ExoPlayer.Builder(app).build()
        // 오디오 포커스는 ExoPlayer 가 처리 (다른 앱이 재생하면 멈춤, 내비 안내 때 볼륨 낮춤), 이어폰/블루투스 해제 시 멈춤
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishMetadata()
                publishState()
                saveLast()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState()
                saveLast()
                main.removeCallbacks(saveTick)
                if (isPlaying) main.postDelayed(saveTick, 15_000)
            }
            override fun onPlaybackStateChanged(playbackState: Int) = publishState()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publishState()
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) = publishState()
        })
        player = p
        ensureSession()
        return p
    }

    // ---------- MediaSession ----------

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(app, TAG).apply {
            // Android 10 은 MediaController.getTag() 가 없어 이 표시로 USB/SD 세션을 알아본다
            setExtras(android.os.Bundle().apply { putBoolean(EXTRA_LOCAL, true) })
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onSkipToQueueItem(id: Long) = skipTo(id.toInt())
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_SHUFFLE) toggleShuffle()
                }
            })
        }
    }

    private fun publishQueue() {
        val s = session ?: return
        s.setQueue(queue.mapIndexed { i, it ->
            MediaSession.QueueItem(
                android.media.MediaDescription.Builder().setMediaId(it.uri.toString())
                    .setTitle(it.title).setSubtitle(it.artist.ifEmpty { if (it.video) "영상" else it.folder.substringAfterLast('/') })
                    .build(), i.toLong()
            )
        })
        publishMetadata()
        publishState()
    }

    private fun publishMetadata() {
        val s = session ?: return
        val item = current ?: return
        val p = player
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist.ifEmpty { item.folder.substringAfterLast('/') })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                    p?.duration?.takeIf { it != C.TIME_UNSET } ?: item.durationMs)
                .apply { if (artFor == item.uri) art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        if (artFor != item.uri) loadArt(item)
    }

    /** 곡에 들어 있는 앨범 커버(영상은 장면 한 컷) */
    private fun loadArt(item: Item) {
        artFor = item.uri
        art = null
        io.execute {
            val bmp = runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(app, item.uri)
                    r.embeddedPicture?.let { com.dolphin.mplayer.ImageLoader.decodeScaled(it) } // 원본(수 MB) 대신 화면 크기로
                        ?: if (!item.video) null
                        else if (Build.VERSION.SDK_INT >= 27) // 영상 장면도 작게 (원본 해상도면 한 장 8MB)
                            r.getScaledFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                        else r.getFrameAtTime(3_000_000)
                } finally {
                    r.release()
                }
            }.getOrNull()
            main.post {
                if (artFor == item.uri && bmp != null) {
                    art = bmp
                    publishMetadata()
                }
            }
        }
    }

    private fun publishState() {
        val s = session ?: return
        val p = player ?: return
        val state = when {
            p.isPlaying -> PlaybackState.STATE_PLAYING
            p.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            p.playbackState == Player.STATE_IDLE -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_SHUFFLE, "셔플 (shuffle)", R.drawable.ic_shuffle).build()
                )
                .setActiveQueueItemId(p.currentMediaItemIndex.toLong())
                .setState(state, p.currentPosition, if (p.isPlaying) 1f else 0f, SystemClock.elapsedRealtime())
                .build()
        )
        if (!s.isActive) s.isActive = true
        YtService.update(app, this, p.isPlaying)
    }
}
