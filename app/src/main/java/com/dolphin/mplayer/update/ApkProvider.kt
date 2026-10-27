package com.dolphin.mplayer.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 다운로드한 업데이트 APK 를 설치 관리자에 넘기기 위한 최소 ContentProvider.
 * (androidx FileProvider 의존성 없이) content://<패키지>.apk/<파일명> → files/update/<파일명>
 */
class ApkProvider : ContentProvider() {

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        // 하위 경로 탈출 방지: update 폴더의 .apk 파일만 제공
        if (name.contains('/') || !name.endsWith(".apk")) throw FileNotFoundException(name)
        val f = File(File(ctx.getExternalFilesDir(null), "update"), name)
        if (!f.exists()) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"

    /** 설치 관리자가 파일 이름/크기를 물어볼 때 */
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
