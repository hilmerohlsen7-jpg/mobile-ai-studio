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
    private val stateFile = File(activity.filesDir, "state.json")
    private val worker = Executors.newSingleThreadExecutor()
    private val downloader = Downloader(modelsDir) { json -> js("__onDownload", json) }

    @Volatile
    private var cancelChat = false
    private var handle = 0L
    private var loadedKey = ""
    private var importing: String? = null

    // ---------- helpers ----------

    private fun js(fn: String, arg: String) {
        ui.post { web.evaluateJavascript("window.$fn && window.$fn(${JSONObject.quote(arg)})", null) }
    }

    private fun chatError(msg: String) = js("__onChatError", msg)

    fun shutdown() {
        cancelChat = true
        downloader.cancel()
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
            val part = File(modelsDir, m.file + ".part")
            known.add(m.file)
            val o = JSONObject()
            o.put("id", m.id)
            o.put("name", m.name)
            o.put("sizeMB", m.sizeMB)
            o.put("note", m.note)
            o.put("recommended", m.recommended)
            o.put("custom", false)
            o.put("state", if (f.exists()) "ready" else if (downloader.activeId == m.id) "downloading" else "none")
            o.put("partMB", if (part.exists()) part.length() / 1048576L else 0L)
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
            arr.put(o)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun downloadModel(id: String) {
        val m = Catalog.byId(id)
        if (m == null) {
            js("__onDownload", JSONObject().put("id", id).put("state", "error").put("msg", "Unknown model.").toString())
            return
        }
        val err = downloader.start(m)
        if (err != null) {
            js("__onDownload", JSONObject().put("id", id).put("state", "error").put("msg", err).toString())
        }
    }

    @JavascriptInterface
    fun cancelDownload() {
        downloader.cancel()
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
