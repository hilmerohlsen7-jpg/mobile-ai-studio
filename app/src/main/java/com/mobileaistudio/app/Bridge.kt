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
import com.mobileaistudio.app.fs.FileOps
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
        try { tts?.shutdown() } catch (e: Throwable) {}
        try { recognizer?.destroy() } catch (e: Throwable) {}
        DownloadCenter.onChange = null
        worker.execute {
            if (handle != 0L) {
                Native.freeModel(handle)
                handle = 0L
            }
        }
        worker.shutdown()
        fsPool.shutdown()
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
        o.put("bigCores", bigCores())
        o.put("freeMB", modelsDir.usableSpace / 1048576L)
        o.put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
        val okEngine = Native.ensureLoaded(activity.applicationInfo.nativeLibraryDir)
        o.put("engine", okEngine)
        if (okEngine) {
            try {
                val si = Native.systemInfo()
                val feats = ArrayList<String>()
                for (k in listOf("DOTPROD", "MATMUL_INT8", "FP16_VA", "SVE", "SME")) if (Regex("\\b$k\\s*=\\s*1").containsMatchIn(si)) feats.add(k)
                o.put("cpu", if (feats.isEmpty()) "basic" else feats.joinToString(" + "))
            } catch (e: Throwable) {
            }
        }
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
            o.put("vision", mmprojFor(m.file).exists())
            arr.put(o)
        }
        val extra = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && !f.name.endsWith(".mmproj.gguf") && !known.contains(f.name) }
        for (f in extra ?: emptyArray()) {
            val o = JSONObject()
            o.put("id", "file:" + f.name)
            o.put("name", f.name.removeSuffix(".gguf"))
            o.put("sizeMB", f.length() / 1048576L)
            o.put("note", "Imported from your phone.")
            o.put("vision", mmprojFor(f.name).exists())
            o.put("recommended", false)
            o.put("custom", true)
            o.put("state", "ready")
            o.put("partMB", 0L)
            o.put("dlId", "")
            arr.put(o)
        }
        // models still downloading that are not in the catalog (found online)
        for (it in dl.list()) {
            if (it.kind == "model" && it.name != null && !known.contains(it.name) && !it.name!!.endsWith(".mmproj.gguf") &&
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

    // ---------- files on the phone ----------

    private val fsPool = Executors.newSingleThreadExecutor()
    private val fsOps: FileOps by lazy {
        val root = android.os.Environment.getExternalStorageDirectory()
        val trash = File(root, ".MobileAIStudio-Trash")
        // The history lives next to the Trash, so it survives restarts, updates and even a reinstall.
        val hist = File(trash, "history.txt")
        val old = File(activity.filesDir, "fs-history.txt")
        try {
            if (old.exists() && !hist.exists() && activity.hasFilesAccess()) {
                trash.mkdirs()
                old.copyTo(hist)
            }
        } catch (e: Exception) {
        }
        FileOps(root, trash, hist)
    }

    @JavascriptInterface
    fun fsAccess(): Boolean = activity.hasFilesAccess()

    @JavascriptInterface
    fun fsRequestAccess() {
        ui.post { activity.requestFilesAccess() }
    }

    @JavascriptInterface
    fun fsRoot(): String = fsOps.root().path

    private fun fsNoAccess() = JSONObject().put("ok", false).put("code", "access")
        .put("msg", "Allow access to all files first.")

    @JavascriptInterface
    fun fsList(path: String): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        return try {
            val arr = JSONArray()
            for (i in fsOps.list(path)) {
                arr.put(JSONObject().put("name", i.name).put("dir", i.dir).put("size", i.size).put("mtime", i.mtime))
            }
            JSONObject().put("ok", true).put("path", fsOps.resolve(path).path).put("items", arr).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("msg", e.message ?: "Could not open it.").toString()
        }
    }

    @JavascriptInterface
    fun fsRead(path: String): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        return try {
            val t = fsOps.read(path)
            JSONObject().put("ok", true).put("text", t.text).put("truncated", t.truncated).put("size", t.size).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("msg", e.message ?: "Could not read it.").toString()
        }
    }

    private fun entryJson(e: FileOps.Entry): JSONObject = JSONObject()
        .put("id", e.id).put("time", e.time).put("op", e.op).put("path", e.path).put("from", e.from)
        .put("reason", e.reason).put("undone", e.undone).put("purged", e.purged).put("size", e.size)

    @JavascriptInterface
    fun fsHistory(): String {
        val arr = JSONArray()
        for (e in fsOps.history()) arr.put(entryJson(e))
        return arr.toString()
    }

    @JavascriptInterface
    fun fsTrash(): String {
        val arr = JSONArray()
        for (e in fsOps.trashEntries()) arr.put(entryJson(e))
        return arr.toString()
    }

    private fun fsReply(reqId: String, r: FileOps.Result?, extra: JSONObject? = null) {
        val o = extra ?: JSONObject()
        o.put("id", reqId)
        if (r != null) {
            o.put("ok", r.ok).put("msg", r.msg).put("code", r.code).put("path", r.path)
            o.put("entryId", r.entry?.id ?: "")
        }
        js("__onFs", o.toString())
    }

    /** Runs one change that the person has already approved in the app. The answer arrives in __onFs. */
    @JavascriptInterface
    fun fsOp(reqId: String, opJson: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) {
                fsReply(reqId, null, fsNoAccess())
                return@execute
            }
            try {
                val o = JSONObject(opJson)
                val reason = o.optString("reason", "")
                val r = when (o.getString("type")) {
                    "write" -> fsOps.write(o.getString("path"), o.optString("text", ""), o.optBoolean("asCopy", false), reason)
                    "mkdir" -> fsOps.mkdir(o.getString("path"), reason)
                    "rename" -> fsOps.rename(o.getString("path"), o.getString("newName"), reason)
                    "copy" -> fsOps.copy(o.getString("path"), o.getString("destDir"), reason)
                    "delete" -> fsOps.delete(o.getString("path"), reason)
                    else -> null
                }
                if (r == null) fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "Unknown action."))
                else fsReply(reqId, r)
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Something went wrong."))
            }
        }
    }

    /** Finds the biggest files under a folder. The answer arrives in __onFs with "files". */
    @JavascriptInterface
    fun fsLargest(reqId: String, path: String, minMB: Int, limit: Int) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) {
                fsReply(reqId, null, fsNoAccess())
                return@execute
            }
            try {
                val sc = fsOps.largest(path, minMB.toLong().coerceAtLeast(1) * 1048576L, limit.coerceIn(1, 60), 60000)
                val arr = JSONArray()
                for (b in sc.files) arr.put(JSONObject().put("path", b.path).put("size", b.size).put("mtime", b.mtime))
                fsReply(
                    reqId, null,
                    JSONObject().put("ok", true).put("files", arr).put("partial", sc.partial).put("scanned", sc.scanned)
                )
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not scan."))
            }
        }
    }

    @JavascriptInterface
    fun fsUndo(reqId: String, id: String, force: Boolean) {
        fsPool.execute { fsReply(reqId, fsOps.undo(id, force)) }
    }

    @JavascriptInterface
    fun fsPurge(reqId: String, id: String) {
        fsPool.execute { fsReply(reqId, fsOps.purge(id)) }
    }

    @JavascriptInterface
    fun fsPurgeAll(reqId: String) {
        fsPool.execute {
            val n = fsOps.purgeAllTrash()
            fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Deleted $n item(s) for good."))
        }
    }

    private val httpPool = Executors.newFixedThreadPool(2)

    /** Reads a page from huggingface.co (for searching models). The answer arrives in __onHttp. */
    @JavascriptInterface
    fun httpGet(reqId: String, url: String) {
        httpPool.execute {
            val o = JSONObject().put("id", reqId)
            try {
                val u = java.net.URL(url)
                if (u.protocol != "https" || u.host !in setOf("huggingface.co", "en.wikipedia.org", "html.duckduckgo.com")) throw Exception("Not allowed.")
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "MobileAIStudio/0.2")
                c.setRequestProperty("Accept", if (u.host == "html.duckduckgo.com") "text/html" else "application/json")
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
            val mm = mmprojFor(f.name)
            for (d in dl.list()) {
                if (d.kind == "model" && d.name == mm.name) dl.remove(d.id, true)
            }
            f.delete()
            File(f.path + ".part").delete()
            mm.delete()
            File(mm.path + ".part").delete()
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
        if (!Native.ensureLoaded(activity.applicationInfo.nativeLibraryDir)) {
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
            val mm = mmprojFor(file.name)
            if (mm.exists()) {
                js("__onStatus", "Loading the picture reader...")
                try {
                    Native.loadVision(handle, mm.absolutePath, threads)
                } catch (e: Throwable) {
                }
            }
        }
        val canSee = Native.hasVision(handle)

        val msgs = req.getJSONArray("messages")
        val roles = ArrayList<String>()
        val contents = ArrayList<ByteArray>()
        val imageIds = ArrayList<List<String>>()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val ids = ArrayList<String>()
            val ia = m.optJSONArray("images")
            if (ia != null) for (k in 0 until ia.length()) {
                val iid = ia.getString(k)
                if (iid.matches(Regex("[A-Za-z0-9_-]{1,64}")) && File(imagesDir, "$iid.jpg").exists()) ids.add(iid)
            }
            var text = m.getString("content")
            if (ids.isNotEmpty()) {
                if (!canSee) {
                    chatError("This model cannot look at pictures. Open Models, tap Find models online, and download a model marked with an eye (for example SmolVLM).")
                    return
                }
                text = "<__media__>\n".repeat(ids.size) + text
            }
            roles.add(m.getString("role"))
            contents.add(text.toByteArray(Charsets.UTF_8))
            imageIds.add(ids)
        }
        if (roles.isEmpty()) {
            chatError("Nothing to send.")
            return
        }

        // Keep the prompt inside the memory size: drop the oldest chat messages first.
        fun imgCount() = imageIds.sumOf { it.size }
        fun reserve() = imgCount() * 450
        var limit = nCtx - maxTok - 16 - reserve()
        var count = Native.countTokens(handle, roles.toTypedArray(), contents.toTypedArray())
        while (count > limit && roles.size > 2) {
            roles.removeAt(1)
            contents.removeAt(1)
            imageIds.removeAt(1)
            limit = nCtx - maxTok - 16 - reserve()
            count = Native.countTokens(handle, roles.toTypedArray(), contents.toTypedArray())
        }
        if (count < 0) {
            chatError("Could not read the message.")
            return
        }
        if (count > limit) {
            val room = nCtx - count - 16 - reserve()
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
        val n: Int
        if (imgCount() > 0) {
            val all = ArrayList<ByteArray>()
            for (ids in imageIds) for (iid in ids) all.add(File(imagesDir, "$iid.jpg").readBytes())
            n = Native.generateWithImages(handle, roles.toTypedArray(), contents.toTypedArray(), all.toTypedArray(), maxTok, temp, sink)
        } else {
            n = Native.generate(handle, roles.toTypedArray(), contents.toTypedArray(), maxTok, temp, sink)
        }
        sink.flush()

        when {
            n == -5 -> chatError("This model cannot look at pictures. Download a model marked with an eye in Find models online.")
            n == -6 -> chatError("The picture could not be read. Try another one.")
            n == -2 -> chatError("This is too long for the memory size. Raise Memory size in Settings or shorten the chat.")
            n < 0 -> chatError("The AI engine stopped unexpectedly. Try again, or pick a smaller model.")
            else -> {
                val o = JSONObject()
                o.put("tokens", n)
                o.put("ms", SystemClock.elapsedRealtime() - started)
                try {
                    val st = Native.lastStats(handle)
                    o.put("pTok", st[0]).put("pMs", st[1]).put("gTok", st[2]).put("gMs", st[3])
                } catch (e: Throwable) {
                }
                o.put("stopped", cancelChat)
                js("__onChatDone", o.toString())
            }
        }
    }

    // ---------- pictures ----------

    private val imagesDir: File by lazy { File(activity.filesDir, "images").also { it.mkdirs() } }

    private fun mmprojFor(modelFile: String) = File(modelsDir, modelFile.removeSuffix(".gguf") + ".mmproj.gguf")

    /** mode: "camera" or "gallery" */
    @JavascriptInterface
    fun pickImage(mode: String) {
        ui.post { activity.pickImage(mode) }
    }

    fun onImageCancelled() {
        js("__onImage", JSONObject().put("ok", false).put("cancelled", true).toString())
    }

    fun onImageFailed(msg: String) {
        js("__onImage", JSONObject().put("ok", false).put("msg", msg).toString())
    }

    /** Shrinks the picture (so the AI can read it quickly), fixes its rotation, saves it, and tells the page. */
    fun onImagePicked(uri: Uri, fromCamera: Boolean) {
        Thread {
            try {
                val maxSide = activity.getSharedPreferences("mai", Context.MODE_PRIVATE).getInt("imgSide", 512).coerceIn(256, 1024)
                fun open() = if (uri.scheme == "file") java.io.FileInputStream(File(uri.path!!)) else activity.contentResolver.openInputStream(uri)!!
                val bo = android.graphics.BitmapFactory.Options()
                bo.inJustDecodeBounds = true
                open().use { android.graphics.BitmapFactory.decodeStream(it, null, bo) }
                if (bo.outWidth <= 0 || bo.outHeight <= 0) throw IllegalStateException("bad")
                var sample = 1
                while (bo.outWidth / (sample * 2) >= maxSide && bo.outHeight / (sample * 2) >= maxSide) sample *= 2
                val o2 = android.graphics.BitmapFactory.Options()
                o2.inSampleSize = sample
                var bmp = open().use { android.graphics.BitmapFactory.decodeStream(it, null, o2) } ?: throw IllegalStateException("bad")

                var rot = 0
                try {
                    val exif = open().use { android.media.ExifInterface(it) }
                    rot = when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90
                        3 -> 180
                        8 -> 270
                        else -> 0
                    }
                } catch (e: Exception) {
                }
                val scale = Math.min(1f, maxSide.toFloat() / Math.max(bmp.width, bmp.height))
                if (rot != 0 || scale < 1f) {
                    val mx = android.graphics.Matrix()
                    if (rot != 0) mx.postRotate(rot.toFloat())
                    if (scale < 1f) mx.postScale(scale, scale)
                    val nb = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
                    if (nb !== bmp) bmp.recycle()
                    bmp = nb
                }
                val id = "i" + System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt()
                FileOutputStream(File(imagesDir, "$id.jpg")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }

                val ts = Math.min(1f, 160f / Math.max(bmp.width, bmp.height))
                val tb = android.graphics.Bitmap.createScaledBitmap(bmp, Math.max(1, (bmp.width * ts).toInt()), Math.max(1, (bmp.height * ts).toInt()), true)
                val bo2 = java.io.ByteArrayOutputStream()
                tb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bo2)
                val thumb = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo2.toByteArray(), android.util.Base64.NO_WRAP)
                if (fromCamera && uri.scheme == "file") File(uri.path!!).delete()
                cleanOldImages()
                js("__onImage", JSONObject().put("ok", true).put("id", id).put("w", bmp.width).put("h", bmp.height).put("thumb", thumb).toString())
            } catch (e: Throwable) {
                onImageFailed("That picture could not be opened.")
            }
        }.start()
    }

    /** Keeps the newest 60 pictures. */
    private fun cleanOldImages() {
        val fs = imagesDir.listFiles() ?: return
        if (fs.size <= 60) return
        fs.sortedBy { it.lastModified() }.take(fs.size - 60).forEach { it.delete() }
    }

    @JavascriptInterface
    fun getImageSide(): Int = activity.getSharedPreferences("mai", Context.MODE_PRIVATE).getInt("imgSide", 512)

    @JavascriptInterface
    fun setImageSide(v: Int) {
        activity.getSharedPreferences("mai", Context.MODE_PRIVATE).edit().putInt("imgSide", if (v >= 1024) 1024 else 512).apply()
    }

    /** Thumbnail data URL for a stored picture, so old chats can still show it. */
    @JavascriptInterface
    fun imageExists(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && File(imagesDir, "$id.jpg").exists()

    // ---------- documents (PDF, Word, Excel, zip, text ...) ----------

    private class Doc(val file: File, val name: String, var pw: String? = null)

    private val docs = HashMap<String, Doc>()
    private val docDir: File by lazy {
        File(activity.cacheDir, "docs").also {
            it.mkdirs()
            val old = System.currentTimeMillis() - 24L * 3600L * 1000L
            it.listFiles()?.forEach { d -> if (d.lastModified() < old) d.deleteRecursively() }
        }
    }

    @JavascriptInterface
    fun pickDocs() {
        ui.post { activity.pickDocs() }
    }

    fun onDocsPicked(uris: List<Uri>) {
        Thread {
            for (u in uris) handleDoc(u)
        }.start()
    }

    private fun docJson(id: String, name: String, status: String): JSONObject =
        JSONObject().put("id", id).put("name", name).put("status", status)

    private fun handleDoc(uri: Uri) {
        var name = "file"
        var size = -1L
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val z = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (i >= 0) name = c.getString(i) ?: name
                    if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
                }
            }
        } catch (e: Exception) {
        }
        val mime = try { activity.contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mime != null && mime.startsWith("image/")) {
            onImagePicked(uri, false)
            return
        }
        val id = "d" + System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt()
        try {
            if (size > 200L * 1048576L) {
                js("__onDoc", docJson(id, name, "error").put("note", "That file is too big (over 200 MB).").toString())
                return
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifEmpty { "file" }
            val dir = File(docDir, id).also { it.mkdirs() }
            val f = File(dir, safe)
            activity.contentResolver.openInputStream(uri)!!.use { ins -> FileOutputStream(f).use { ins.copyTo(it) } }
            docs[id] = Doc(f, name)
            processDoc(id, null)
        } catch (e: Throwable) {
            js("__onDoc", docJson(id, name, "error").put("note", "Could not open that file.").toString())
        }
    }

    private fun processDoc(id: String, pw: String?) {
        val d = docs[id] ?: return
        val r = DocReader.read(activity, d.file, d.name, pw)
        if (r.status == "ok" && pw != null) d.pw = pw
        val o = docJson(id, d.name, r.status).put("text", r.text).put("note", r.note).put("kind", r.kind)
        o.put("unlockable", pw != null && (r.kind == "pdf" || r.kind == "zip") && r.status == "ok")
        js("__onDoc", o.toString())
    }

    @JavascriptInterface
    fun docPassword(id: String, pw: String) {
        Thread { processDoc(id, pw) }.start()
    }

    @JavascriptInterface
    fun docDiscard(id: String) {
        docs.remove(id)?.file?.parentFile?.deleteRecursively()
    }

    /** Starts the torrent inside a picked .torrent file. */
    @JavascriptInterface
    fun docStartTorrent(id: String): String {
        val d = docs[id] ?: return JSONObject().put("ok", false).put("msg", "That file is gone. Pick it again.").toString()
        return try {
            ui.post { activity.askNotificationPermission() }
            val tid = TorrentCenter.addFile(d.file.readBytes())
            docDiscard(id)
            JSONObject().put("ok", true).put("id", tid).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("msg", e.message ?: "Could not start it.").toString()
        }
    }

    /** Saves an unlocked copy of a protected PDF or zip into the Download folder. Answer in __onFs. */
    @JavascriptInterface
    fun docUnlockSave(reqId: String, id: String) {
        fsPool.execute {
            val d = docs[id]
            if (d == null) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "That file is gone. Pick it again."))
                return@execute
            }
            if (!activity.hasFilesAccess()) {
                fsReply(reqId, null, fsNoAccess())
                return@execute
            }
            val root = fsOps.root()
            val base = d.name.substringBeforeLast('.', d.name).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifEmpty { "file" }
            val dir = File(root, "Download").also { it.mkdirs() }
            if (DocReader.extOf(d.name) == "pdf") {
                val tmp = File(d.file.parentFile, "unlocked.pdf")
                val err = DocReader.unlockPdf(activity, d.file, d.pw, tmp)
                if (err != null) {
                    fsReply(reqId, null, JSONObject().put("ok", false).put("msg", err))
                    return@execute
                }
                val r = fsOps.createBinary(File(dir, "$base (unlocked).pdf").path, tmp.readBytes(), "You chose to save an unlocked copy.")
                tmp.delete()
                fsReply(reqId, r)
            } else {
                var out = File(dir, "$base (unlocked)")
                var n = 2
                while (out.exists()) out = File(dir, "$base (unlocked $n)").also { n++ }
                val err = DocReader.unzipTo(d.file, d.pw, out)
                if (err != null) fsReply(reqId, null, JSONObject().put("ok", false).put("msg", err))
                else fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Unpacked into Download/" + out.name).put("path", out.path))
            }
        }
    }

    // ---------- making PDFs ----------

    /** Builds a PDF from text and saves it as a new file at path (already approved). Answer in __onFs. */
    @JavascriptInterface
    fun makePdf(reqId: String, title: String, text: String, path: String, reason: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) {
                fsReply(reqId, null, fsNoAccess())
                return@execute
            }
            try {
                val bytes = PdfMaker.make(title, text)
                fsReply(reqId, fsOps.createBinary(path, bytes, reason))
            } catch (e: Throwable) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not make the PDF."))
            }
        }
    }

    // ---------- "Share to this app" ----------

    private var pendingShare: String? = null

    /** Called with the intent that opened the app: shared text, links, files, magnet links and .torrent files. */
    fun onShare(intent: android.content.Intent?) {
        if (intent == null) return
        val act = intent.action
        if (act != android.content.Intent.ACTION_SEND && act != android.content.Intent.ACTION_SEND_MULTIPLE && act != android.content.Intent.ACTION_VIEW) return
        Thread {
            try {
                val uris = ArrayList<Uri>()
                var text = ""
                if (act == android.content.Intent.ACTION_VIEW) {
                    val d = intent.data
                    if (d != null) {
                        if (d.scheme == "magnet") text = d.toString() else uris.add(d)
                    }
                } else {
                    text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
                    @Suppress("DEPRECATION")
                    if (act == android.content.Intent.ACTION_SEND) {
                        (intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM))?.let { uris.add(it) }
                    } else {
                        intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
                    }
                }
                val files = JSONArray()
                for (u in uris.take(10)) {
                    val id = "d" + System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt()
                    var name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                    try {
                        activity.contentResolver.query(u, null, null, null, null)?.use { c ->
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                        }
                    } catch (e: Exception) {
                    }
                    val mime = try { activity.contentResolver.getType(u) } catch (e: Exception) { null } ?: ""
                    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifEmpty { "file" }
                    val dir = File(docDir, id).also { it.mkdirs() }
                    val f = File(dir, safe)
                    try {
                        (if (u.scheme == "file") java.io.FileInputStream(File(u.path!!)) else activity.contentResolver.openInputStream(u)!!).use { ins ->
                            FileOutputStream(f).use { ins.copyTo(it) }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                    docs[id] = Doc(f, name)
                    files.put(JSONObject().put("id", id).put("name", name).put("size", f.length()).put("image", mime.startsWith("image/")))
                }
                if (text.isBlank() && files.length() == 0) return@Thread
                pendingShare = JSONObject().put("text", text).put("files", files).toString()
                ui.post { js("__onShare", "") }
            } catch (e: Throwable) {
            }
        }.start()
    }

    @JavascriptInterface
    fun takeShare(): String {
        val s = pendingShare ?: ""
        pendingShare = null
        return s
    }

    /** Reads a staged file (from Share) into the project, same as picking it. */
    @JavascriptInterface
    fun docProcess(id: String) {
        Thread { processDoc(id, null) }.start()
    }

    /** Uses a shared picture as the attached picture. */
    @JavascriptInterface
    fun docAsImage(id: String) {
        val d = docs[id] ?: return
        onImagePicked(Uri.fromFile(d.file), false)
    }

    /** Saves a copy of a shared file into the Download folder. Answer in __onFs. */
    @JavascriptInterface
    fun docSaveCopy(reqId: String, id: String) {
        fsPool.execute {
            val d = docs[id]
            when {
                d == null -> fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "That file is gone. Share it again."))
                !activity.hasFilesAccess() -> fsReply(reqId, null, fsNoAccess())
                d.file.length() > 50L * 1048576L -> fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "That file is too big to copy this way (limit 50 MB)."))
                else -> {
                    val name = d.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifEmpty { "file" }
                    fsReply(reqId, fsOps.createBinary(File(File(fsOps.root(), "Download"), name).path, d.file.readBytes(), "You chose Save a copy from the Share menu."))
                }
            }
        }
    }

    /** How many of the phone's cores are the fast ones (phones mix fast and slow cores; slow ones drag the AI down). */
    private fun bigCores(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = ArrayList<Long>()
        for (i in 0 until n) {
            try {
                freqs.add(File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong())
            } catch (e: Exception) {
            }
        }
        if (freqs.size < n || freqs.isEmpty()) return Math.max(2, n / 2)
        val top = freqs.max()
        val big = freqs.count { it >= top * 0.75 }
        return big.coerceIn(2, 6)
    }

    // ---------- speaking and listening ----------

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var ttsPending: (() -> Unit)? = null

    private fun withTts(then: () -> Unit) {
        if (ttsReady && tts != null) { then(); return }
        ttsPending = then
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(activity) { status ->
            ui.post {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { if (id != null && id.endsWith("_last")) ui.post { js("__onSpeakDone", "") } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { ui.post { js("__onSpeakDone", "") } }
                    })
                    ttsPending?.invoke()
                } else {
                    tts = null
                    js("__onSpeakDone", "")
                    js("__onVoiceError", "This phone has no text-to-speech voice. Install one in Settings, then Text-to-speech.")
                }
                ttsPending = null
            }
        }
    }

    /** Reads text aloud. rate: 0.6 slow ... 1.0 normal ... 1.5 fast. Ends with __onSpeakDone. */
    @JavascriptInterface
    fun speak(text: String, rate: Float) {
        ui.post {
            withTts {
                val t = tts ?: return@withTts
                t.stop()
                t.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                try {
                    val loc = java.util.Locale.getDefault()
                    if (t.isLanguageAvailable(loc) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) t.language = loc
                } catch (e: Throwable) {
                }
                val max = Math.min(3000, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 100)
                val parts = ArrayList<String>()
                var rest = text.trim()
                while (rest.length > max) {
                    var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max)
                    if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
                    if (cut < 1) cut = max
                    parts.add(rest.substring(0, cut + 1))
                    rest = rest.substring(cut + 1).trim()
                }
                if (rest.isNotEmpty()) parts.add(rest)
                if (parts.isEmpty()) { js("__onSpeakDone", ""); return@withTts }
                parts.forEachIndexed { i, p ->
                    val id = "u" + i + (if (i == parts.size - 1) "_last" else "")
                    t.speak(p, android.speech.tts.TextToSpeech.QUEUE_ADD, null, id)
                }
            }
        }
    }

    @JavascriptInterface
    fun stopSpeak() {
        ui.post { try { tts?.stop() } catch (e: Throwable) {} }
    }

    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listenAfterPermission = false

    fun onMicResult(granted: Boolean) {
        if (granted && listenAfterPermission) startListening()
        else if (!granted) js("__onSpeech", JSONObject().put("state", "error").put("msg", "Microphone access was denied. Allow it in the phone's Settings for this app.").toString())
        listenAfterPermission = false
    }

    @JavascriptInterface
    fun listen() {
        ui.post {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(activity)) {
                js("__onSpeech", JSONObject().put("state", "error").put("msg", "This phone has no speech recognizer. Install or enable Speech Services by Google.").toString())
            } else if (!activity.hasMic()) {
                listenAfterPermission = true
                activity.askMic()
            } else startListening()
        }
    }

    private fun startListening() {
        try { tts?.stop() } catch (e: Throwable) {}
        try { recognizer?.destroy() } catch (e: Throwable) {}
        val r = android.speech.SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) { js("__onSpeech", JSONObject().put("state", "listening").toString()) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { js("__onSpeech", JSONObject().put("state", "thinking").toString()) }
            override fun onError(code: Int) {
                val msg = when (code) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT, android.speech.SpeechRecognizer.ERROR_SERVER ->
                        "Speech recognition needs internet on this phone, or an offline language pack (Google app, Settings, Voice, Offline speech recognition)."
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off for this app."
                    android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again."
                    else -> "Speech recognition failed (code $code)."
                }
                js("__onSpeech", JSONObject().put("state", "error").put("msg", msg).toString())
            }
            override fun onResults(b: android.os.Bundle?) {
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                js("__onSpeech", JSONObject().put("state", "done").put("text", t).toString())
            }
            override fun onPartialResults(b: android.os.Bundle?) {
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                js("__onSpeech", JSONObject().put("state", "partial").put("text", t).toString())
            }
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
        })
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    @JavascriptInterface
    fun stopListening() {
        ui.post { try { recognizer?.stopListening() } catch (e: Throwable) {} }
    }

    @JavascriptInterface
    fun cancelListening() {
        ui.post { try { recognizer?.cancel() } catch (e: Throwable) {} }
    }
}
