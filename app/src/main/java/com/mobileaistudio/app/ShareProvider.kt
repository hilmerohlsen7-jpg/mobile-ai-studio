package com.mobileaistudio.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Read-only door so other apps can open or receive a file the person chose to share or open with. */
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.mobileaistudio.app.share"
        fun uriFor(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath("f").appendQueryParameter("p", f.absolutePath).build()
        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"
    }

    private fun fileOf(uri: Uri): File {
        val p = uri.getQueryParameter("p") ?: throw FileNotFoundException()
        val f = File(p)
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = try { mimeOf(fileOf(uri).name) } catch (e: Exception) { "*/*" }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = try { fileOf(uri) } catch (e: Exception) { return null }
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
