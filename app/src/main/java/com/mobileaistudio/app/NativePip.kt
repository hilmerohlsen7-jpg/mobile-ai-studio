package com.mobileaistudio.app

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/** A small native video player that fills its parent: used for the floating (picture-in-picture) window. */
class NativePip(ctx: Context, private val onFail: () -> Unit) : FrameLayout(ctx), SurfaceHolder.Callback {
    private val sv = SurfaceView(ctx)
    private var mp: MediaPlayer? = null
    private var url = ""
    private var startMs = 0

    init {
        setBackgroundColor(Color.BLACK); isClickable = true
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        sv.holder.addCallback(this)
    }

    fun start(u: String, sec: Double) {
        url = u; startMs = (sec * 1000).toInt()
        if (sv.holder.surface.isValid) begin()
    }

    override fun surfaceCreated(h: SurfaceHolder) { if (url.isNotEmpty() && mp == null) begin() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}

    private fun begin() {
        try {
            val m = MediaPlayer(); mp = m
            m.setSurface(sv.holder.surface)
            m.setDataSource(context, Uri.parse(url), mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"))
            m.setOnPreparedListener { it.seekTo(startMs); it.start() }
            m.setOnVideoSizeChangedListener { _, w, h -> fit(w, h) }
            m.setOnErrorListener { _, _, _ -> post { onFail() }; true }
            m.prepareAsync()
        } catch (e: Throwable) { post { onFail() } }
    }

    private fun fit(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        post {
            val pw = width; val ph = height; if (pw <= 0 || ph <= 0) return@post
            val s = minOf(pw.toFloat() / w, ph.toFloat() / h)
            sv.layoutParams = LayoutParams((w * s).toInt(), (h * s).toInt(), Gravity.CENTER)
        }
    }

    fun position(): Int = try { mp?.currentPosition ?: 0 } catch (e: Throwable) { 0 }

    fun stop() {
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
    }
}
