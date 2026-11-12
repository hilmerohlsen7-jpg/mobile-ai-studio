package com.mobileaistudio.app

import android.app.ActivityManager
import android.content.Intent
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
    private var handle: Long
        get() = Shared.handle
        set(v) { Shared.handle = v }
    private var loadedKey: String
        get() = Shared.loadedKey
        set(v) { Shared.loadedKey = v }
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
        WakeCenter.onWake = null
        cancelChat = true
        try { tts?.shutdown() } catch (e: Throwable) {}
        try { recognizer?.destroy() } catch (e: Throwable) {}
        DownloadCenter.onChange = null
        worker.execute {
            synchronized(Shared.lock) {
                if (handle != 0L) {
                    Native.freeModel(handle)
                    handle = 0L
                    loadedKey = ""
                }
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

    // ---- extra file manager features: share, open with, info, zip, unzip, find ----
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, if (ext.isEmpty() || base == name) "$name ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    @JavascriptInterface
    fun fsShare(pathsJson: String): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        return try {
            val arr = JSONArray(pathsJson)
            val files = (0 until arr.length()).map { File(arr.getString(it)) }.filter { it.isFile }
            if (files.isEmpty()) return JSONObject().put("ok", false).put("msg", "Only files can be shared, not folders. Make a ZIP first.").toString()
            val uris = ArrayList(files.map { ShareProvider.uriFor(it) })
            val i = android.content.Intent(if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE)
            i.type = if (files.size == 1) ShareProvider.mimeOf(files[0].name) else "*/*"
            if (uris.size == 1) i.putExtra(android.content.Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ch = android.content.Intent.createChooser(i, "Share")
            ch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ui.post { try { activity.startActivity(ch) } catch (e: Exception) {} }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("msg", e.message ?: "Could not share.").toString() }
    }

    @JavascriptInterface
    fun fsOpenWith(path: String): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        return try {
            val f = File(path)
            if (!f.isFile) return JSONObject().put("ok", false).put("msg", "That is not a file.").toString()
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(ShareProvider.uriFor(f), ShareProvider.mimeOf(f.name))
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val ch = android.content.Intent.createChooser(i, "Open with")
            ch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ui.post { try { activity.startActivity(ch) } catch (e: Exception) {} }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("msg", e.message ?: "Could not open it.").toString() }
    }

    @JavascriptInterface
    fun fsInfo(path: String): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        return try {
            val f = File(path)
            if (!f.exists()) return JSONObject().put("ok", false).put("msg", "That does not exist.").toString()
            var files = 0L; var dirs = 0L; var bytes = 0L; var partial = false
            if (f.isDirectory) {
                var seen = 0
                val stack = ArrayDeque<File>(); stack.add(f)
                while (stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    for (c in d.listFiles() ?: emptyArray()) {
                        if (++seen > 50000) { partial = true; break }
                        if (c.isDirectory) { dirs++; stack.add(c) } else { files++; bytes += c.length() }
                    }
                    if (partial) break
                }
            } else bytes = f.length()
            JSONObject().put("ok", true).put("name", f.name).put("path", f.absolutePath).put("dir", f.isDirectory)
                .put("size", bytes).put("files", files).put("dirs", dirs).put("partial", partial).put("mtime", f.lastModified())
                .put("mime", if (f.isFile) ShareProvider.mimeOf(f.name) else "folder")
                .put("canRead", f.canRead()).put("canWrite", f.canWrite()).put("hidden", f.name.startsWith(".")).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("msg", e.message ?: "Could not read it.").toString() }
    }

    /** Makes a new ZIP of the chosen files and folders next to them. The answer arrives in __onFs. */
    @JavascriptInterface
    fun fsZip(reqId: String, pathsJson: String, destDir: String, zipName: String, password: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val arr = JSONArray(pathsJson)
                val items = (0 until arr.length()).map { File(arr.getString(it)) }.filter { it.exists() }
                val dir = File(destDir)
                val out = uniqueFile(dir, if (zipName.endsWith(".zip", true)) zipName else "$zipName.zip")
                val zf = if (password.isNotEmpty()) net.lingala.zip4j.ZipFile(out, password.toCharArray()) else net.lingala.zip4j.ZipFile(out)
                val prm = net.lingala.zip4j.model.ZipParameters()
                if (password.isNotEmpty()) { prm.isEncryptFiles = true; prm.encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES }
                for (f in items) { if (f.isDirectory) zf.addFolder(f, prm) else zf.addFile(f, prm) }
                fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Made " + out.name).put("path", out.absolutePath))
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not make the ZIP."))
            }
        }
    }

    /** Unpacks a ZIP into a new folder beside it. A wrong password is reported as code "password". */
    @JavascriptInterface
    fun fsUnzip(reqId: String, zipPath: String, password: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val src = File(zipPath)
                val zf = net.lingala.zip4j.ZipFile(src)
                if (zf.isEncrypted) {
                    if (password.isEmpty()) { fsReply(reqId, null, JSONObject().put("ok", false).put("code", "password").put("msg", "This ZIP needs a password.")); return@execute }
                    zf.setPassword(password.toCharArray())
                }
                val dest = uniqueFile(src.parentFile ?: File("/"), src.name.substringBeforeLast('.'))
                dest.mkdirs()
                zf.extractAll(dest.absolutePath)
                fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Unpacked into " + dest.name).put("path", dest.absolutePath))
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                val wrong = (e.message ?: "").contains("password", true)
                fsReply(reqId, null, JSONObject().put("ok", false).put("code", if (wrong) "password" else "").put("msg", if (wrong) "Wrong password." else (e.message ?: "Could not unpack it.")))
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not unpack it."))
            }
        }
    }

    /** Looks for names containing the text anywhere under a folder. The answer arrives in __onFs with "files". */
    @JavascriptInterface
    fun fsFind(reqId: String, path: String, query: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val q = query.lowercase(); val arr = JSONArray(); var seen = 0
                val stack = ArrayDeque<File>(); stack.add(File(path))
                while (stack.isNotEmpty() && arr.length() < 200 && seen < 200000) {
                    val d = stack.removeLast()
                    for (c in d.listFiles() ?: emptyArray()) {
                        seen++
                        if (c.name.lowercase().contains(q)) arr.put(JSONObject().put("path", c.absolutePath).put("dir", c.isDirectory).put("size", c.length()).put("mtime", c.lastModified()))
                        if (c.isDirectory && !c.name.startsWith(".")) stack.add(c)
                    }
                }
                fsReply(reqId, null, JSONObject().put("ok", true).put("files", arr).put("partial", arr.length() >= 200 || seen >= 200000))
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not search."))
            }
        }
    }

    /** Sizes of a folder tree for the disk map (3 levels deep). The answer arrives in __onFs with "node", "total", "free". */
    @JavascriptInterface
    fun fsDiskMap(reqId: String, path: String) {
        Thread {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@Thread }
            try {
                val deadline = SystemClock.elapsedRealtime() + 45000
                var visited = 0L; var partial = false
                fun scan(f: File, depth: Int): JSONObject {
                    var size = 0L
                    val kids = ArrayList<JSONObject>()
                    if (f.isFile) size = f.length()
                    else for (c in f.listFiles() ?: emptyArray()) {
                        if (++visited > 1500000 || SystemClock.elapsedRealtime() > deadline) { partial = true; break }
                        val n = if (c.isDirectory && !java.nio.file.Files.isSymbolicLink(c.toPath())) scan(c, depth + 1)
                            else JSONObject().put("name", c.name).put("size", if (c.isFile) c.length() else 0L).put("dir", false)
                        size += n.getLong("size")
                        if (depth < 3) kids.add(n)
                    }
                    val o = JSONObject().put("name", f.name.ifEmpty { f.path }).put("size", size).put("dir", f.isDirectory)
                    if (depth < 3 && kids.isNotEmpty()) {
                        kids.sortByDescending { it.getLong("size") }
                        val arr = JSONArray(); var rest = 0L
                        for ((i, k) in kids.withIndex()) { if (i < 14 && k.getLong("size") > 0) arr.put(k) else rest += k.getLong("size") }
                        if (rest > 0) arr.put(JSONObject().put("name", "\u2026").put("size", rest).put("dir", false).put("other", true))
                        o.put("kids", arr)
                    }
                    return o
                }
                val root = File(path)
                val node = scan(root, 0)
                val st = android.os.StatFs(root.path)
                fsReply(reqId, null, JSONObject().put("ok", true).put("node", node).put("total", st.totalBytes).put("free", st.availableBytes).put("partial", partial))
            } catch (e: Exception) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not measure."))
            }
        }.start()
    }

    // ---- Wi-Fi file server ----
    @JavascriptInterface
    fun wifiStatus(): String {
        val o = JSONObject().put("running", WifiServer.running).put("url", WifiServer.url).put("pin", WifiServer.pin)
        val arr = JSONArray(); synchronized(WifiServer.log) { WifiServer.log.takeLast(8).forEach { arr.put(it) } }
        return o.put("log", arr).put("ip", WifiServer.localIp() ?: "").toString()
    }

    /** Only called after the person approved it in the app: starts sharing the given folder. */
    @JavascriptInterface
    fun wifiStart(path: String, upload: Boolean): String {
        if (!activity.hasFilesAccess()) return fsNoAccess().toString()
        val err = WifiServer.start(File(path), upload)
        if (err == null) ui.post { try { androidx_start(Intent(activity, WifiService::class.java)) } catch (e: Exception) {} }
        return if (err != null) JSONObject().put("ok", false).put("msg", err).toString() else wifiStatus()
    }

    @JavascriptInterface
    fun wifiStop(): String {
        WifiServer.stop()
        ui.post { try { activity.stopService(Intent(activity, WifiService::class.java)) } catch (e: Exception) {} }
        return wifiStatus()
    }

    private fun androidx_start(i: Intent) { if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i) }

    // ---- remote servers (FTP / SFTP) ----
    /** op: list | download | upload | mkdir | rename | delete. The answer arrives in __onFs. Callers have already asked the person. */
    @JavascriptInterface
    fun rfsOp(reqId: String, connJson: String, opJson: String) {
        Thread {
            try {
                val c = RemoteFs.Conn.of(JSONObject(connJson)); val o = JSONObject(opJson)
                when (o.getString("type")) {
                    "list" -> fsReply(reqId, null, JSONObject().put("ok", true).put("items", RemoteFs.list(c, o.getString("path"))))
                    "download" -> {
                        if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@Thread }
                        val n = RemoteFs.download(c, o.getString("path"), File(o.getString("destDir")))
                        fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Copied $n file(s) to this phone."))
                    }
                    "upload" -> {
                        if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@Thread }
                        val n = RemoteFs.upload(c, File(o.getString("path")), o.getString("destDir"))
                        fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Sent $n file(s) to the server."))
                    }
                    "mkdir" -> { RemoteFs.mkdir(c, o.getString("path")); fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Folder created.")) }
                    "rename" -> { RemoteFs.rename(c, o.getString("path"), o.getString("to")); fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Renamed.")) }
                    "delete" -> { RemoteFs.delete(c, o.getString("path")); fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Deleted from the server.")) }
                    else -> fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "Unknown action."))
                }
            } catch (e: RemoteFs.NeedHostKey) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("code", "hostkey").put("fp", e.fp).put("msg", "New server. Check its key."))
            } catch (e: Throwable) {
                fsReply(reqId, null, JSONObject().put("ok", false).put("msg", (e.message ?: "Could not reach the server.")))
            }
        }.start()
    }

    // ---- home screen extras: recent files and app manager ----
    /** Newest files under a folder (last [days] days, at most 150). The answer arrives in __onFs with "files". */
    @JavascriptInterface
    fun fsRecent(reqId: String, path: String, days: Int) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val since = System.currentTimeMillis() - days * 86400000L
                val found = ArrayList<File>(); var seen = 0
                val stack = ArrayDeque<File>(); stack.add(File(path))
                while (stack.isNotEmpty() && seen < 250000) {
                    val d = stack.removeLast()
                    for (c in d.listFiles() ?: emptyArray()) {
                        seen++
                        if (c.isDirectory) { if (!c.name.startsWith(".") && !(d.name == "Android" && (c.name == "data" || c.name == "obb"))) stack.add(c) }
                        else if (c.lastModified() >= since) found.add(c)
                    }
                }
                found.sortByDescending { it.lastModified() }
                val arr = JSONArray()
                for (f in found.take(150)) arr.put(JSONObject().put("path", f.absolutePath).put("dir", false).put("size", f.length()).put("mtime", f.lastModified()))
                fsReply(reqId, null, JSONObject().put("ok", true).put("files", arr).put("partial", found.size > 150))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not look.")) }
        }
    }

    @JavascriptInterface
    fun appList(): String {
        val pm = activity.packageManager; val arr = JSONArray()
        try {
            for (a in pm.getInstalledApplications(0)) {
                val system = (a.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                if (system && pm.getLaunchIntentForPackage(a.packageName) == null) continue
                val vn = try { pm.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
                arr.put(JSONObject().put("pkg", a.packageName).put("name", pm.getApplicationLabel(a).toString()).put("size", File(a.sourceDir).length())
                    .put("version", vn).put("system", system).put("splits", (a.splitSourceDirs?.size ?: 0)))
            }
        } catch (e: Exception) {}
        return arr.toString()
    }

    /** action: info | uninstall | launch. Uninstall only opens Android's own confirmation. */
    /** App icons as small PNGs (base64), for a list of package names. */
    @JavascriptInterface
    fun appIcons(reqId: String, pkgsJson: String) {
        fsPool.execute {
            val out = JSONObject()
            try {
                val pm = activity.packageManager; val arr = JSONArray(pkgsJson)
                for (i in 0 until arr.length()) {
                    val pkg = arr.getString(i)
                    try {
                        val d = pm.getApplicationIcon(pkg); val sz = 96
                        val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, sz, sz); d.draw(c)
                        val bo = java.io.ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bo)
                        out.put(pkg, android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP))
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
            fsReply(reqId, null, JSONObject().put("ok", true).put("icons", out))
        }
    }

    // ---- Vault: AES-256 encrypted ZIPs kept in the app's private folder. The password is never stored. ----
    private fun vaultDir(): File { val d = File(activity.filesDir, "vaults"); d.mkdirs(); return d }
    private fun vaultFile(name: String) = File(vaultDir(), name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + ".zip")
    private fun vaultOpen(name: String, pw: String): net.lingala.zip4j.ZipFile? {
        val f = vaultFile(name); if (!f.exists()) return null
        val zf = net.lingala.zip4j.ZipFile(f, pw.toCharArray())
        val h = zf.getFileHeader(".vault") ?: return null
        zf.getInputStream(h).use { it.read() } // throws on a wrong password
        return zf
    }
    @JavascriptInterface
    fun vaultOp(reqId: String, opJson: String) {
        fsPool.execute {
            val r = JSONObject()
            try {
                val op = JSONObject(opJson); val t = op.getString("type")
                if (t == "list") {
                    val arr = JSONArray(); vaultDir().listFiles()?.filter { it.name.endsWith(".zip") }?.sortedBy { it.name }?.forEach { arr.put(JSONObject().put("name", it.name.removeSuffix(".zip")).put("size", it.length())) }
                    r.put("ok", true).put("vaults", arr)
                } else if (t == "create") {
                    val name = op.getString("name"); val pw = op.getString("password")
                    if (pw.length < 4) throw Exception("Use a password of at least 4 characters.")
                    val f = vaultFile(name); if (f.exists()) throw Exception("A vault with that name already exists.")
                    val zf = net.lingala.zip4j.ZipFile(f, pw.toCharArray())
                    val prm = net.lingala.zip4j.model.ZipParameters(); prm.isEncryptFiles = true
                    prm.encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES; prm.fileNameInZip = ".vault"
                    zf.addStream("ok".byteInputStream(), prm)
                    r.put("ok", true).put("msg", "Vault created.")
                } else {
                    val name = op.getString("name")
                    if (t == "delete") { val f = vaultFile(name); r.put("ok", f.delete()).put("msg", "Vault deleted. Its contents are gone for good.") }
                    else {
                        val zf = try { vaultOpen(name, op.getString("password")) } catch (e: net.lingala.zip4j.exception.ZipException) { null }
                        if (zf == null) { r.put("ok", false).put("code", "password").put("msg", "Wrong password.") }
                        else when (t) {
                            "contents" -> {
                                val arr = JSONArray()
                                for (h in zf.fileHeaders) if (!h.isDirectory && h.fileName != ".vault") arr.put(JSONObject().put("name", h.fileName).put("size", h.uncompressedSize))
                                r.put("ok", true).put("entries", arr)
                            }
                            "add" -> {
                                if (!activity.hasFilesAccess()) throw Exception("Files access is off.")
                                val prm = net.lingala.zip4j.model.ZipParameters(); prm.isEncryptFiles = true; prm.encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                                val ps = op.getJSONArray("paths"); var n = 0
                                for (i in 0 until ps.length()) { val f = File(ps.getString(i)); if (!f.exists()) continue; if (f.isDirectory) zf.addFolder(f, prm) else zf.addFile(f, prm); n++ }
                                r.put("ok", true).put("msg", "Locked $n item(s) in the vault.")
                            }
                            "extract" -> {
                                if (!activity.hasFilesAccess()) throw Exception("Files access is off.")
                                val entry = op.getString("entry"); val dest = File(op.getString("destDir")); dest.mkdirs()
                                val h = zf.getFileHeader(entry) ?: throw Exception("Not found in the vault.")
                                val tmp = File(activity.cacheDir, "vx" + System.nanoTime()); tmp.mkdirs()
                                try { zf.extractFile(h, tmp.absolutePath); val src = File(tmp, entry); val out = uniqueFile(dest, File(entry).name); src.copyTo(out); r.put("ok", true).put("msg", "Saved " + out.name + " in " + dest.name) }
                                finally { tmp.deleteRecursively() }
                            }
                            "remove" -> { zf.removeFile(op.getString("entry")); r.put("ok", true).put("msg", "Removed from the vault.") }
                            else -> r.put("ok", false).put("msg", "Unknown action.")
                        }
                    }
                }
            } catch (e: Exception) { r.put("ok", false).put("msg", e.message ?: "Vault problem.") }
            fsReply(reqId, null, r)
        }
    }

    // ---- File sync: compare two folders, copy what is missing. Never deletes or overwrites. ----
    private fun walkRel(root: File, cap: Int): Map<String, File> {
        val out = LinkedHashMap<String, File>(); val stack = ArrayDeque<File>(); stack.add(root)
        while (stack.isNotEmpty() && out.size < cap) {
            val d = stack.removeLast()
            for (c in d.listFiles() ?: emptyArray()) { if (c.isDirectory) stack.add(c) else out[c.relativeTo(root).path] = c }
        }
        return out
    }
    @JavascriptInterface
    fun fsSyncPlan(reqId: String, src: String, dst: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val a = walkRel(File(src), 20000); val b = walkRel(File(dst), 20000)
                val missing = JSONArray(); val changed = JSONArray(); var same = 0
                for ((rel, f) in a) { val g = b[rel]
                    if (g == null) missing.put(JSONObject().put("rel", rel).put("size", f.length()))
                    else if (g.length() != f.length()) changed.put(JSONObject().put("rel", rel).put("size", f.length()).put("dstSize", g.length()))
                    else same++ }
                fsReply(reqId, null, JSONObject().put("ok", true).put("missing", missing).put("changed", changed).put("same", same).put("partial", a.size >= 20000 || b.size >= 20000))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not compare.")) }
        }
    }
    /** relsJson: files to copy. asCopy=true writes changed files under a new name instead of replacing. */
    @JavascriptInterface
    fun fsSyncRun(reqId: String, src: String, dst: String, relsJson: String, asCopy: Boolean) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            var done = 0; var failed = 0
            try {
                val arr = JSONArray(relsJson)
                for (i in 0 until arr.length()) {
                    try {
                        val rel = arr.getString(i); if (rel.contains("..")) { failed++; continue }
                        val from = File(src, rel); var to = File(dst, rel); to.parentFile?.mkdirs()
                        if (to.exists()) { if (!asCopy) { failed++; continue }; to = uniqueFile(to.parentFile!!, to.name) }
                        from.inputStream().use { x -> to.outputStream().use { x.copyTo(it, 65536) } }
                        to.setLastModified(from.lastModified()); done++
                    } catch (e: Exception) { failed++ }
                }
                fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Copied $done file(s)" + if (failed > 0) ", $failed could not be copied." else "."))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Sync failed.")) }
        }
    }

    // ---- DLNA / UPnP media servers: find them on the Wi-Fi, browse, download a copy ----
    private fun xmlDoc(text: String): org.w3c.dom.Document {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance(); f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) {}
        return f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(text)))
    }
    private fun httpText(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000; c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
    @JavascriptInterface
    fun dlnaScan(reqId: String) {
        fsPool.execute {
            val found = LinkedHashMap<String, String>() // location -> usn
            var lock: android.net.wifi.WifiManager.MulticastLock? = null
            try {
                val wm = activity.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                lock = wm.createMulticastLock("dlna"); lock.setReferenceCounted(false); lock.acquire()
                val sock = java.net.DatagramSocket(); sock.soTimeout = 1200
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
                val grp = java.net.InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(java.net.DatagramPacket(msg, msg.size, grp, 1900)) }
                val end = System.currentTimeMillis() + 3500; val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val pk = java.net.DatagramPacket(buf, buf.size); sock.receive(pk)
                        val t = String(pk.data, 0, pk.length)
                        val loc = Regex("(?im)^location:\\s*(\\S+)").find(t)?.groupValues?.get(1) ?: continue
                        found[loc] = loc
                    } catch (e: java.net.SocketTimeoutException) {}
                }
                sock.close()
            } catch (e: Exception) {} finally { try { lock?.release() } catch (e: Exception) {} }
            val arr = JSONArray()
            for (loc in found.keys) {
                try {
                    val d = xmlDoc(httpText(loc)); val name = d.getElementsByTagName("friendlyName").item(0)?.textContent ?: loc
                    val svcs = d.getElementsByTagName("service"); var ctl: String? = null
                    for (i in 0 until svcs.length) { val sv = svcs.item(i) as org.w3c.dom.Element
                        if ((sv.getElementsByTagName("serviceType").item(0)?.textContent ?: "").contains("ContentDirectory")) ctl = sv.getElementsByTagName("controlURL").item(0)?.textContent }
                    if (ctl != null) arr.put(JSONObject().put("name", name).put("control", java.net.URL(java.net.URL(loc), ctl).toString()))
                } catch (e: Exception) {}
            }
            fsReply(reqId, null, JSONObject().put("ok", true).put("devices", arr))
        }
    }
    @JavascriptInterface
    fun dlnaBrowse(reqId: String, control: String, objectId: String) {
        fsPool.execute {
            try {
                val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>" +
                    objectId.replace("&", "&amp;").replace("<", "&lt;") + "</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>300</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"
                val resp = httpText(control, "POST", body, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""))
                val result = xmlDoc(resp).getElementsByTagName("Result").item(0)?.textContent ?: ""
                val didl = xmlDoc(result); val arr = JSONArray()
                fun title(e: org.w3c.dom.Element) = e.getElementsByTagName("dc:title").item(0)?.textContent ?: "?"
                val cs = didl.getElementsByTagName("container")
                for (i in 0 until cs.length) { val e = cs.item(i) as org.w3c.dom.Element; arr.put(JSONObject().put("id", e.getAttribute("id")).put("title", title(e)).put("container", true)) }
                val its = didl.getElementsByTagName("item")
                for (i in 0 until its.length) { val e = its.item(i) as org.w3c.dom.Element
                    val res = e.getElementsByTagName("res").item(0) as? org.w3c.dom.Element ?: continue
                    arr.put(JSONObject().put("id", e.getAttribute("id")).put("title", title(e)).put("container", false).put("url", res.textContent).put("size", res.getAttribute("size").toLongOrNull() ?: 0L)) }
                fsReply(reqId, null, JSONObject().put("ok", true).put("items", arr))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", "Could not read this server: " + (e.message ?: ""))) }
        }
    }
    @JavascriptInterface
    fun dlnaDownload(reqId: String, url: String, title: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection; c.connectTimeout = 5000; c.readTimeout = 20000
                val ext = when { (c.contentType ?: "").contains("video") -> ".mp4"; (c.contentType ?: "").contains("audio/mpeg") -> ".mp3"; (c.contentType ?: "").contains("image/jpeg") -> ".jpg"; else -> "" }
                var nm = title.replace(Regex("[^A-Za-z0-9._ -]"), "_"); if (!nm.contains('.') ) nm += ext
                val out = uniqueFile(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), nm)
                c.inputStream.use { i -> out.outputStream().use { i.copyTo(it, 65536) } }
                fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Saved " + out.name + " in Download"))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Download failed.")) }
        }
    }

    @JavascriptInterface
    fun appAction(pkg: String, action: String): String {
        return try {
            val i = when (action) {
                "info" -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                "uninstall" -> android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
                else -> activity.packageManager.getLaunchIntentForPackage(pkg) ?: return JSONObject().put("ok", false).put("msg", "This app has no screen to open.").toString()
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ui.post { try { activity.startActivity(i) } catch (e: Exception) {} }
            JSONObject().put("ok", true).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("msg", e.message ?: "Could not.").toString() }
    }

    /** Copies an app's APK into the Download folder as a new file (never replaces). */
    @JavascriptInterface
    fun appBackup(reqId: String, pkg: String) {
        fsPool.execute {
            if (!activity.hasFilesAccess()) { fsReply(reqId, null, fsNoAccess()); return@execute }
            try {
                val a = activity.packageManager.getApplicationInfo(pkg, 0)
                val label = activity.packageManager.getApplicationLabel(a).toString().replace(Regex("[^A-Za-z0-9._ -]"), "_")
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "")
                val out = uniqueFile(dir, "$label.apk")
                File(a.sourceDir).inputStream().use { i -> out.outputStream().use { i.copyTo(it, 65536) } }
                val extra = if ((a.splitSourceDirs?.size ?: 0) > 0) " (this app has split parts; only the base part was saved)" else ""
                fsReply(reqId, null, JSONObject().put("ok", true).put("msg", "Saved " + out.name + " in Download" + extra))
            } catch (e: Exception) { fsReply(reqId, null, JSONObject().put("ok", false).put("msg", e.message ?: "Could not save it.")) }
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
                if (u.protocol != "https" || u.host !in setOf("huggingface.co", "en.wikipedia.org", "html.duckduckgo.com", "api.open-meteo.com", "geocoding-api.open-meteo.com")) throw Exception("Not allowed.")
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

    /** Reads one web page the person pasted into the chat (any https site, never the phone's own network). The answer arrives in __onHttp. */
    @JavascriptInterface
    fun pageGet(reqId: String, url: String) {
        httpPool.execute {
            val o = JSONObject().put("id", reqId)
            try {
                var cur = java.net.URL(url)
                var hops = 0
                while (true) {
                    if (cur.protocol != "https") throw Exception("Only https links can be read.")
                    for (a in java.net.InetAddress.getAllByName(cur.host)) {
                        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress) throw Exception("That address is not allowed.")
                    }
                    val c = cur.openConnection() as java.net.HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MobileAIStudio/0.2")
                    c.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.1")
                    val code = c.responseCode
                    if (code in 300..399 && hops < 4) {
                        val loc = c.getHeaderField("Location") ?: throw Exception("The page redirected nowhere.")
                        cur = java.net.URL(cur, loc)
                        c.disconnect()
                        hops++
                        continue
                    }
                    val type = (c.contentType ?: "").lowercase()
                    if (code in 200..299 && !(type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.isEmpty())) throw Exception("That link is not a web page ($type).")
                    val charset = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val bytes = java.io.ByteArrayOutputStream()
                    stream?.use { ins ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            bytes.write(buf, 0, n)
                            if (bytes.size() > 1_500_000) break
                        }
                    }
                    val body = try { bytes.toString(charset) } catch (e: Exception) { bytes.toString("UTF-8") }
                    o.put("ok", code in 200..299).put("status", code).put("body", body).put("url", cur.toString())
                    c.disconnect()
                    break
                }
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
                synchronized(Shared.lock) { runChat(JSONObject(requestJson)) }
            } catch (t: Throwable) {
                chatError("Something went wrong: ${t.message}")
            }
        }
    }

    @Volatile private var cloudConn: java.net.HttpURLConnection? = null

    /** Sends the chat to an online AI (Gemini, Claude or Grok) with the person's own key. The whole answer arrives at once. */
    @JavascriptInterface
    fun cloudChat(requestJson: String) {
        cancelChat = false
        worker.execute {
            val t0 = System.currentTimeMillis()
            try {
                val req = JSONObject(requestJson)
                val provider = req.getString("provider")
                val key = req.getString("key").trim()
                val model = req.getString("model").trim()
                if (key.isEmpty()) { chatError("There is no key for this service. Open Settings, Online AI, and paste it."); return@execute }
                if (!model.matches(Regex("[A-Za-z0-9._:/-]{1,80}"))) { chatError("That model name does not look right. Check it in Settings, Online AI."); return@execute }
                val maxTok = req.optInt("max_tokens", 2048).coerceIn(64, 8192)
                val temp = req.optDouble("temperature", 0.7)

                var system = ""
                val roles = ArrayList<String>()
                val texts = ArrayList<String>()
                val imgs = ArrayList<ArrayList<String>>()
                val msgs = req.getJSONArray("messages")
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val role = m.getString("role")
                    val content = m.getString("content")
                    val ids = ArrayList<String>()
                    val ia = m.optJSONArray("images")
                    if (ia != null) for (k in 0 until ia.length()) {
                        val iid = ia.getString(k)
                        if (iid.matches(Regex("[A-Za-z0-9_-]{1,64}")) && File(imagesDir, "$iid.jpg").exists()) ids.add(iid)
                    }
                    if (role == "system") { system = content; continue }
                    if (roles.isNotEmpty() && roles.last() == role) {
                        texts[texts.size - 1] = texts.last() + "\n\n" + content
                        imgs.last().addAll(ids)
                    } else {
                        roles.add(role); texts.add(content); imgs.add(ids)
                    }
                }
                if (roles.isEmpty() || roles[0] != "user") { chatError("Nothing to send."); return@execute }
                fun b64(id: String) = android.util.Base64.encodeToString(File(imagesDir, "$id.jpg").readBytes(), android.util.Base64.NO_WRAP)

                val url: String
                val body = JSONObject()
                val headers = HashMap<String, String>()
                when (provider) {
                    "gemini" -> {
                        url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                        headers["x-goog-api-key"] = key
                        if (system.isNotEmpty()) body.put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", system))))
                        val contents = org.json.JSONArray()
                        for (i in roles.indices) {
                            val parts = org.json.JSONArray()
                            for (id in imgs[i]) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(id))))
                            parts.put(JSONObject().put("text", texts[i]))
                            contents.put(JSONObject().put("role", if (roles[i] == "assistant") "model" else "user").put("parts", parts))
                        }
                        body.put("contents", contents).put("generationConfig", JSONObject().put("maxOutputTokens", maxTok).put("temperature", temp))
                    }
                    "claude" -> {
                        url = "https://api.anthropic.com/v1/messages"
                        headers["x-api-key"] = key
                        headers["anthropic-version"] = "2023-06-01"
                        body.put("model", model).put("max_tokens", maxTok).put("temperature", Math.min(temp, 1.0))
                        if (system.isNotEmpty()) body.put("system", system)
                        val arr = org.json.JSONArray()
                        for (i in roles.indices) {
                            val parts = org.json.JSONArray()
                            for (id in imgs[i]) parts.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(id))))
                            parts.put(JSONObject().put("type", "text").put("text", texts[i]))
                            arr.put(JSONObject().put("role", roles[i]).put("content", parts))
                        }
                        body.put("messages", arr)
                    }
                    "grok" -> {
                        url = "https://api.x.ai/v1/chat/completions"
                        headers["Authorization"] = "Bearer $key"
                        body.put("model", model).put("max_tokens", maxTok).put("temperature", temp)
                        val arr = org.json.JSONArray()
                        if (system.isNotEmpty()) arr.put(JSONObject().put("role", "system").put("content", system))
                        for (i in roles.indices) {
                            if (imgs[i].isEmpty()) arr.put(JSONObject().put("role", roles[i]).put("content", texts[i]))
                            else {
                                val parts = org.json.JSONArray()
                                for (id in imgs[i]) parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64(id))))
                                parts.put(JSONObject().put("type", "text").put("text", texts[i]))
                                arr.put(JSONObject().put("role", roles[i]).put("content", parts))
                            }
                        }
                        body.put("messages", arr)
                    }
                    else -> { chatError("Unknown online service."); return@execute }
                }

                js("__onStatus", "Asking the online AI...")
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                cloudConn = c
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 20000
                c.readTimeout = 120000
                c.setRequestProperty("Content-Type", "application/json")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
                cloudConn = null
                if (cancelChat) { js("__onChatDone", JSONObject().put("stopped", true).toString()); return@execute }
                if (code !in 200..299) {
                    var msg = ""
                    try {
                        val j = JSONObject(text)
                        val e = j.opt("error")
                        msg = if (e is JSONObject) e.optString("message") else (e?.toString() ?: j.optString("message"))
                    } catch (e: Exception) {
                    }
                    val who = when (provider) { "gemini" -> "Google"; "claude" -> "Anthropic"; else -> "xAI" }
                    chatError(when (code) {
                        400 -> "$who did not accept the request: " + msg.take(200)
                        401, 403 -> "$who refused the key. Check it in Settings, Online AI. " + msg.take(120)
                        404 -> "$who does not know that model name. Check it in Settings, Online AI."
                        429 -> "$who says the limit is reached (too many requests or the free quota is used up). Try again later."
                        else -> "$who error $code: " + msg.take(200)
                    })
                    return@execute
                }
                val j = JSONObject(text)
                val sb = StringBuilder()
                when (provider) {
                    "gemini" -> {
                        val cand = j.optJSONArray("candidates")?.optJSONObject(0)
                        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val pt = parts.getJSONObject(i)
                            if (!pt.optBoolean("thought", false)) sb.append(pt.optString("text"))
                        }
                        if (sb.isEmpty()) {
                            val why = j.optJSONObject("promptFeedback")?.optString("blockReason") ?: cand?.optString("finishReason") ?: ""
                            chatError("Google gave no answer" + (if (why.isNotEmpty()) " ($why)." else "."))
                            return@execute
                        }
                    }
                    "claude" -> {
                        val arr = j.optJSONArray("content")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val b = arr.getJSONObject(i)
                            if (b.optString("type") == "text") sb.append(b.optString("text"))
                        }
                    }
                    else -> sb.append(j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: "")
                }
                val reply = sb.toString().trim()
                if (reply.isEmpty()) { chatError("The online AI sent an empty answer."); return@execute }
                js("__onToken", reply)
                js("__onChatDone", JSONObject().put("stopped", false).put("cloud", true).put("ms", System.currentTimeMillis() - t0).toString())
            } catch (t: Throwable) {
                cloudConn = null
                if (cancelChat) js("__onChatDone", JSONObject().put("stopped", true).toString())
                else chatError(if (t is java.net.UnknownHostException || t is java.net.SocketTimeoutException || t is java.net.ConnectException) "No internet connection, or the online AI did not answer in time." else "Online AI problem: ${t.message}")
            }
        }
    }

    @JavascriptInterface
    fun stopChat() {
        cancelChat = true
        try { cloudConn?.disconnect() } catch (e: Throwable) {}
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
                        override fun onDone(id: String?) { if (id != null && id.endsWith("_last")) ui.post { WakeCenter.paused = false; js("__onSpeakDone", "") } }
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
                WakeCenter.paused = true
                t.stop()
                t.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                try {
                    val loc = java.util.Locale.getDefault()
                    if (t.isLanguageAvailable(loc) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) t.language = loc
                } catch (e: Throwable) {
                }
                try {
                    val vid = prefs.getString("ttsVoice", "") ?: ""
                    if (vid.isNotEmpty()) t.voices?.firstOrNull { it.name == vid }?.let { t.voice = it }
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
        ui.post { WakeCenter.paused = false; try { tts?.stop() } catch (e: Throwable) {} }
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

    private fun startListening(offline: Boolean = true) {
        WakeCenter.paused = true
        Hush.on(activity)
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
                Hush.off(activity)
                // The recognizer service dropped or has no offline pack: try again online, then fall back to the phone's own voice window.
                if (code == 11 || code == 12 || code == 13 || code == 5) {
                    ui.postDelayed({
                        if (offline) startListening(false)
                        else {
                            WakeCenter.paused = true
                            if (!activity.speechDialog()) {
                                WakeCenter.paused = false
                                js("__onSpeech", JSONObject().put("state", "error").put("msg", "Speech recognition is not working on this phone. Check Google app, Settings, Voice.").toString())
                            }
                        }
                    }, 300)
                    return
                }
                val msg = when (code) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT, android.speech.SpeechRecognizer.ERROR_SERVER ->
                        "Speech recognition needs internet on this phone, or an offline language pack (Google app, Settings, Voice, Offline speech recognition)."
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is off for this app."
                    android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again."
                    else -> "Speech recognition failed (code $code)."
                }
                WakeCenter.paused = false
                js("__onSpeech", JSONObject().put("state", "error").put("msg", msg).toString())
            }
            override fun onResults(b: android.os.Bundle?) {
                Hush.off(activity)
                WakeCenter.paused = false
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
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    /** Result of the phone's own voice window (the fallback). */
    fun onVoiceDialog(text: String?) {
        WakeCenter.paused = false
        if (text.isNullOrBlank()) js("__onSpeech", JSONObject().put("state", "error").put("msg", "").toString())
        else js("__onSpeech", JSONObject().put("state", "done").put("text", text).toString())
    }

    @JavascriptInterface
    fun stopListening() {
        ui.post { try { recognizer?.stopListening() } catch (e: Throwable) {} }
    }

    @JavascriptInterface
    fun cancelListening() {
        Hush.off(activity)
        ui.post { try { recognizer?.cancel() } catch (e: Throwable) {} }
    }

    // ---------- wake word ("hey Alex") ----------

    private val prefs get() = activity.getSharedPreferences("mai", Context.MODE_PRIVATE)

    fun onWake() {
        js("__onWake", "")
    }

    private fun wakePhrase(name: String): String =
        "hey " + name.lowercase().replace(Regex("[^a-z ]"), "").trim().ifEmpty { "ajax" }

    @JavascriptInterface
    fun wakeStatus(): String = JSONObject()
        .put("model", WakeCenter.modelReady(activity))
        .put("running", WakeCenter.running)
        .put("on", prefs.getBoolean("wakeOn", false))
        .put("name", prefs.getString("wakeName", "Ajax"))
        .put("spkModel", WakeCenter.spkModelReady(activity))
        .put("spkLoaded", WakeCenter.spkLoaded)
        .put("enrolled", (prefs.getString("spkVec", "") ?: "").isNotEmpty())
        .put("spkOn", prefs.getBoolean("spkOn", false))
        .put("level", prefs.getFloat("spkLevel", 0.6f).toDouble())
        .put("lastDist", WakeCenter.lastDist)
        .put("lastConf", WakeCenter.lastConf)
        .put("lockWake", prefs.getBoolean("lockWake", true))
        .toString()

    /** The settings the background assistant needs while the screen is closed (model, name, city, online key). */
    @JavascriptInterface
    fun assistantConfig(json: String) {
        try { JSONObject(json); prefs.edit().putString("assistantCfg", json).apply() } catch (e: Exception) {}
    }

    /** Downloads the voice-match model (about 13 MB). Progress in __onSpkModel. */
    @JavascriptInterface
    fun spkDownloadModel() {
        Thread {
            try {
                val c = java.net.URL("https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 30000
                val total = c.contentLengthLong
                val tmp = File(activity.cacheDir, "vosk-spk.zip")
                c.inputStream.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(65536)
                        var got = 0L
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; js("__onSpkModel", JSONObject().put("pct", pct).toString()) }
                        }
                    }
                }
                val dest = WakeCenter.spkDir(activity)
                dest.deleteRecursively()
                dest.mkdirs()
                val destPath = dest.canonicalPath + File.separator
                java.util.zip.ZipInputStream(java.io.FileInputStream(tmp)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || e.isDirectory) continue
                        val f = File(dest, rel)
                        if (!f.canonicalPath.startsWith(destPath)) continue
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zin.copyTo(it) }
                    }
                }
                tmp.delete()
                if (!WakeCenter.spkModelReady(activity)) throw IllegalStateException("The voice-match model looks incomplete.")
                js("__onSpkModel", JSONObject().put("done", true).toString())
            } catch (e: Exception) {
                js("__onSpkModel", JSONObject().put("error", e.message ?: "Download failed. Check your internet.").toString())
            }
        }.start()
    }

    /** Restarts the listener so it picks up a newly downloaded voice-match model. */
    @JavascriptInterface
    fun wakeRestart() {
        activity.startService(android.content.Intent(activity, WakeService::class.java).setAction(WakeService.ACTION_STOP))
        ui.postDelayed({ if (prefs.getBoolean("wakeOn", false)) wakeStartService() }, 800)
    }

    /** Starts learning the person's voice: they say the wake phrase five times. Progress in __onEnroll. */
    @JavascriptInterface
    fun spkEnroll(): String {
        if (!WakeCenter.spkModelReady(activity)) return JSONObject().put("ok", false).put("msg", "Download the voice-match model first.").toString()
        if (!prefs.getBoolean("wakeOn", false) || !WakeCenter.running) return JSONObject().put("ok", false).put("msg", "Switch the wake word on first.").toString()
        if (!WakeCenter.spkLoaded) { wakeRestart(); return JSONObject().put("ok", false).put("msg", "Restarting the listener. Tap again in a few seconds.").toString() }
        WakeCenter.samples.clear()
        WakeCenter.enrollLeft = WakeCenter.ENROLL_TOTAL
        WakeCenter.onEnroll = { done, total -> js("__onEnroll", JSONObject().put("done", done).put("total", total).toString()) }
        return JSONObject().put("ok", true).put("msg", "Say \"" + wakePhrase(prefs.getString("wakeName", "Ajax") ?: "Ajax") + "\" " + WakeCenter.ENROLL_TOTAL + " times, with a short pause between.").toString()
    }

    @JavascriptInterface
    fun spkSet(on: Boolean, level: Double) {
        prefs.edit().putBoolean("spkOn", on).putFloat("spkLevel", level.toFloat().coerceIn(0.2f, 1.0f)).apply()
    }

    @JavascriptInterface
    fun spkClear() {
        prefs.edit().remove("spkVec").putBoolean("spkOn", false).apply()
        WakeCenter.enrollLeft = 0
    }

    /** Downloads the small offline English voice model (about 40 MB) and unpacks it. Progress in __onWakeModel. */
    @JavascriptInterface
    fun wakeDownloadModel() {
        Thread {
            try {
                val url = java.net.URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20000
                c.readTimeout = 30000
                val total = c.contentLengthLong
                val tmp = File(activity.cacheDir, "vosk-model.zip")
                c.inputStream.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(65536)
                        var got = 0L
                        var lastPct = -1
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; js("__onWakeModel", JSONObject().put("pct", pct).toString()) }
                        }
                    }
                }
                val dest = File(activity.filesDir, "vosk/model")
                dest.deleteRecursively()
                dest.mkdirs()
                val destPath = dest.canonicalPath + File.separator
                java.util.zip.ZipInputStream(java.io.FileInputStream(tmp)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isEmpty() || e.isDirectory) continue
                        val f = File(dest, rel)
                        if (!f.canonicalPath.startsWith(destPath)) continue
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zin.copyTo(it) }
                    }
                }
                tmp.delete()
                if (!WakeCenter.modelReady(activity)) throw IllegalStateException("The voice model looks incomplete.")
                js("__onWakeModel", JSONObject().put("done", true).toString())
            } catch (e: Exception) {
                js("__onWakeModel", JSONObject().put("error", e.message ?: "Download failed. Check your internet.").toString())
            }
        }.start()
    }

    /** Turns the wake word on or off. Returns {"ok":..,"msg":..}. */
    @JavascriptInterface
    fun wakeSet(on: Boolean, name: String): String {
        val nm = name.lowercase().replace(Regex("[^a-z ]"), "").trim().ifEmpty { "ajax" }
        prefs.edit().putBoolean("wakeOn", on).putString("wakeName", nm.replaceFirstChar { it.uppercase() }).apply()
        if (!on) {
            activity.startService(android.content.Intent(activity, WakeService::class.java).setAction(WakeService.ACTION_STOP))
            WakeCenter.onWake = null
            return JSONObject().put("ok", true).toString()
        }
        if (!WakeCenter.modelReady(activity)) return JSONObject().put("ok", false).put("msg", "Download the voice model first.").toString()
        if (!activity.hasMic()) {
            ui.post { activity.askMic() }
            return JSONObject().put("ok", false).put("msg", "Allow the microphone, then switch this on again.").toString()
        }
        ui.post { activity.askNotificationPermission() }
        wakeStartService()
        return JSONObject().put("ok", true).put("msg", "Listening for \"" + wakePhrase(nm) + "\".").toString()
    }

    private fun wakeStartService() {
        WakeCenter.onWake = { onWake() }
        val i = android.content.Intent(activity, WakeService::class.java).putExtra("phrase", wakePhrase(prefs.getString("wakeName", "Ajax") ?: "Ajax"))
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i)
        } catch (e: Exception) {
        }
    }

    /** Called when the app opens: brings the wake word back if it was on. */
    fun wakeResume() {
        WakeCenter.onWake = { onWake() }
        if (prefs.getBoolean("wakeOn", false) && WakeCenter.modelReady(activity) && activity.hasMic() && !WakeCenter.running) wakeStartService()
    }

    // ---------- permissions checklist ----------

    @JavascriptInterface
    fun permStatus(): String {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val installer = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) activity.packageManager.getInstallSourceInfo(activity.packageName).installingPackageName
            else @Suppress("DEPRECATION") activity.packageManager.getInstallerPackageName(activity.packageName)
        } catch (e: Exception) {
            null
        }
        val notif = android.os.Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return JSONObject()
            .put("mic", activity.hasMic())
            .put("notif", notif)
            .put("files", activity.hasFilesAccess())
            .put("loc", activity.hasLocation())
            .put("overlay", android.provider.Settings.canDrawOverlays(activity))
            .put("battery", pm.isIgnoringBatteryOptimizations(activity.packageName))
            .put("xiaomi", android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") })
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("sideloaded", installer != "com.android.vending")
            .toString()
    }

    // ---------- weather helpers: coarse location, barometer, voices ----------
    @JavascriptInterface
    fun locationGet(): String {
        try {
            if (!activity.hasLocation()) return "{}"
            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            for (pr in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(pr) } catch (e: SecurityException) { null } ?: continue
                if (best == null || l.time > best.time) best = l
            }
            val b = best ?: return "{}"
            return JSONObject().put("lat", b.latitude).put("lon", b.longitude).put("age", (System.currentTimeMillis() - b.time) / 60000).toString()
        } catch (e: Throwable) {
            return "{}"
        }
    }

    /** Reads the phone's barometer (if it has one) and keeps a small history so a trend can be worked out. */
    @JavascriptInterface
    fun pressure(): String {
        try {
            val sm = activity.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) ?: return "{\"has\":false}"
            var v = -1f
            val latch = java.util.concurrent.CountDownLatch(1)
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) { v = e.values[0]; latch.countDown() }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, android.os.Handler(android.os.Looper.getMainLooper()))
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            sm.unregisterListener(l)
            if (v <= 0f) return "{\"has\":true}"
            val now = System.currentTimeMillis()
            val hist = ArrayList<Pair<Long, Float>>()
            for (e in (prefs.getString("pHist", "") ?: "").split(",")) {
                val a = e.split(":"); if (a.size == 2) { val t = a[0].toLongOrNull(); val x = a[1].toFloatOrNull(); if (t != null && x != null && now - t < 24 * 3600_000L) hist.add(Pair(t, x)) }
            }
            // trend against a reading taken 2 to 6 hours ago (closest to 3 hours)
            val ref = hist.filter { now - it.first in 2 * 3600_000L..6 * 3600_000L }.minByOrNull { Math.abs(now - it.first - 3 * 3600_000L) }
            if (hist.isEmpty() || now - hist.last().first > 20 * 60_000L) hist.add(Pair(now, v))
            prefs.edit().putString("pHist", hist.takeLast(100).joinToString(",") { it.first.toString() + ":" + it.second }).apply()
            val o = JSONObject().put("has", true).put("hpa", Math.round(v * 10) / 10.0)
            if (ref != null) o.put("change", Math.round((v - ref.second) * 10) / 10.0).put("hours", Math.round((now - ref.first) / 360_000.0) / 10.0)
            return o.toString()
        } catch (e: Throwable) {
            return "{\"has\":false}"
        }
    }

    /** Lists the phone's voices for the current language. Answer arrives in __onVoices. */
    @JavascriptInterface
    fun voiceList() {
        ui.post {
            withTts {
                val t = tts ?: return@withTts
                val arr = org.json.JSONArray()
                try {
                    val lang = java.util.Locale.getDefault().language
                    t.voices?.filter { it.locale.language == lang && !it.features.contains("notInstalled") }
                        ?.sortedBy { it.name }?.forEach {
                            arr.put(JSONObject().put("id", it.name).put("lang", it.locale.displayName).put("offline", !it.isNetworkConnectionRequired))
                        }
                } catch (e: Throwable) {
                }
                js("__onVoices", JSONObject().put("voices", arr).put("current", prefs.getString("ttsVoice", "") ?: "").toString())
            }
        }
    }

    @JavascriptInterface
    fun voiceSet(id: String) {
        prefs.edit().putString("ttsVoice", id).apply()
    }

    @JavascriptInterface
    fun isLocked(): Boolean = (activity.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    /** Is this app the phone's chosen digital assistant? */
    @JavascriptInterface
    fun assistStatus(): String {
        var cur = ""
        for (k in listOf("voice_interaction_service", "assistant")) {
            try { cur += " " + (android.provider.Settings.Secure.getString(activity.contentResolver, k) ?: "") } catch (e: Exception) {}
        }
        return JSONObject().put("isDefault", cur.contains(activity.packageName)).toString()
    }

    @JavascriptInterface
    fun assistOpenSettings() {
        ui.post {
            try {
                activity.startActivity(android.content.Intent("android.settings.VOICE_INPUT_SETTINGS"))
            } catch (e: Exception) {
                try {
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (e2: Exception) {
                    activity.openAppInfo()
                }
            }
        }
    }

    @JavascriptInterface
    fun lockWakeSet(on: Boolean) {
        prefs.edit().putBoolean("lockWake", on).apply()
    }

    @JavascriptInterface
    fun permRequest(kind: String) {
        ui.post {
            when (kind) {
                "mic" -> activity.askMic()
                "notif" -> activity.askNotificationPermission()
                "files" -> activity.requestFilesAccess()
                "battery" -> activity.askBattery()
                "loc" -> activity.askLocation()
                "overlay" -> try {
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + activity.packageName)))
                } catch (e: Exception) {
                    activity.openAppInfo()
                }
                "miuiperms" -> try {
                    activity.startActivity(android.content.Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity").putExtra("extra_pkgname", activity.packageName))
                } catch (e: Exception) {
                    activity.openAppInfo()
                }
                "autostart" -> {
                    var done = false
                    for (cls in listOf("com.miui.permcenter.autostart.AutoStartManagementActivity", "com.miui.securitycenter.autostart.AutoStartManagementActivity")) {
                        if (done) break
                        try {
                            activity.startActivity(android.content.Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", cls)))
                            done = true
                        } catch (e: Exception) {
                        }
                    }
                    if (!done) activity.openAppInfo()
                }
                else -> activity.openAppInfo()
            }
        }
    }
}
