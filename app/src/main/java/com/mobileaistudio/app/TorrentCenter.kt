package com.mobileaistudio.app

import android.content.Context
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Torrent and magnet downloads (libtorrent). Lives next to the normal download manager and shows up
 * in the same list. Finished torrents stop right away, nothing is uploaded afterwards.
 */
object TorrentCenter {
    class Entry(val id: String, val source: String, val createdAt: Long) {
        @Volatile var name: String = ""
        @Volatile var state: String = "queued" // queued, downloading, paused, done, error
        @Volatile var msg: String = ""
        @Volatile var total: Long = -1
        @Volatile var done: Long = 0
        @Volatile var speed: Long = 0
        @Volatile var peers: Int = 0
        @Volatile var info: TorrentInfo? = null
        @Volatile var busy: Boolean = false // fetching the torrent details
    }

    private lateinit var app: Context
    private lateinit var saveDir: File
    private lateinit var torrentDir: File
    private lateinit var listFile: File
    private val entries = ArrayList<Entry>()
    private var session: SessionManager? = null
    private var inited = false
    private var maxActive = 2
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "torrent").apply { isDaemon = true } }
    private var ticker: ScheduledExecutorService? = null
    @Volatile private var lastActive = false

    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        app = context.applicationContext
        saveDir = DownloadCenter.filesDir
        torrentDir = File(app.filesDir, "torrents").apply { mkdirs() }
        listFile = File(app.filesDir, "torrents.json")
        maxActive = app.getSharedPreferences("downloads", Context.MODE_PRIVATE).getInt("max", 2)
        load()
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "torrent-tick").apply { isDaemon = true } }
        ticker!!.scheduleWithFixedDelay({ try { tick() } catch (e: Throwable) { } }, 1, 1, TimeUnit.SECONDS)
    }

    private fun changed() {
        onChange?.invoke()
    }

    // ---------- the session ----------

    @Synchronized
    private fun session(): SessionManager {
        var s = session
        if (s == null) {
            s = SessionManager()
            s.start()
            session = s
            applyLimits()
        }
        return s
    }

    private fun applyLimits() {
        val s = session ?: return
        try {
            val sp = SettingsPack()
            sp.activeDownloads(maxActive)
            sp.activeLimit(maxActive + 50)
            s.applySettings(sp)
        } catch (e: Throwable) {
        }
    }

    @Synchronized
    fun setMax(n: Int) {
        maxActive = n
        applyLimits()
    }

    // ---------- the list ----------

    @Synchronized
    fun list(): List<Entry> = ArrayList(entries)

    private fun find(id: String): Entry? = synchronized(this) { entries.firstOrNull { it.id == id } }

    fun json(e: Entry): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("url", if (e.source.startsWith("magnet:", true)) "magnet link" else e.source)
        o.put("kind", "file")
        o.put("tag", "")
        o.put("name", if (e.name.isNotEmpty()) e.name else "Torrent")
        o.put("state", e.state)
        o.put("msg", e.msg)
        o.put("total", e.total)
        o.put("done", e.done)
        o.put("threads", 0)
        o.put("speed", e.speed)
        o.put("ftp", false)
        o.put("torrent", true)
        o.put("peers", e.peers)
        return o
    }

    fun isTorrentId(id: String) = id.startsWith("t")

    /** Adds a magnet link or the address of a .torrent file. */
    fun add(source: String): String {
        val src = source.trim()
        val low = src.lowercase()
        require(low.startsWith("magnet:") || low.startsWith("http://") || low.startsWith("https://")) {
            "Use a magnet link or the address of a .torrent file."
        }
        val e = Entry("t" + UUID.randomUUID().toString().substring(0, 7), src, System.currentTimeMillis())
        synchronized(this) {
            if (entries.any { it.source == src && it.state != "done" }) throw IllegalArgumentException("This download is already in the list.")
            entries.add(e)
        }
        e.state = "queued"
        save()
        changed()
        start(e)
        return e.id
    }

    /** Gets the torrent details (for a magnet link from other people's computers, which can take a while) and starts it. */
    private fun start(e: Entry) {
        e.state = "downloading"
        e.msg = if (e.info == null) "Finding the torrent..." else ""
        updateActive()
        changed()
        pool.execute {
            try {
                var ti = e.info
                if (ti == null) {
                    e.busy = true
                    ti = loadInfo(e)
                    e.info = ti
                    e.name = ti.name()
                    e.total = ti.totalSize()
                    save()
                }
                if (e.state != "downloading") { e.busy = false; return@execute } // paused or removed meanwhile
                session().download(ti, saveDir)
                e.busy = false
                e.msg = ""
            } catch (t: Throwable) {
                e.busy = false
                if (e.state == "downloading") {
                    e.state = "error"
                    e.msg = t.message?.takeIf { it.isNotBlank() } ?: "Could not start the torrent."
                }
            }
            save()
            updateActive()
            changed()
        }
    }

    private fun loadInfo(e: Entry): TorrentInfo {
        val file = File(torrentDir, e.id + ".torrent")
        if (file.exists()) return TorrentInfo.bdecode(file.readBytes())
        val data: ByteArray
        if (e.source.startsWith("magnet:", true)) {
            data = session().fetchMagnet(e.source, 90, File(app.cacheDir, "magnet").apply { mkdirs() })
                ?: throw Exception("No one answered for this magnet link. It may be dead or have no seeders. Try again later.")
        } else {
            data = fetchBytes(e.source)
        }
        val ti = TorrentInfo.bdecode(data)
        file.writeBytes(data)
        return ti
    }

    private fun fetchBytes(url: String): ByteArray {
        var cur = url
        for (hop in 0..6) {
            val c = java.net.URL(cur).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MobileAIStudio/0.3")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Bad redirect.")
                c.disconnect()
                cur = java.net.URL(java.net.URL(cur), loc).toString()
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw Exception("The server answered $code for the .torrent file.")
            }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 10_000_000) throw Exception("That .torrent file is too large.")
                }
            }
            c.disconnect()
            return out.toByteArray()
        }
        throw Exception("Too many redirects.")
    }

    private fun handleOf(e: Entry): TorrentHandle? {
        val ti = e.info ?: return null
        val s = session ?: return null
        return try { s.find(ti.infoHash()) } catch (t: Throwable) { null }
    }

    fun pause(id: String) {
        val e = find(id) ?: return
        if (e.state == "downloading" || e.state == "queued") {
            e.state = "paused"
            e.msg = ""
            e.speed = 0
            try { handleOf(e)?.pause() } catch (t: Throwable) { }
            save(); updateActive(); changed()
        }
    }

    fun pauseAll() {
        for (e in list()) pause(e.id)
    }

    fun resume(id: String) {
        val e = find(id) ?: return
        if (e.state != "paused" && e.state != "error") return
        if (e.busy) return
        e.msg = ""
        val h = handleOf(e)
        if (h != null) {
            e.state = "downloading"
            try { h.resume() } catch (t: Throwable) { }
            updateActive(); changed()
        } else {
            start(e)
        }
        save()
    }

    fun remove(id: String, deleteFiles: Boolean) {
        val e = find(id) ?: return
        val ti = e.info
        val h = handleOf(e)
        val target = if (ti != null) File(saveDir, ti.name()) else null
        e.state = "removed"
        synchronized(this) { entries.remove(e) }
        pool.execute {
            try { if (h != null) session?.remove(h) } catch (t: Throwable) { }
            File(torrentDir, e.id + ".torrent").delete()
            if (deleteFiles && target != null) target.deleteRecursively()
            changed()
        }
        save(); updateActive(); changed()
    }

    /** The finished files of a torrent (one file, or everything inside its folder). */
    fun filesOf(id: String): List<Pair<File, String>> {
        val e = find(id) ?: return emptyList()
        val ti = e.info ?: return emptyList()
        val root = File(saveDir, ti.name())
        if (!root.exists()) return emptyList()
        if (root.isFile) return listOf(Pair(root, root.name))
        return root.walkTopDown().filter { it.isFile }.map { Pair(it, ti.name() + "/" + it.relativeTo(root).path) }.toList()
    }

    // ---------- progress ----------

    private fun tick() {
        var any = false
        for (e in list()) {
            if (e.state != "downloading" || e.busy) continue
            any = true
            val h = handleOf(e) ?: continue
            val st = h.status()
            e.done = st.totalWantedDone()
            e.total = st.totalWanted()
            e.speed = st.downloadPayloadRate().toLong()
            e.peers = st.numPeers()
            if (st.isFinished) {
                e.state = "done"
                e.speed = 0
                e.done = e.total
                e.msg = ""
                try { h.pause() } catch (t: Throwable) { } // do not seed
                save()
                updateActive()
            }
        }
        if (any) changed()
    }

    fun summary(): Triple<Int, Long, Long> {
        var run = 0
        var speed = 0L
        var left = 0L
        for (e in list()) if (e.state == "downloading") { run++; speed += e.speed; left += maxOf(0L, e.total - e.done) }
        return Triple(run, speed, left)
    }

    private fun updateActive() {
        val a = list().any { it.state == "downloading" || it.state == "queued" }
        if (a != lastActive) {
            lastActive = a
            DownloadCenter.setTorrentActive(a)
        }
    }

    // ---------- saved list ----------

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                if (e.state == "removed") continue
                arr.put(JSONObject().put("id", e.id).put("src", e.source).put("name", e.name).put("state", e.state)
                    .put("total", e.total).put("done", e.done).put("created", e.createdAt))
            }
            val tmp = File(listFile.path + ".tmp")
            tmp.writeText(arr.toString())
            if (listFile.exists()) listFile.delete()
            tmp.renameTo(listFile)
        } catch (t: Throwable) {
        }
    }

    private fun load() {
        try {
            if (!listFile.exists()) return
            val arr = JSONArray(listFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(o.getString("id"), o.getString("src"), o.optLong("created"))
                e.name = o.optString("name")
                e.total = o.optLong("total", -1)
                e.done = o.optLong("done")
                var st = o.optString("state", "paused")
                if (st == "downloading" || st == "queued") { st = "paused"; e.msg = "Paused when the app closed." }
                e.state = st
                val f = File(torrentDir, e.id + ".torrent")
                if (f.exists()) {
                    try { e.info = TorrentInfo.bdecode(f.readBytes()) } catch (t: Throwable) { }
                }
                if (st == "done" && (e.info == null || !File(DownloadCenter.filesDir, e.info!!.name()).exists())) continue
                entries.add(e)
            }
        } catch (t: Throwable) {
        }
    }

    fun shutdown() {
        // Called only when the app process is going away for good.
        try { session?.stop() } catch (t: Throwable) { }
    }
}
