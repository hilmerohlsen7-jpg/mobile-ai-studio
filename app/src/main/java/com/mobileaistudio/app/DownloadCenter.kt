package com.mobileaistudio.app

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mobileaistudio.app.dl.DownloadItem
import com.mobileaistudio.app.dl.DownloadManager
import java.io.File

/**
 * One download manager for the whole app process. Downloads keep running when the
 * screen is closed because DownloadService holds the process while anything is active.
 */
object DownloadCenter {
    private lateinit var app: Context
    private lateinit var prefs: android.content.SharedPreferences
    lateinit var manager: DownloadManager
        private set
    lateinit var modelsDir: File
        private set
    lateinit var filesDir: File
        private set

    @Volatile
    var onChange: (() -> Unit)? = null

    @Volatile
    var active = false
        private set

    @Synchronized
    fun init(context: Context) {
        if (::manager.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        modelsDir = File(app.filesDir, "models").apply { mkdirs() }
        filesDir = (app.getExternalFilesDir("downloads") ?: File(app.filesDir, "downloads")).apply { mkdirs() }
        manager = DownloadManager(
            File(app.filesDir, "downloads-state"),
            object : DownloadManager.Listener {
                override fun onChanged() {
                    onChange?.invoke()
                    if (active) DownloadService.refresh(app)
                }

                override fun onActive(active: Boolean) {
                    this@DownloadCenter.active = active
                    if (active) startService() else app.stopService(Intent(app, DownloadService::class.java))
                }
            },
            DownloadManager.Check { item, file ->
                if (item.kind == "model" && !Catalog.looksLikeGguf(file)) "That file is not a valid .gguf model." else null
            }
        )
        manager.setMax(prefs.getInt("max", 2))
        manager.setDefaultThreads(prefs.getInt("threads", 4))
    }

    private fun startService() {
        try {
            val i = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            // Not allowed to start in the background: downloads still run while the app is open.
        }
    }

    fun saveSettings(max: Int, threads: Int) {
        manager.setMax(max)
        manager.setDefaultThreads(threads)
        prefs.edit().putInt("max", manager.getMax()).putInt("threads", manager.getDefaultThreads()).apply()
    }

    fun summary(): Triple<Int, Long, Int> {
        // running count, total speed, overall percent
        var run = 0
        var speed = 0L
        var done = 0L
        var total = 0L
        for (d in manager.list()) {
            if (d.state == DownloadItem.State.DOWNLOADING) {
                run++
                speed += d.speed
                done += d.done.get()
                if (d.total > 0) total += d.total else total += d.done.get()
            }
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return Triple(run, speed, pct)
    }
}
