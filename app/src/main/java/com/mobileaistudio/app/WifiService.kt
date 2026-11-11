package com.mobileaistudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps the Wi-Fi file server alive while the screen is off, with a notification and a Stop button. */
class WifiService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WifiServer.stop(); release(); stopForeground(true); stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wi-Fi file server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, WifiService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Sharing files over Wi-Fi")
            .setContentText(WifiServer.url + "  PIN " + WifiServer.pin).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop sharing", stop).build())
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, b.build())
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        try {
            if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobileai:wifiserver").also { it.acquire(6 * 60 * 60 * 1000L) }
            if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mobileai:wifiserver").also { it.acquire() }
        } catch (e: Exception) {}
        return START_NOT_STICKY
    }

    private fun release() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        try { wifi?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        wake = null; wifi = null
    }

    override fun onDestroy() { release(); super.onDestroy() }

    companion object { const val CHANNEL = "wifiserver"; const val NOTIF_ID = 4417; const val ACTION_STOP = "stop" }
}
