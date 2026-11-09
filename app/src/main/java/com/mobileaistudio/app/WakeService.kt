package com.mobileaistudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File

/** Shared switches between the wake-word service and the screen. */
object WakeCenter {
    @Volatile var onWake: (() -> Unit)? = null
    @Volatile var visible = false      // the app is on screen
    @Volatile var paused = false       // the microphone is needed for something else
    @Volatile var running = false
    @Volatile var spkLoaded = false    // the voice-match model is in use by the service
    @Volatile var lastDist = -2.0      // how far the last heard wake phrase was from the saved voice (-2 none, -1 could not tell)
    @Volatile var lastConf = -1.0      // how sure the recogniser was about the last wake phrase
    @Volatile var enrollLeft = 0
    @Volatile var onEnroll: ((Int, Int) -> Unit)? = null
    val samples = ArrayList<FloatArray>()
    const val ENROLL_TOTAL = 5
    const val MIN_CONF = 0.8

    fun spkDir(ctx: Context) = File(ctx.filesDir, "vosk/spk")
    fun spkModelReady(ctx: Context) = File(spkDir(ctx), "final.ext.raw").exists()

    fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk/model")
    fun modelReady(ctx: Context) = File(modelDir(ctx), "am").isDirectory
}

/** Listens for the wake phrase in the background. Everything stays on the phone (offline Vosk, a tiny word list). */
class WakeService : Service() {
    @Volatile private var run = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("mai", Context.MODE_PRIVATE)
        if (intent == null && !prefs.getBoolean("wakeOn", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = (prefs.getString("wakeName", "Ajax") ?: "Ajax").lowercase().replace(Regex("[^a-z ]"), "").trim().ifEmpty { "ajax" }
        val phrase = (intent?.getStringExtra("phrase") ?: ("hey $name")).lowercase().replace(Regex("[^a-z ]"), "").trim()
        try {
            val n = ongoing(phrase)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread == null && phrase.isNotEmpty()) {
            run = true
            WakeCenter.running = true
            thread = Thread { listenLoop(phrase) }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        run = false
        WakeCenter.running = false
        super.onDestroy()
    }

    private fun listenLoop(phrase: String) {
        var model: Model? = null
        var spk: SpeakerModel? = null
        var rec: Recognizer? = null
        var ar: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(WakeCenter.modelDir(this).absolutePath)
            rec = Recognizer(model, 16000f, "[\"$phrase\", \"[unk]\"]")
            rec.setWords(true)
            try {
                if (WakeCenter.spkModelReady(this)) {
                    spk = SpeakerModel(WakeCenter.spkDir(this).absolutePath)
                    rec.setSpeakerModel(spk)
                    WakeCenter.spkLoaded = true
                }
            } catch (e: Throwable) {
                WakeCenter.spkLoaded = false
            }
            val prefs = getSharedPreferences("mai", Context.MODE_PRIVATE)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val buf = ShortArray(4000)
            var last = 0L
            while (run) {
                if (WakeCenter.paused) {
                    ar?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
                    ar = null
                    Thread.sleep(200)
                    continue
                }
                if (ar == null) {
                    ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000))
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        ar.release(); ar = null
                        Thread.sleep(1000)
                        continue
                    }
                    ar.startRecording()
                    rec.reset()
                }
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(50); continue }
                // only a finished phrase counts (half-heard words used to set it off by mistake)
                if (!rec.acceptWaveForm(buf, n)) continue
                val j = JSONObject(rec.result)
                if (!j.optString("text").contains(phrase)) continue
                if (System.currentTimeMillis() - last < 3000) continue
                val words = phrase.split(" ")
                var sum = 0.0
                var cnt = 0
                val arr = j.optJSONArray("result")
                if (arr != null) for (k in 0 until arr.length()) {
                    val w = arr.getJSONObject(k)
                    if (w.optString("word") in words) { sum += w.optDouble("conf", 1.0); cnt++ }
                }
                val conf = if (cnt > 0) sum / cnt else 1.0
                WakeCenter.lastConf = conf
                if (conf < WakeCenter.MIN_CONF) continue
                val vec = j.optJSONArray("spk")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                if (WakeCenter.enrollLeft > 0) {
                    if (vec != null) {
                        WakeCenter.samples.add(vec)
                        WakeCenter.enrollLeft--
                        if (WakeCenter.enrollLeft == 0) saveVoice(prefs)
                        WakeCenter.onEnroll?.let { cb -> Handler(Looper.getMainLooper()).post { cb(WakeCenter.ENROLL_TOTAL - WakeCenter.enrollLeft, WakeCenter.ENROLL_TOTAL) } }
                    }
                    last = System.currentTimeMillis()
                    continue
                }
                val saved = loadVoice(prefs)
                if (prefs.getBoolean("spkOn", false) && saved != null) {
                    if (vec == null) { WakeCenter.lastDist = -1.0; continue }
                    val d = distance(saved, vec)
                    WakeCenter.lastDist = d
                    if (d > prefs.getFloat("spkLevel", 0.6f)) continue
                }
                last = System.currentTimeMillis()
                rec.reset()
                heard()
            }
        } catch (e: Throwable) {
            Handler(Looper.getMainLooper()).post { stopSelf() }
        } finally {
            try { ar?.stop(); ar?.release() } catch (e: Exception) {}
            try { rec?.close() } catch (e: Throwable) {}
            try { spk?.close() } catch (e: Throwable) {}
            try { model?.close() } catch (e: Throwable) {}
            WakeCenter.spkLoaded = false
            WakeCenter.running = false
        }
    }

    private fun saveVoice(prefs: android.content.SharedPreferences) {
        val list = WakeCenter.samples
        if (list.isEmpty()) return
        val n = list[0].size
        val avg = FloatArray(n)
        for (v in list) if (v.size == n) for (i in 0 until n) avg[i] += v[i] / list.size
        prefs.edit().putString("spkVec", avg.joinToString(",")).putBoolean("spkOn", true).apply()
        list.clear()
    }

    private fun loadVoice(prefs: android.content.SharedPreferences): FloatArray? = try {
        val s = prefs.getString("spkVec", "") ?: ""
        if (s.isEmpty()) null else s.split(",").map { it.toFloat() }.toFloatArray()
    } catch (e: Exception) {
        null
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 2.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 2.0 else 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun heard() {
        val main = Handler(Looper.getMainLooper())
        if (WakeCenter.visible && WakeCenter.onWake != null) {
            main.post { WakeCenter.onWake?.invoke() }
            return
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val locked = km.isKeyguardLocked || !pm.isInteractive
        val lockWake = getSharedPreferences("mai", Context.MODE_PRIVATE).getBoolean("lockWake", true)
        // On a locked or sleeping phone: light the screen and show the app. Unlocked with the app closed: answer by voice only.
        if (!(locked && lockWake) && HeadlessAssistant.configured(this)) {
            main.post { HeadlessAssistant.start(this) }
            return
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("wake", true)
        try {
            startActivity(open)   // works when the phone allows it; otherwise the notification below is the way in
        } catch (e: Exception) {
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ALERT) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Wake word heard", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(this, 5, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH_ALERT) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("I heard you").setContentText("Tap to start talking").setAutoCancel(true).setContentIntent(pi)
            .setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true)
        try { nm.notify(ALERT_ID, b.build()) } catch (e: Exception) {}
    }

    private fun ongoing(phrase: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 7, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Listening for \"$phrase\"").setContentText("Everything stays on this phone.")
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(0, "Turn off", stop).build())
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "com.mobileaistudio.app.WAKE_STOP"
        private const val NOTIF_ID = 42
        private const val ALERT_ID = 43
        private const val CHANNEL = "wake"
        private const val CH_ALERT = "wake_alert"
    }
}
