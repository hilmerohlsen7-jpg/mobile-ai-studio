package com.mobileaistudio.app

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import com.mobileaistudio.app.dl.DownloadItem
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Everything the page (index.html) can ask the app to do. The page only ever
 * loads from inside the app, so nothing from the internet can call these.
 */
class Bridge(private val activity: MainActivity, private val web: WebView) {
    private val ui = Handler(Looper.getMainLooper())
    private val modelsDir = File(activity.filesDir, "models").apply { mkdirs() }
    private val dl: com.mobileaistudio.app.dl.DownloadManager
    private val pushPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stateFile = File(activity.filesDir, "state.json")
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelChat = false
    private var handle = 0L
    private var loadedKey = ""
    private var importing: String? = null

    init {
        DownloadCenter.init(activity)
        dl = DownloadCenter.manager
        DownloadCenter.onChange = { schedulePush() }
    }

    private fun schedulePush() {
        if (pushPending.compareAndSet(false, true)) {
            ui.postDelayed({
                pushPending.set(false)
                js("__onDownloads", dlList())
            }, 350)
        }
    }

    // ---------- helpers ----------

    private fun js(fn: String, arg: String) {
        ui.post { web.evaluateJavascript("window.$fn && window.$fn(${JSONObject.quote(arg)})", null) }
    }

    private fun chatError(msg: String) = js("__onChatError", msg)

    fun shutdown() {
        cancelChat = true
        DownloadCenter.onChange = null
        worker.execute {
            if (handle != 0L) {
                Native.freeModel(handle)
                handle = 0L
            }
        }
        worker.shutdown()
    }

    // ---------- saved data ----------

    @JavascriptInterface
    fun loadState(): String {
        return try {
            synchronized(stateFile) { if (stateFile.exists()) stateFile.readText() else "" }
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun saveState(json: String): Boolean {
        return try {
            synchronized(stateFile) {
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json)
                if (stateFile.exists()) stateFile.delete()
                tmp.renameTo(stateFile)
            }
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun copyText(text: String) {
        ui.post {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("text", text))
        }
    }

    // ---------- phone info ----------

    @JavascriptInterface
    fun deviceInfo(): String {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val o = JSONObject()
        o.put("ramMB", mi.totalMem / 1048576L)
        o.put("cores", Runtime.getRuntime().availableProcessors())
        o.put("freeMB", modelsDir.usableSpace / 1048576L)
        o.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        o.put("engine", Native.ensureLoaded())
        return o.toString()
    }

    // ---------- models ----------

    @JavascriptInterface
    fun modelList(): String {
        val arr = JSONArray()
        val known = HashSet<String>()
        for (m in Catalog.all) {
            val f = File(modelsDir, m.file)
            known.add(m.file)
            val item = modelItem(m.file)
            val o = JSONObject()
            o.put("id", m.id)
            o.put("name", m.name)
            o.put("sizeMB", m.sizeMB)
            o.put("note", m.note)
            o.put("recommended", m.recommended)
            o.put("custom", false)
            o.put("state", if (f.exists()) "ready" else modelState(item))
            o.put("partMB", if (item != null) item.done.get() / 1048576L else 0L)
            o.put("dlId", item?.id ?: "")
            arr.put(o)
        }
        val extra = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && !known.contains(f.name) }
        for (f in extra ?: emptyArray()) {
            val o = JSONObject()
            o.put("id", "file:" + f.name)
            o.put("name", f.name.removeSuffix(".gguf"))
            o.put("sizeMB", f.length() / 1048576L)
            o.put("note", "Imported from your phone.")
            o.put("recommended", false)
            o.put("custom", true)
            o.put("state", "ready")
            o.put("partMB", 0L)
            o.put("dlId", "")
            arr.put(o)
        }
        // models still downloading that are not in the catalog (found online)
        for (it in dl.list()) {
            if (it.kind == "model" && it.name != null && !known.contains(it.name) &&
                it.state != DownloadItem.State.DONE && !File(modelsDir, it.name!!).exists()
            ) {
                val o = JSONObject()
                o.put("id", "file:" + it.name)
                o.put("name", it.name!!.removeSuffix(".gguf"))
                o.put("sizeMB", if (it.total > 0) it.total / 1048576L else 0L)
                o.put("note", "Found online.")
                o.put("recommended", false)
                o.put("custom", true)
                o.put("state", modelState(it))
                o.put("partMB", it.done.get() / 1048576L)
                o.put("dlId", it.id)
                arr.put(o)
            }
        }
        return arr.toString()
    }

    private fun modelItem(fileName: String): DownloadItem? =
        dl.list().firstOrNull { it.kind == "model" && it.name == fileName && it.state != DownloadItem.State.DONE }

    private fun modelState(it: DownloadItem?): String = when (it?.state) {
        null -> "none"
        DownloadItem.State.PAUSED -> "paused"
        DownloadItem.State.ERROR -> "error"
        else -> "downloading"
    }

    @JavascriptInterface
    fun downloadModel(id: String) {
        val m = Catalog.byId(id)
        if (m == null) {
            js("__onDownload", JSONObject().put("id", id).put("state", "error").put("msg", "Unknown model.").toString())
            return
        }
        val existing = modelItem(m.file)
        if (existing != null) {
            dl.resume(existing.id)
            return
        }
        try {
            ui.post { activity.askNotificationPermission() }
            dl.add(m.url, "model", m.id, modelsDir, m.file, 0)
        } catch (e: Exception) {
            js("__onDownload", JSONObject().put("id", id).put("state", "error").put("msg", e.message ?: "Could not start.").toString())
        }
    }

    // ---------- download manager ----------

    private fun itemJson(it: DownloadItem): JSONObject {
        val o = JSONObject()
        o.put("id", it.id)
        o.put("url", it.safeUrl())
        o.put("kind", it.kind)
        o.put("tag", it.tag)
        o.put("name", it.displayName())
        o.put("state", it.state.name.lowercase())
        o.put("msg", it.message ?: "")
        o.put("total", it.total)
        o.put("done", it.done.get())
        o.put("threads", it.threads)
        o.put("minT", 1)
        o.put("maxT", 16)
        o.put("step", 1)
        o.put("speed", it.speed)
        o.put("ftp", it.url.startsWith("ftp://", ignoreCase = true))
        return o
    }

    @JavascriptInterface
    fun dlList(): String {
        val arr = JSONArray()
        for (it in dl.list()) arr.put(itemJson(it))
        for (t in TorrentCenter.list()) arr.put(TorrentCenter.json(t))
        return arr.toString()
    }

    private fun isTorrent(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("magnet:") || u.substringBefore('?').endsWith(".torrent")
    }

    /** Adds any file by address (http, https, ftp). Returns {"ok":true,"id":..} or {"ok":false,"msg":..}. */
    @JavascriptInterface
    fun dlAdd(url: String, threads: Int): String {
        if (isTorrent(url)) {
            return try {
                ui.post { activity.askNotificationPermission() }
                JSONObject().put("ok", true).put("id", TorrentCenter.add(url)).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("msg", e.message ?: "Could not add it.").toString()
            }
        }
        return try {
            ui.post { activity.askNotificationPermission() }
            val id = dl.add(url, "file", "", DownloadCenter.filesDir, null, threads)
            JSONObject().put("ok", true).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("msg", e.message ?: "Could not add it.").toString()
        }
    }

    /** Downloads a model found online. The file name must end in .gguf. */
    @JavascriptInterface
    fun dlAddModel(url: String, fileName: String, threads: Int): String {
        val u = url.trim()
        if (!u.startsWith("https://huggingface.co/")) {
            return JSONObject().put("ok", false).put("msg", "Only Hugging Face models can be added here.").toString()
        }
        val name = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.lowercase().endsWith(".gguf") || name.startsWith(".")) {
            return JSONObject().put("ok", false).put("msg", "That is not a .gguf model file.").toString()
        }
        if (File(modelsDir, name).exists()) {
            return JSONObject().put("ok", false).put("msg", "You already have this model.").toString()
        }
        val existing = modelItem(name)
        if (existing != null) {
            dl.resume(existing.id)
            return JSONObject().put("ok", true).put("id", existing.id).toString()
        }
        return try {
            ui.post { activity.askNotificationPermission() }
            val id = dl.add(u, "model", "", modelsDir, name, threads)
            JSONObject().put("ok", true).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("msg", e.message ?: "Could not add it.").toString()
        }
    }

    @JavascriptInterface
    fun dlPause(id: String) = if (TorrentCenter.isTorrentId(id)) TorrentCenter.pause(id) else dl.pause(id)

    @JavascriptInterface
    fun dlResume(id: String) = if (TorrentCenter.isTorrentId(id)) TorrentCenter.resume(id) else dl.resume(id)

    @JavascriptInterface
    fun dlPauseAll() {
        dl.pauseAll()
        TorrentCenter.pauseAll()
    }

    @JavascriptInterface
    fun dlSetThreads(id: String, n: Int) = if (TorrentCenter.isTorrentId(id)) TorrentCenter.setConnections(id, n) else dl.setThreads(id, n)

    /** Removes the entry. deleteFile also deletes a finished file. */
    @JavascriptInterface
    fun dlRemove(id: String, deleteFile: Boolean) {
        if (TorrentCenter.isTorrentId(id)) TorrentCenter.remove(id, deleteFile) else dl.remove(id, deleteFile)
        js("__onModelsChanged", "")
    }

    @JavascriptInterface
    fun dlGetSettings(): String =
        JSONObject().put("max", dl.getMax()).put("threads", dl.getDefaultThreads()).toString()

    @JavascriptInterface
    fun dlSetSettings(max: Int, threads: Int) {
        DownloadCenter.saveSettings(max, threads)
        schedulePush()
    }

    /** Copies a finished download into the phone's public Downloads folder. */
    @JavascriptInterface
    fun dlSaveToDownloads(id: String) {
        if (TorrentCenter.isTorrentId(id)) {
            saveTorrentFiles(id)
            return
        }
        val it = dl.list().firstOrNull { d -> d.id == id }
        val src = it?.file()
        if (it == null || src == null || !src.exists() || it.state != DownloadItem.State.DONE) {
            js("__onToast", "That file is not ready.")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            js("__onToast", "Saving to Downloads needs Android 10 or newer.")
            return
        }
        Thread {
            try {
                val values = android.content.ContentValues()
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/MobileAIStudio")
                val uri = activity.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Could not create the file.")
                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it2 -> it2.copyTo(out, 64 * 1024) }
                } ?: throw Exception("Could not write the file.")
                js("__onToast", "Saved to Downloads/MobileAIStudio/${src.name}")
            } catch (e: Exception) {
                js("__onToast", "Could not save: ${e.message}")
            }
        }.start()
    }

    private fun saveTorrentFiles(id: String) {
        val files = TorrentCenter.filesOf(id)
        if (files.isEmpty()) {
            js("__onToast", "That file is not ready.")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            js("__onToast", "Saving to Downloads needs Android 10 or newer.")
            return
        }
        Thread {
            try {
                for ((src, rel) in files) {
                    val values = android.content.ContentValues()
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, rel.substringAfterLast('/'))
                    val sub = rel.substringBeforeLast('/', "")
                    values.put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/MobileAIStudio" + (if (sub.isNotEmpty()) "/$sub" else "")
                    )
                    val uri = activity.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw Exception("Could not create the file.")
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it2 -> it2.copyTo(out, 64 * 1024) }
                    } ?: throw Exception("Could not write the file.")
                }
                js("__onToast", "Saved ${files.size} file(s) to Downloads/MobileAIStudio")
            } catch (e: Exception) {
                js("__onToast", "Could not save: ${e.message}")
            }
        }.start()
    }

    private val httpPool = Executors.newFixedThreadPool(2)

    /** Reads a page from huggingface.co (for searching models). The answer arrives in __onHttp. */
    @JavascriptInterface
    fun httpGet(reqId: String, url: String) {
        httpPool.execute {
            val o = JSONObject().put("id", reqId)
            try {
                val u = java.net.URL(url)
                if (u.protocol != "https" || u.host != "huggingface.co") throw Exception("Not allowed.")
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "MobileAIStudio/0.2")
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val bytes = java.io.ByteArrayOutputStream()
                stream?.use { ins ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                        if (bytes.size() > 6_000_000) throw Exception("The answer was too large.")
                    }
                }
                o.put("ok", code in 200..299).put("status", code).put("body", bytes.toString("UTF-8"))
                c.disconnect()
            } catch (e: Exception) {
                o.put("ok", false).put("status", 0).put("body", "").put("msg", e.message ?: "Network problem.")
            }
            js("__onHttp", o.toString())
        }
    }

    @JavascriptInterface
    fun deleteModel(id: String) {
        val f = Catalog.fileFor(modelsDir, id) ?: return
        worker.execute {
            if (handle != 0L && loadedKey.startsWith("$id|")) {
                Native.freeModel(handle)
                handle = 0L
                loadedKey = ""
            }
            for (d in dl.list()) {
                if (d.kind == "model" && d.name == f.name) dl.remove(d.id, true)
            }
            f.delete()
            File(f.path + ".part").delete()
            js("__onModelsChanged", "")
        }
    }

    @JavascriptInterface
    fun importModel() {
        ui.post { activity.pickModelFile() }
    }

    /** Called by the activity after the person picked a file. */
    fun onModelPicked(uri: Uri) {
        if (importing != null) return
        Thread {
            var name = "model.gguf"
            try {
                activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
            } catch (e: Exception) {
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val id = "file:$name"
            importing = id
            try {
                if (!name.lowercase().endsWith(".gguf")) {
                    throw Exception("That is not a .gguf model file.")
                }
                val part = File(modelsDir, "$name.part")
                val dest = File(modelsDir, name)
                var total = 0L
                activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { total = it.length }
                if (total > 0 && modelsDir.usableSpace < total + 50L * 1048576L) {
                    throw Exception("Not enough free storage for this file.")
                }
                var done = 0L
                var last = 0L
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - last > 300) {
                                last = now
                                js(
                                    "__onDownload",
                                    JSONObject().put("id", id).put("state", "downloading")
                                        .put("done", done).put("total", total).toString()
                                )
                            }
                        }
                    }
                } ?: throw Exception("Could not open that file.")
                if (!Catalog.looksLikeGguf(part)) {
                    part.delete()
                    throw Exception("That file is not a valid model.")
                }
                if (dest.exists()) dest.delete()
                part.renameTo(dest)
                js("__onDownload", JSONObject().put("id", id).put("state", "ready").toString())
            } catch (e: Exception) {
                js(
                    "__onDownload",
                    JSONObject().put("id", id).put("state", "error").put("msg", e.message ?: "Import failed.").toString()
                )
            } finally {
                importing = null
            }
        }.start()
    }

    // ---------- chat ----------

    @JavascriptInterface
    fun chat(requestJson: String) {
        cancelChat = false
        worker.execute {
            try {
                runChat(JSONObject(requestJson))
            } catch (t: Throwable) {
                chatError("Something went wrong: ${t.message}")
            }
        }
    }

    @JavascriptInterface
    fun stopChat() {
        cancelChat = true
    }

    private fun runChat(req: JSONObject) {
        if (!Native.ensureLoaded()) {
            chatError("The AI engine could not start on this phone. It needs a 64-bit ARM phone.")
            return
        }
        val id = req.getString("model")
        val file = Catalog.fileFor(modelsDir, id)
        if (file == null || !file.exists()) {
            chatError("That model is not downloaded yet. Open Models and download it.")
            return
        }
        val nCtx = req.optInt("n_ctx", 2048).coerceIn(512, 8192)
        val threads = req.optInt("threads", 4).coerceIn(1, 8)
        var maxTok = req.optInt("max_tokens", 512).coerceIn(16, 2048)
        val temp = req.optDouble("temperature", 0.7).toFloat()

        val key = "$id|$nCtx|$threads"
        if (handle == 0L || key != loadedKey) {
            js("__onStatus", "Loading the model...")
            if (handle != 0L) {
                Native.freeModel(handle)
                handle = 0L
                loadedKey = ""
            }
            handle = Native.loadModel(file.absolutePath, nCtx, threads)
            if (handle == 0L) {
                chatError("Could not load the model. If this keeps happening, delete it in Models and download it again.")
                return
            }
            loadedKey = key
        }

        val msgs = req.getJSONArray("messages")
        val roles = ArrayList<String>()
        val contents = ArrayList<ByteArray>()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            roles.add(m.getString("role"))
            contents.add(m.getString("content").toByteArray(Charsets.UTF_8))
        }
        if (roles.isEmpty()) {
            chatError("Nothing to send.")
            return
        }

        // Keep the prompt inside the memory size: drop the oldest chat messages first.
        val limit = nCtx - maxTok - 16
        var count = Native.countTokens(handle, roles.toTypedArray(), contents.toTypedArray())
        while (count > limit && roles.size > 2) {
            roles.removeAt(1)
            contents.removeAt(1)
            count = Native.countTokens(handle, roles.toTypedArray(), contents.toTypedArray())
        }
        if (count < 0) {
            chatError("Could not read the message.")
            return
        }
        if (count > limit) {
            val room = nCtx - count - 16
            if (room < 32) {
                chatError("This is too long for the memory size. Remove some project files, shorten the message, or raise Memory size in Settings.")
                return
            }
            maxTok = room
        }

        js("__onStatus", "Thinking...")
        val started = SystemClock.elapsedRealtime()
        val sink = object : TokenSink {
            private val buf = StringBuilder()
            private var last = 0L

            fun flush() {
                if (buf.isNotEmpty()) {
                    js("__onToken", buf.toString())
                    buf.setLength(0)
                }
                last = SystemClock.elapsedRealtime()
            }

            override fun onToken(bytes: ByteArray): Boolean {
                buf.append(String(bytes, Charsets.UTF_8))
                if (SystemClock.elapsedRealtime() - last >= 40) flush()
                return !cancelChat
            }

            override fun isCancelled(): Boolean = cancelChat
        }
        val n = Native.generate(handle, roles.toTypedArray(), contents.toTypedArray(), maxTok, temp, sink)
        sink.flush()

        when {
            n == -2 -> chatError("This is too long for the memory size. Raise Memory size in Settings or shorten the chat.")
            n < 0 -> chatError("The AI engine stopped unexpectedly. Try again, or pick a smaller model.")
            else -> {
                val o = JSONObject()
                o.put("tokens", n)
                o.put("ms", SystemClock.elapsedRealtime() - started)
                o.put("stopped", cancelChat)
                js("__onChatDone", o.toString())
            }
        }
    }
}
