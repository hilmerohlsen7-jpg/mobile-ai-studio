package com.mobileaistudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds a playable video file for a YouTube video id by asking public Piped / Invidious services.
 * The list of services can be changed without a new app: it is fetched from instances.json in the project's GitHub repo.
 */
object YtStreams {
    private val DEFAULT = listOf(
        "p|https://pipedapi.kavin.rocks", "p|https://pipedapi.adminforge.de", "p|https://api.piped.private.coffee",
        "i|https://inv.nadeko.net", "i|https://yewtu.be", "i|https://invidious.nerdvpn.de"
    )
    private const val REMOTE = "https://raw.githubusercontent.com/hilmerohlsen7-jpg/mobile-ai-studio/main/instances.json"

    private fun get(u: String, max: Int = 3_000_000): String? = try {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (c.responseCode !in 200..299) null else c.inputStream.use { ins ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int
            while (ins.read(buf).also { n = it } > 0) { out.write(buf, 0, n); if (out.size() > max) break }
            out.toString("UTF-8")
        }
    } catch (e: Throwable) { null }

    /** Downloads the newest list of services once; it is used from then on. */
    fun refresh(ctx: Context) {
        Thread {
            val t = get(REMOTE, 100_000) ?: return@Thread
            try { val a = JSONArray(t); if (a.length() > 0) ctx.getSharedPreferences("browser", Context.MODE_PRIVATE).edit().putString("yt_instances", a.toString()).apply() } catch (_: Throwable) {}
        }.start()
    }

    private fun list(ctx: Context): List<String> = try {
        val a = JSONArray(ctx.getSharedPreferences("browser", Context.MODE_PRIVATE).getString("yt_instances", null) ?: throw Exception())
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Throwable) { DEFAULT }

    /** Returns a direct address of a video (with sound) or null when no service could give one. Call from a background thread. */
    fun resolve(ctx: Context, id: String): String? {
        for (e in list(ctx)) {
            val kind = e.substringBefore("|"); val base = e.substringAfter("|").trimEnd('/')
            try {
                if (kind == "p") {
                    val j = JSONObject(get("$base/streams/$id") ?: continue)
                    val vs = j.optJSONArray("videoStreams")
                    var best: String? = null; var bh = 0
                    if (vs != null) for (i in 0 until vs.length()) {
                        val o = vs.getJSONObject(i)
                        if (o.optBoolean("videoOnly", true)) continue
                        val h = o.optInt("height", 0)
                        if (h in 1..720 && h >= bh) { bh = h; best = o.optString("url") }
                    }
                    if (!best.isNullOrBlank()) return best
                    val hls = j.optString("hls"); if (hls.isNotBlank()) return hls
                } else {
                    val j = JSONObject(get("$base/api/v1/videos/$id?local=true") ?: continue)
                    val fs = j.optJSONArray("formatStreams")
                    var best: String? = null; var rank = -1
                    if (fs != null) for (i in 0 until fs.length()) {
                        val o = fs.getJSONObject(i)
                        val r = when (o.optString("itag")) { "22" -> 2; "18" -> 1; else -> 0 }
                        if (r > rank) { rank = r; best = o.optString("url") }
                    }
                    if (!best.isNullOrBlank()) return if (best.startsWith("http")) best else base + best
                }
            } catch (_: Throwable) {}
        }
        return null
    }
}
