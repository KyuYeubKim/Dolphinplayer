package com.dolphin.mplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 커버 이미지 로더. content:// · android.resource:// · file:// · http(s):// 지원 + 메모리 캐시.
 * 화면에 쓰는 크기(최대 [MAX_PX])로 줄여 읽고, 캐시는 용량(16MB)으로 제한한다 —
 * 예전에는 원본 크기를 120장까지 쌓아 큰 앨범 사진이면 수백 MB 까지 커질 수 있었다 (0.51).
 */
class ImageLoader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, bitmap: Bitmap?, uri: Uri?, placeholder: Int) {
        if (bitmap != null) {
            view.tag = null
            view.setImageBitmap(bitmap)
            return
        }
        val key = uri?.toString()
        view.tag = key
        if (key == null) {
            view.setImageResource(placeholder)
            return
        }
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageResource(placeholder)
        pool.execute {
            val bmp = runCatching { decode(uri, key) }.getOrNull() ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    private fun open(uri: Uri, key: String) = when (uri.scheme) {
        "http", "https" -> (URL(key).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }.inputStream
        else -> resolver.openInputStream(uri)
    }

    /** 크기만 먼저 읽고 화면에 필요한 만큼만 줄여서 디코딩 */
    private fun decode(uri: Uri, key: String): Bitmap? {
        val bytes = open(uri, key)?.use { it.readBytes() } ?: return null
        return decodeScaled(bytes)
    }

    companion object {
        const val MAX_PX = 640

        /** 바이트 배열 이미지를 긴 변이 [MAX_PX] 근처가 되도록 줄여서 디코딩 (앨범 사진 등) */
        fun decodeScaled(bytes: ByteArray, maxPx: Int = MAX_PX): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
