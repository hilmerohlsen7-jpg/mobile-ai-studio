package com.mobileaistudio.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

/** Building blocks for the browser's full-screen pages (Settings, History, Extensions ...), laid out like Firefox for Android. */
class Fx(private val a: BrowserActivity) {
    val th: BTheme get() = a.th
    fun dp(v: Int) = a.dp(v)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun tv(t: String, size: Float = 16f, color: Int = th.txt, bold: Boolean = false): TextView {
        val v = TextView(a); v.text = t; v.textSize = size; v.setTextColor(color); if (bold) v.setTypeface(null, Typeface.BOLD); return v
    }

    fun round(color: Int, radius: Int): GradientDrawable = GradientDrawable().also { it.setColor(color); it.cornerRadius = dp(radius).toFloat() }

    fun icon(res: Int, tint: Int = th.txt, size: Int = 46, f: (() -> Unit)? = null): ImageButton {
        val b = ImageButton(a); b.setImageResource(res); b.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        b.setBackgroundColor(Color.TRANSPARENT); b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.setPadding(dp(11), dp(11), dp(11), dp(11)); b.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        if (f != null) b.setOnClickListener { f() } else b.isClickable = false
        return b
    }

    // ---------- pages ----------
    /** A full-screen page: back arrow, title, optional action icons, then scrolling content. */
    fun page(title: String, actions: List<Pair<Int, () -> Unit>> = emptyList(), scroll: Boolean = true, build: (LinearLayout) -> Unit): View {
        val root = LinearLayout(a); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(th.bg); root.isClickable = true
        val bar = LinearLayout(a); bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), a.inTop + dp(6), dp(6), dp(6))
        bar.addView(icon(R.drawable.bt_back) { a.popPage() })
        val t = tv(title, 21f); t.setPadding(dp(10), 0, dp(6), 0); t.setSingleLine(); t.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.forEach { (res, f) -> bar.addView(icon(res, th.txt, 46, f)) }
        root.addView(bar)
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL
        build(body)
        if (scroll) root.addView(ScrollView(a).also { it.addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        else root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    fun header(p: LinearLayout, text: String) {
        val h = tv(text, 14f, th.txt, true); h.setPadding(dp(16), dp(18), dp(16), dp(6)); p.addView(h)
    }

    fun divider(p: LinearLayout) {
        val d = View(a); d.setBackgroundColor(th.line); p.addView(d, LinearLayout.LayoutParams(MATCH, dp(1)).also { it.topMargin = dp(6) })
    }

    /** A two-line settings row: title, grey sub-line, optional vector icon in front. */
    fun row(p: LinearLayout, title: String, sub: String? = null, ico: Int? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(13), dp(16), dp(13))
        if (ico != null) {
            val i = ImageView(a); i.setImageResource(ico); i.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN)
            r.addView(i, LinearLayout.LayoutParams(dp(24), dp(24)).also { it.rightMargin = dp(16) })
        }
        val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(title, 16f))
        if (!sub.isNullOrBlank()) col.addView(tv(sub, 14f, th.mute).also { it.setPadding(0, dp(2), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (onClick != null) { r.setOnClickListener { onClick() }; r.isClickable = true }
        p.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return r
    }

    private fun tintStates(on: Int, off: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    fun switchRow(p: LinearLayout, title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16), dp(11), dp(16), dp(11))
        val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(title, 16f))
        if (!sub.isNullOrBlank()) col.addView(tv(sub, 14f, th.mute).also { it.setPadding(0, dp(2), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val s = Switch(a); s.isChecked = checked
        s.thumbTintList = tintStates(th.onacc, th.mute); s.trackTintList = tintStates(th.acc, th.line)
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.addView(s)
        r.setOnClickListener { s.toggle() }
        p.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return s
    }

    fun checkRow(p: LinearLayout, title: String, checked: Boolean, indent: Int = 0, onChange: (Boolean) -> Unit): CheckBox {
        val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16 + indent), dp(4), dp(16), dp(4))
        val c = CheckBox(a); c.isChecked = checked; c.buttonTintList = tintStates(th.acc, th.mute)
        c.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = dp(10) })
        r.addView(tv(title, 16f), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.setOnClickListener { c.toggle() }
        p.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    /** A group of radio rows; picking one calls onPick(index) and redraws the group. */
    fun radios(p: LinearLayout, options: List<Pair<String, String?>>, selected: Int, onPick: (Int) -> Unit) {
        val holder = LinearLayout(a); holder.orientation = LinearLayout.VERTICAL; p.addView(holder, LinearLayout.LayoutParams(MATCH, WRAP))
        fun draw(sel: Int) {
            holder.removeAllViews()
            options.forEachIndexed { i, (t, sub) ->
                val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16), dp(10), dp(16), dp(10))
                val rb = RadioButton(a); rb.isChecked = i == sel; rb.isClickable = false; rb.isFocusable = false; rb.buttonTintList = tintStates(th.acc, th.mute)
                r.addView(rb, LinearLayout.LayoutParams(WRAP, WRAP).also { it.rightMargin = dp(12) })
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
                col.addView(tv(t, 16f))
                if (!sub.isNullOrBlank()) col.addView(tv(sub, 14f, th.mute).also { it.setPadding(0, dp(2), 0, 0) })
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.setOnClickListener { draw(i); onPick(i) }
                holder.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        draw(selected)
    }

    fun bigButton(p: LinearLayout, text: String, filled: Boolean = true, onClick: () -> Unit): TextView {
        val b = tv(text, 16f, if (filled) th.onacc else th.acc, true); b.gravity = Gravity.CENTER
        b.background = if (filled) round(th.acc, 26) else GradientDrawable().also { it.setColor(Color.TRANSPARENT); it.setStroke(dp(1), th.mute); it.cornerRadius = dp(26).toFloat() }
        b.setPadding(dp(16), dp(14), dp(16), dp(14)); b.setOnClickListener { onClick() }
        p.addView(b, LinearLayout.LayoutParams(MATCH, WRAP).also { it.setMargins(dp(16), dp(10), dp(16), dp(6)) })
        return b
    }

    /** Centred empty-state: a big vector in an accent circle, a title and a hint. */
    fun empty(p: LinearLayout, ico: Int, title: String, hint: String? = null) {
        val c = LinearLayout(a); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER_HORIZONTAL; c.setPadding(dp(24), dp(80), dp(24), dp(24))
        val i = ImageView(a); i.setImageResource(ico); i.setColorFilter(th.acc, PorterDuff.Mode.SRC_IN); i.background = round(th.side, 60); i.setPadding(dp(28), dp(28), dp(28), dp(28))
        c.addView(i, LinearLayout.LayoutParams(dp(120), dp(120)))
        val t = tv(title, 20f, th.txt, true); t.gravity = Gravity.CENTER; t.setPadding(0, dp(22), 0, 0); c.addView(t)
        if (hint != null) { val h = tv(hint, 15f, th.mute); h.gravity = Gravity.CENTER; h.setPadding(0, dp(8), 0, 0); c.addView(h) }
        p.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** Square letter badge used in place of site icons. */
    fun badge(text: String, size: Int = 40, seed: String = text): TextView {
        val colors = intArrayOf(0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFF8E24AA.toInt(), 0xFFF4511E.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(), 0xFF039BE5.toInt())
        val v = tv(text.take(1).uppercase().ifBlank { "?" }, (size / 2.2f), Color.WHITE, false); v.gravity = Gravity.CENTER
        v.background = round(colors[(seed.hashCode() and 0x7fffffff) % colors.size], 4)
        v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return v
    }

    // ---------- small helpers ----------
    private val cache = LruCache<String, Bitmap>(60)
    private val pool = Executors.newFixedThreadPool(3)

    /** Loads a picture from a web address into an ImageView in the background. */
    fun load(url: String, into: ImageView) {
        into.tag = url
        cache.get(url)?.let { into.setImageBitmap(it); return }
        pool.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val bmp = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@execute
                cache.put(url, bmp)
                into.post { if (into.tag == url) into.setImageBitmap(bmp) }
            } catch (_: Throwable) {}
        }
    }

    /** Runs fn about once a second while the view is on screen. */
    fun tick(v: View, fn: () -> Unit) {
        val r = object : Runnable { override fun run() { if (!v.isAttachedToWindow) return; fn(); a.main.postDelayed(this, 1000) } }
        a.main.postDelayed(r, 1000)
    }

    fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
}
