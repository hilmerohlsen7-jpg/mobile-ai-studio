package com.mobileaistudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mobileaistudio.app.dl.DownloadItem

/** Keeps the app alive in the background while files are downloading and shows a notification. */
class DownloadService : Service() {
    private var lastShown = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadCenter.init(this)
        if (intent?.action == ACTION_PAUSE_ALL) DownloadCenter.manager.pauseAll()
        val n = build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val (run, speed, pct) = DownloadCenter.summary()
        val queued = DownloadCenter.manager.list().count { it.state == DownloadItem.State.QUEUED }
        val title = when {
            run == 0 && queued > 0 -> "Starting downloads"
            run == 1 -> "Downloading 1 file"
            else -> "Downloading $run files"
        }
        val text = buildString {
            append(pct).append("%")
            if (speed > 0) append(" · ").append(fmtSpeed(speed))
            if (queued > 0) append(" · ").append(queued).append(" waiting")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, pct, run == 0)
            .addAction(Notification.Action.Builder(0, "Pause all", pause).build())
        return b.build()
    }

    private fun update() {
        val now = System.currentTimeMillis()
        if (now - lastShown < 900) return
        lastShown = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID, build())
        } catch (e: Exception) {
        }
    }

    private fun fmtSpeed(bps: Long): String =
        if (bps >= 1048576) String.format("%.1f MB/s", bps / 1048576.0) else String.format("%d KB/s", bps / 1024)

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val ACTION_PAUSE_ALL = "pause_all"

        @Volatile
        private var instance: DownloadService? = null

        fun refresh(context: Context) {
            instance?.update()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
