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
import java.io.File

/** Shared switches between the wake-word service and the screen. */
object WakeCenter {
    @Volatile var onWake: (() -> Unit)? = null
    @Volatile var visible = false      // the app is on screen
    @Volatile var paused = false       // the microphone is needed for something else
    @Volatile var running = false

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
        val phrase = (intent?.getStringExtra("phrase") ?: "hey alex").lowercase().replace(Regex("[^a-z ]"), "").trim()
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        run = false
        WakeCenter.running = false
        super.onDestroy()
    }

    private fun listenLoop(phrase: String) {
        var model: Model? = null
        var rec: Recognizer? = null
        var ar: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(WakeCenter.modelDir(this).absolutePath)
            rec = Recognizer(model, 16000f, "[\"$phrase\", \"[unk]\"]")
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
                val text = if (rec.acceptWaveForm(buf, n)) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
                if (text.contains(phrase) && System.currentTimeMillis() - last > 3000) {
                    last = System.currentTimeMillis()
                    rec.reset()
                    heard()
                }
            }
        } catch (e: Throwable) {
            Handler(Looper.getMainLooper()).post { stopSelf() }
        } finally {
            try { ar?.stop(); ar?.release() } catch (e: Exception) {}
            try { rec?.close() } catch (e: Throwable) {}
            try { model?.close() } catch (e: Throwable) {}
            WakeCenter.running = false
        }
    }

    private fun heard() {
        val main = Handler(Looper.getMainLooper())
        if (WakeCenter.visible && WakeCenter.onWake != null) {
            main.post { WakeCenter.onWake?.invoke() }
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
