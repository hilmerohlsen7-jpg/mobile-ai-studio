package com.mobileaistudio.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Silences the system's start and stop listening beeps while the phone is listening, then puts the volume back. */
object Hush {
    private val main = Handler(Looper.getMainLooper())
    private val streams = intArrayOf(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.STREAM_NOTIFICATION)
    private val mine = HashSet<Int>()
    private var ctxRef: Context? = null
    private val failSafe = Runnable { ctxRef?.let { off(it, 0) } }

    @Synchronized fun on(ctx: Context) {
        ctxRef = ctx.applicationContext
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in streams) {
            if (st in mine) continue
            try {
                if (!am.isStreamMute(st)) { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_MUTE, 0); mine.add(st) }
            } catch (e: Throwable) {
            }
        }
        main.removeCallbacks(failSafe)
        main.postDelayed(failSafe, 25000)
    }

    fun off(ctx: Context, delayMs: Long = 450) {
        main.removeCallbacks(failSafe)
        main.postDelayed({ release(ctx) }, delayMs)
    }

    @Synchronized private fun release(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        for (st in mine) try { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (e: Throwable) {}
        mine.clear()
    }
}

/** The loaded model, shared by the screen and the background assistant so it is only in memory once. */
object Shared {
    @JvmField val lock = Any()
    @Volatile var handle = 0L
    @Volatile var loadedKey = ""
}

/**
 * Answers by voice without opening the app: greets, listens, thinks (local model or an online AI), speaks, and repeats
 * until the person says stop or stays quiet. Started by the wake-word service when the app is not on screen.
 */
object HeadlessAssistant {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()

    @Volatile var active = false
    private lateinit var app: Context
    private var cfg = JSONObject()
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private var onSpoken: (() -> Unit)? = null
    private val history = ArrayList<Pair<String, String>>()
    private var empties = 0
    private var retried = false
    private var seq = 0
    private val idleFree = Runnable { freeIfIdle() }

    private fun loadCfg(ctx: Context): JSONObject = try {
        JSONObject(ctx.getSharedPreferences("mai", Context.MODE_PRIVATE).getString("assistantCfg", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    /** True when the person wants voice answers with the app closed, and an AI is available for it. */
    fun configured(ctx: Context): Boolean {
        val c = loadCfg(ctx)
        return c.optBoolean("headless", true) && (c.optString("local").isNotEmpty() || c.optJSONObject("cloud") != null)
    }

    fun start(ctx: Context) {
        if (active) return
        app = ctx.applicationContext
        cfg = loadCfg(app)
        active = true
        history.clear()
        empties = 0
        retried = false
        WakeCenter.paused = true
        main.removeCallbacks(idleFree)
        main.post {
            initTts {
                val name = cfg.optString("userName").trim()
                if (cfg.optBoolean("greet", true)) say(if (name.isNotEmpty()) "Hi $name, how can I assist?" else "Hi, how can I assist?") { listen() }
                else listen()
            }
        }
    }

    // ---------- speaking ----------

    private fun initTts(done: () -> Unit) {
        if (tts != null) { done(); return }
        var made: TextToSpeech? = null
        made = TextToSpeech(app) { status ->
            val x = made
            if (status != TextToSpeech.SUCCESS || x == null) {
                end()
            } else {
                try {
                    val loc = Locale.getDefault()
                    if (x.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) x.language = loc
                } catch (e: Throwable) {
                }
                try {
                    val vid = app.getSharedPreferences("mai", Context.MODE_PRIVATE).getString("ttsVoice", "") ?: ""
                    if (vid.isNotEmpty()) x.voices?.firstOrNull { it.name == vid }?.let { x.voice = it }
                } catch (e: Throwable) {
                }
                x.setSpeechRate(cfg.optDouble("rate", 1.0).toFloat())
                x.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { spoken() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { spoken() }
                })
                tts = x
                done()
            }
        }
    }

    private fun spoken() {
        main.post {
            val f = onSpoken
            onSpoken = null
            f?.invoke()
        }
    }

    private fun say(text: String, then: () -> Unit) {
        val t = tts
        if (t == null || !active) { then(); return }
        onSpoken = then
        val r = t.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "a" + (++seq))
        if (r != TextToSpeech.SUCCESS) { spoken(); return }
        val mine = then
        main.postDelayed({ if (active && onSpoken === mine) { onSpoken = null; mine() } }, 60000)
    }

    // ---------- listening ----------

    private fun listen() {
        if (!active) return
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            say("Speech recognition is not available on this phone.") { end() }
            return
        }
        try { sr?.destroy() } catch (e: Throwable) {}
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { Hush.off(app); main.post { listenError(error) } }
            override fun onResults(results: Bundle?) {
                Hush.off(app)
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                main.post { heard(t) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        try {
            Hush.on(app)
            r.startListening(i)
        } catch (e: Throwable) {
            Hush.off(app, 0)
            end()
        }
    }

    private fun listenError(code: Int) {
        if (!active) return
        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { quiet(); return }
        if (!retried) { retried = true; listen(); return }
        say("Sorry, I could not hear that.") { end() }
    }

    private fun quiet() {
        empties++
        if (empties >= 2) end() else listen()
    }

    private val stopWords = Regex("^\\s*(stop|goodbye|bye|that'?s all|end( the)? conversation|never ?mind)\\b", RegexOption.IGNORE_CASE)

    private fun heard(text: String) {
        if (!active) return
        val t = text.trim()
        if (t.isEmpty()) { quiet(); return }
        if (stopWords.containsMatchIn(t)) { say("Okay.") { end() }; return }
        empties = 0
        retried = false
        pool.execute {
            val reply = reply(t)
            main.post {
                if (active) say(reply) { if (cfg.optBoolean("convo", true)) listen() else end() }
            }
        }
    }

    private fun end() {
        Hush.off(app, 0)
        main.post {
            try { sr?.destroy() } catch (e: Throwable) {}
            sr = null
            try { tts?.stop(); tts?.shutdown() } catch (e: Throwable) {}
            tts = null
            onSpoken = null
            active = false
            WakeCenter.paused = false
            main.removeCallbacks(idleFree)
            main.postDelayed(idleFree, 5 * 60_000L)
        }
    }

    /** A model left in memory while the app is closed costs RAM, so let it go after a few quiet minutes. */
    private fun freeIfIdle() {
        if (active || WakeCenter.visible) return
        pool.execute {
            synchronized(Shared.lock) {
                if (!active && !WakeCenter.visible && Shared.handle != 0L) {
                    try { Native.freeModel(Shared.handle) } catch (e: Throwable) {}
                    Shared.handle = 0L
                    Shared.loadedKey = ""
                }
            }
        }
    }

    // ---------- thinking ----------

    private val weatherWords = Regex("\\b(weather|forecast|rain(ing|y)?|temperature|how (hot|cold|warm)|umbrella|sunny|windy|storm|snow(ing)?|jacket)\\b", RegexOption.IGNORE_CASE)
    private val stopPlace = setOf("today", "tomorrow", "tonight", "now", "this", "right", "please", "later", "like", "and", "is", "will", "going", "be", "at")

    private fun placeFrom(text: String): String {
        val m = Regex("\\b(?:in|for|at)\\s+([a-z][a-z'\\-]*(?:\\s+[a-z][a-z'\\-]*){0,2})", RegexOption.IGNORE_CASE).find(text) ?: return ""
        val out = ArrayList<String>()
        for (w in m.groupValues[1].split(" ")) {
            if (w.lowercase() in stopPlace) break
            out.add(w)
        }
        return out.joinToString(" ")
    }

    private fun reply(text: String): String {
        try {
            var q = text
            if (weatherWords.containsMatchIn(text)) {
                val w = VoiceWeather.report(app, placeFrom(text), cfg.optString("city"))
                q = "Weather information:\n$w\n\nUsing only this information, answer briefly: $text"
            }
            history.add(Pair("user", q))
            while (history.size > 8) history.removeAt(0)
            while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
            val name = cfg.optString("userName").trim()
            val sys = "You are a friendly voice assistant on a phone. Your answer is read out loud, so reply in one to three short sentences of plain text. " +
                "No lists, no markdown, no emoji. If you do not know something, say so briefly." +
                (if (name.isNotEmpty()) " The person's name is $name." else "")
            val out = ask(sys)
            history.add(Pair("assistant", out))
            return out
        } catch (e: Throwable) {
            return "Sorry, " + (e.message ?: "something went wrong") + "."
        }
    }

    private fun ask(system: String): String {
        val cloud = cfg.optJSONObject("cloud")
        val local = cfg.optString("local")
        if (cloud != null) {
            try {
                return CloudText.ask(cloud.optString("provider"), cloud.optString("key"), cloud.optString("model"), system, history, 400)
            } catch (e: Throwable) {
                if (local.isEmpty()) throw e
            }
        }
        if (local.isEmpty()) throw IllegalStateException("no AI model is chosen. Open the app and pick one")
        return askLocal(system, local)
    }

    private fun askLocal(system: String, id: String): String {
        synchronized(Shared.lock) {
            if (!Native.ensureLoaded(app.applicationInfo.nativeLibraryDir)) throw IllegalStateException("the AI engine could not start")
            val file = Catalog.fileFor(File(app.filesDir, "models"), id)
            if (file == null || !file.exists()) throw IllegalStateException("that model is not downloaded")
            if (Shared.handle == 0L || !Shared.loadedKey.startsWith("$id|")) {
                if (Shared.handle != 0L) {
                    Native.freeModel(Shared.handle)
                    Shared.handle = 0L
                    Shared.loadedKey = ""
                }
                val threads = cfg.optInt("threads", 4).coerceIn(1, 8)
                val h = Native.loadModel(file.absolutePath, 2048, threads)
                if (h == 0L) throw IllegalStateException("the model could not load")
                Shared.handle = h
                Shared.loadedKey = "$id|2048|$threads"
            }
            val h = Shared.handle
            val maxTok = 200
            val roles = ArrayList<String>()
            val contents = ArrayList<ByteArray>()
            roles.add("system"); contents.add(system.toByteArray(Charsets.UTF_8))
            for ((r, t) in history) { roles.add(r); contents.add(t.toByteArray(Charsets.UTF_8)) }
            val limit = Native.contextSize(h) - maxTok - 16
            var count = Native.countTokens(h, roles.toTypedArray(), contents.toTypedArray())
            while (count > limit && roles.size > 2) {
                roles.removeAt(1); contents.removeAt(1)
                while (roles.size > 2 && roles[1] != "user") { roles.removeAt(1); contents.removeAt(1) }
                count = Native.countTokens(h, roles.toTypedArray(), contents.toTypedArray())
            }
            if (count < 0 || count > limit) throw IllegalStateException("that was too long for me")
            val bytes = ByteArrayOutputStream()
            val sink = object : TokenSink {
                override fun onToken(bytes0: ByteArray): Boolean { bytes.write(bytes0); return bytes.size() < 1500 }
                override fun isCancelled(): Boolean = false
            }
            val n = Native.generate(h, roles.toTypedArray(), contents.toTypedArray(), maxTok, cfg.optDouble("temp", 0.7).toFloat(), sink)
            if (n < 0) throw IllegalStateException("I could not work out an answer")
            return clean(String(bytes.toByteArray(), Charsets.UTF_8))
        }
    }

    fun clean(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace(Regex("[*#`_>]+"), "").replace(Regex("\\s+"), " ").trim()
        .ifEmpty { "I have nothing to say to that." }
}

/** Small text-only calls to the online AIs, with the person's own key. */
object CloudText {
    fun ask(provider: String, key: String, model: String, system: String, hist: List<Pair<String, String>>, maxTok: Int): String {
        if (key.isBlank()) throw IllegalStateException("there is no key for the online AI")
        val roles = ArrayList<String>()
        val texts = ArrayList<String>()
        for ((r, t) in hist) {
            if (roles.isNotEmpty() && roles.last() == r) texts[texts.size - 1] = texts.last() + "\n\n" + t
            else { roles.add(r); texts.add(t) }
        }
        if (roles.isEmpty() || roles[0] != "user") throw IllegalStateException("there was nothing to ask")
        val url: String
        val body = JSONObject()
        val headers = HashMap<String, String>()
        when (provider) {
            "gemini" -> {
                url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                headers["x-goog-api-key"] = key
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                val contents = JSONArray()
                for (i in roles.indices) contents.put(JSONObject().put("role", if (roles[i] == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", texts[i]))))
                body.put("contents", contents).put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTok, 1024)))
            }
            "claude" -> {
                url = "https://api.anthropic.com/v1/messages"
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
                body.put("model", model).put("max_tokens", maxTok).put("system", system)
                val arr = JSONArray()
                for (i in roles.indices) arr.put(JSONObject().put("role", roles[i]).put("content", texts[i]))
                body.put("messages", arr)
            }
            "grok" -> {
                url = "https://api.x.ai/v1/chat/completions"
                headers["Authorization"] = "Bearer $key"
                body.put("model", model).put("max_tokens", maxTok)
                val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                for (i in roles.indices) arr.put(JSONObject().put("role", roles[i]).put("content", texts[i]))
                body.put("messages", arr)
            }
            else -> throw IllegalStateException("unknown online service")
        }
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("the online AI said error $code")
            val j = JSONObject(text)
            val sb = StringBuilder()
            when (provider) {
                "gemini" -> {
                    val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) for (i in 0 until parts.length()) {
                        val pt = parts.getJSONObject(i)
                        if (!pt.optBoolean("thought", false)) sb.append(pt.optString("text"))
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
            val out = HeadlessAssistant.clean(sb.toString())
            if (sb.isBlank()) throw IllegalStateException("the online AI sent an empty answer")
            return out
        } finally {
            c.disconnect()
        }
    }
}

/** Weather for the voice assistant: Open-Meteo, with a saved copy for when there is no internet. */
object VoiceWeather {
    private val codes = mapOf(0 to "clear sky", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "fog", 51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain", 71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains",
        80 to "light showers", 81 to "showers", 82 to "heavy showers", 85 to "snow showers", 86 to "snow showers", 95 to "thunderstorm", 96 to "thunderstorm with hail", 99 to "thunderstorm with hail")

    private fun get(url: String): String? = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "MobileAIStudio/0.2")
        val code = c.responseCode
        val t = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        t
    } catch (e: Exception) {
        null
    }

    fun report(ctx: Context, place: String, city: String): String {
        val prefs = ctx.getSharedPreferences("mai", Context.MODE_PRIVATE)
        var lat = 0.0
        var lon = 0.0
        var label = ""
        var key = ""
        var have = false
        var p = place.trim()
        if (p.isEmpty()) {
            try {
                if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    var best: android.location.Location? = null
                    for (pr in lm.getProviders(true)) {
                        val l = try { lm.getLastKnownLocation(pr) } catch (e: SecurityException) { null } ?: continue
                        if (best == null || l.time > best.time) best = l
                    }
                    if (best != null) { lat = best.latitude; lon = best.longitude; label = "your location"; key = "here"; have = true }
                }
            } catch (e: Throwable) {
            }
            if (!have) p = city.trim()
        }
        if (!have) {
            if (p.isEmpty()) return "You do not know where the person is. Ask which city they mean."
            key = p.lowercase()
            val g = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + java.net.URLEncoder.encode(p, "UTF-8"))
            if (g != null) {
                val r = try { JSONObject(g).optJSONArray("results")?.optJSONObject(0) } catch (e: Exception) { null }
                    ?: return "You could not find a place called $p. Say so."
                lat = r.optDouble("latitude"); lon = r.optDouble("longitude"); label = r.optString("name"); have = true
            }
        }
        var data: JSONObject? = null
        var savedAt = 0L
        if (have) {
            val f = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3")
            if (f != null) {
                try {
                    data = JSONObject(f)
                    savedAt = System.currentTimeMillis()
                    prefs.edit().putString("vwx_$key", JSONObject().put("at", savedAt).put("label", label).put("data", f).toString()).apply()
                } catch (e: Exception) {
                    data = null
                }
            }
        }
        var stale = false
        if (data == null) {
            val s = prefs.getString("vwx_$key", null) ?: return "There is no internet and no saved forecast. Tell the person you cannot give a forecast until they are online."
            try {
                val o = JSONObject(s)
                data = JSONObject(o.getString("data")); savedAt = o.getLong("at"); label = o.getString("label"); stale = true
            } catch (e: Exception) {
                return "There is no internet and no saved forecast. Tell the person you cannot give a forecast until they are online."
            }
        }
        val d = data ?: return "No forecast available."
        val sb = StringBuilder()
        sb.append("Weather for ").append(label)
        if (stale) sb.append(" (NO INTERNET: saved forecast from ").append(((System.currentTimeMillis() - savedAt) / 3_600_000L).coerceAtLeast(0)).append(" hours ago, may be out of date)")
        sb.append(".\n")
        val cu = d.optJSONObject("current")
        if (cu != null && !stale) sb.append("Now: ").append(Math.round(cu.optDouble("temperature_2m"))).append(" degrees Celsius, ").append(codes[cu.optInt("weather_code")] ?: "unknown")
            .append(", wind ").append(Math.round(cu.optDouble("wind_speed_10m"))).append(" kilometres per hour.\n")
        val dl = d.optJSONObject("daily")
        if (dl != null) {
            val times = dl.optJSONArray("time")
            val names = arrayOf("Today", "Tomorrow", "The day after")
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            var shown = 0
            for (i in 0 until (times?.length() ?: 0)) {
                if (times!!.getString(i) < today || shown >= 2) continue
                sb.append(names.getOrElse(shown) { "Later" }).append(": ").append(Math.round(dl.getJSONArray("temperature_2m_min").getDouble(i))).append(" to ")
                    .append(Math.round(dl.getJSONArray("temperature_2m_max").getDouble(i))).append(" degrees, ").append(codes[dl.getJSONArray("weather_code").optInt(i)] ?: "")
                    .append(", chance of rain ").append(dl.getJSONArray("precipitation_probability_max").optInt(i)).append(" percent.\n")
                shown++
            }
        }
        return sb.toString()
    }
}
