package com.mobileaistudio.app

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one model at a time. A half-finished download is kept as ".part"
 * and resumed from where it stopped the next time.
 */
class Downloader(
    private val dir: File,
    private val report: (String) -> Unit
) {
    @Volatile
    var activeId: String? = null
        private set

    @Volatile
    private var cancelled = false

    /** Returns an error message, or null when the download has started. */
    @Synchronized
    fun start(m: ModelInfo): String? {
        if (activeId != null) return "Another download is already running."
        activeId = m.id
        cancelled = false
        Thread { download(m) }.start()
        return null
    }

    fun cancel() {
        cancelled = true
    }

    private fun send(id: String, state: String, done: Long, total: Long, msg: String) {
        val o = JSONObject()
        o.put("id", id)
        o.put("state", state)
        o.put("done", done)
        o.put("total", total)
        o.put("msg", msg)
        report(o.toString())
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MobileAIStudio/0.1")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun download(m: ModelInfo) {
        val part = File(dir, m.file + ".part")
        val dest = File(dir, m.file)
        var lastReport = 0L
        try {
            send(m.id, "downloading", 0, m.sizeMB * 1048576L, "")
            var existing = if (part.exists()) part.length() else 0L
            var conn = open(m.url, existing)
            var code = conn.responseCode
            if (code == 416) {
                // the saved part does not fit what the server has: start again
                conn.disconnect()
                part.delete()
                existing = 0
                conn = open(m.url, 0)
                code = conn.responseCode
            }
            if (code != 200 && code != 206) {
                throw IOException("The download server answered $code. Try again later.")
            }
            val append = code == 206 && existing > 0
            if (!append) existing = 0
            val len = conn.contentLengthLong
            val total = if (len > 0) existing + len else m.sizeMB * 1048576L
            val need = total - existing
            if (dir.usableSpace < need + 50L * 1048576L) {
                throw IOException("Not enough free storage. This model needs about ${need / 1048576} MB.")
            }

            var done = existing
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) {
                            send(m.id, "cancelled", done, total, "")
                            return
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 300) {
                            lastReport = now
                            send(m.id, "downloading", done, total, "")
                        }
                    }
                }
            }

            if (len > 0 && part.length() != existing + len) {
                throw IOException("The download was cut off. Tap Resume to continue.")
            }
            if (!Catalog.looksLikeGguf(part)) {
                part.delete()
                throw IOException("The downloaded file is not a model (the link may have changed).")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save the model file.")
            send(m.id, "ready", dest.length(), dest.length(), "")
        } catch (e: Exception) {
            send(m.id, "error", 0, 0, e.message ?: "The download failed.")
        } finally {
            activeId = null
        }
    }
}
