package com.mobileaistudio.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager as AndroidDownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*

/** The built-in Firefox-engine browser (GeckoView) with tabs, add-ons and the app's downloader. */
object Gk {
    var runtime: GeckoRuntime? = null
    fun get(a: Activity): GeckoRuntime {
        runtime?.let { return it }
        // light on memory: one content process instead of one per site
        val st = GeckoRuntimeSettings.Builder().fissionEnabled(false).remoteDebuggingEnabled(false).consoleOutput(false).build()
        val r = GeckoRuntime.create(a.applicationContext, st)
        runtime = r
        return r
    }
}

class Tab(val session: GeckoSession) {
    var url = ""
    var title = ""
    var back = false
    var fwd = false
    var home = false
    var priv = false
    var thumb: android.graphics.Bitmap? = null
}

/** Dialog builder that colours its buttons with the app's accent. */
class TB(ctx: Context, style: Int, private val acc: Int) : AlertDialog.Builder(ctx, style) {
    override fun create(): AlertDialog {
        val d = super.create()
        d.setOnShowListener {
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { d.getButton(it)?.setTextColor(acc) }
        }
        return d
    }
}

class BrowserActivity : Activity() {
    internal val tabs = ArrayList<Tab>()
    internal var cur: Tab? = null
    private lateinit var gv: GeckoView
    private lateinit var urlBox: EditText
    private lateinit var prog: ProgressBar
    internal lateinit var rt: GeckoRuntime
    private lateinit var topBar: LinearLayout
    private lateinit var frame: FrameLayout
    private var pad: PadOverlay? = null
    private var dp = 1f
    internal lateinit var th: BTheme
    private lateinit var dc: Context
    internal val main = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dp = resources.displayMetrics.density
        th = BTheme.load(this, getSharedPreferences("browser", Context.MODE_PRIVATE).getInt("theme", 0))
        dc = ContextThemeWrapper(this, dlgStyle())
        rt = Gk.get(this)
        pages.applyAll()
        if (prefs.getBoolean("gfxfix", false)) rt.webExtensionController.ensureBuiltIn(GFX_URI, GFX_ID)
        rt.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(ext: WebExtension, perms: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val res = GeckoResult<WebExtension.PermissionPromptResponse>()
                runOnUiThread {
                    val all = (perms.toList() + origins.toList()).joinToString("\n• ", prefix = "• ").ifBlank { "No special permissions" }
                    bd().setTitle("Add " + (ext.metaData.name ?: "this add-on") + "?")
                        .setMessage("It will be allowed to:\n$all")
                        .setPositiveButton("Add") { _, _ -> res.complete(WebExtension.PermissionPromptResponse(true, false, true)) }
                        .setNegativeButton("Cancel") { _, _ -> res.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                        .setOnCancelListener { res.complete(WebExtension.PermissionPromptResponse(false, false, false)) }.show()
                }
                return res
            }
        })
        window.statusBarColor = th.bg; window.navigationBarColor = th.bg
        baseUi()
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(th.bg)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(6), dp(4), dp(6), dp(4))
        top.addView(ibtn(R.drawable.bt_back) { cur?.let { if (it.back) it.session.goBack() else finish() } })
        urlBox = EditText(this)
        urlBox.setSingleLine(); urlBox.hint = "Search or type an address"; urlBox.setTextColor(th.txt); urlBox.setHintTextColor(th.mute); urlBox.textSize = 15f
        urlBox.inputType = InputType.TYPE_TEXT_VARIATION_URI; urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.background = GradientDrawable().also { it.setColor(th.side); it.cornerRadius = dp(10).toFloat() }
        urlBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        urlBox.setOnEditorActionListener { _, _, _ -> go(urlBox.text.toString()); true }
        urlBox.setOnFocusChangeListener { _, f -> if (f) { urlBox.post { urlBox.selectAll() }; showSug("") } else hideSug() }
        urlBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { if (urlBox.hasFocus()) showSug(e?.toString() ?: "") }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        top.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        micBtn = ibtn(R.drawable.bt_mic) {
            try { startActivityForResult(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 7303) }
            catch (e: Throwable) { Toast.makeText(this, "Voice search is not available on this phone.", Toast.LENGTH_SHORT).show() }
        }
        top.addView(micBtn)
        refreshBar()
        tabBtn = TextView(this); tabBtn.gravity = Gravity.CENTER; tabBtn.setTextColor(th.txt); tabBtn.textSize = 12f; tabBtn.setTypeface(null, Typeface.BOLD)
        tabBtn.background = GradientDrawable().also { it.setColor(Color.TRANSPARENT); it.setStroke(dp(2), th.txt); it.cornerRadius = dp(6).toFloat() }
        tabBtn.setOnClickListener { tabTray() }
        top.addView(tabBtn, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.leftMargin = dp(8); it.rightMargin = dp(8) })
        moreBtn = ibtn(R.drawable.bt_more) { pages.menu() }; top.addView(moreBtn)
        topBar = top
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); prog.max = 100; prog.visibility = View.GONE
        prog.progressDrawable.setColorFilter(th.acc, PorterDuff.Mode.SRC_IN)
        gv = GeckoView(this)
        holder = FrameLayout(this)
        holder.addView(gv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        homeBox = FrameLayout(this); homeBox.setBackgroundColor(th.bg); homeBox.visibility = View.GONE
        holder.addView(homeBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootView = root
        placeBar()
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        // leave room for the status bar only when the system really draws under it
        frame.setOnApplyWindowInsetsListener { _, ins ->
            if (fs) { rootView.setPadding(0, 0, 0, 0); return@setOnApplyWindowInsetsListener ins }
            @Suppress("DEPRECATION") val t = ins.systemWindowInsetTop
            @Suppress("DEPRECATION") val bt = ins.systemWindowInsetBottom
            inTop = t
            if (barBottom()) {
                topBar.setPadding(dp(6), dp(4), dp(6), dp(4))
                root.setPadding(0, if (pad == null) t else 0, 0, if (pad == null) bt else 0)
            } else {
                topBar.setPadding(dp(6), t + dp(4), dp(6), dp(4))
                root.setPadding(0, if (topBar.visibility != View.VISIBLE && pad == null) t else 0, 0, if (pad == null) bt else 0)
            }
            (handle.layoutParams as FrameLayout.LayoutParams).topMargin = t + dp(6); handle.requestLayout()
            ins
        }
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        handle = ImageButton(this); handle.setImageResource(R.drawable.bt_down); handle.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN)
        handle.background = GradientDrawable().also { it.setColor(th.side); it.alpha = 190; it.cornerRadius = dp(18).toFloat() }
        handle.setPadding(dp(6), dp(6), dp(6), dp(6)); handle.visibility = View.GONE
        handle.setOnClickListener { setBarHidden(false) }
        frame.addView(handle, FrameLayout.LayoutParams(dp(40), dp(32), Gravity.TOP or Gravity.END).also { it.rightMargin = dp(8); it.topMargin = dp(6) })
        setContentView(frame)
        val first = intent?.dataString
        appMode = intent?.getBooleanExtra("app", false) == true
        if (appMode) {
            val nm = intent?.getStringExtra("name") ?: ""
            urlBox.visibility = View.GONE
            val tv = label(this, nm, bold = true, size = 16f); tv.setSingleLine(); tv.setPadding(dp(8), 0, dp(8), 0)
            top.addView(tv, 1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            try {
                val bmp = intent?.getStringExtra("icon")?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
                @Suppress("DEPRECATION") setTaskDescription(android.app.ActivityManager.TaskDescription(nm.ifBlank { "Web app" }, bmp))
            } catch (_: Throwable) {}
        }
        placeBar()
        noBar = appMode && intent?.getBooleanExtra("hidebar", false) == true
        barHidden = if (intent?.hasExtra("hidebar") == true) intent.getBooleanExtra("hidebar", false) else prefs.getBoolean(barKey(), false); applyBar()
        val saved = try { JSONArray(prefs.getString("restore", "[]")) } catch (e: Throwable) { JSONArray() }
        val openMode = prefs.getInt("opening", 1)
        val fresh = b == null && first.isNullOrBlank() && !appMode
        val stale = System.currentTimeMillis() - prefs.getLong("lastSeen", 0L) > 4 * 3600_000L
        if (!first.isNullOrBlank()) newTab(first)
        else if (saved.length() > 0 && (b != null || (fresh && (openMode == 1 || (openMode == 2 && !stale))))) {
            for (i in 0 until saved.length()) newTab(saved.getString(i))
        } else newTab("about:home")
        prefs.edit().remove("restore").apply()
    }


    // ---------- Firefox-style pages ----------
    internal val fx by lazy { Fx(this) }
    internal val pages by lazy { FxPages(this, fx) }
    internal var onRole: (() -> Unit)? = null
    private val pageStack = ArrayList<View>()
    internal fun pushPage(v: View) {
        pageStack.add(v); frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    internal fun popPage() { if (pageStack.isNotEmpty()) frame.removeView(pageStack.removeAt(pageStack.size - 1)) }
    private var fs = false
    /** Page (video) full screen: hides every bar and the system bars, turns the phone sideways like the YouTube app. */
    private fun setFs(on: Boolean) {
        fs = on
        if (on) {
            topBar.visibility = View.GONE; navBar?.visibility = View.GONE; handle.visibility = View.GONE; prog.visibility = View.GONE
            if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            rootView.setPadding(0, 0, 0, 0)
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            baseUi(); applyBar(); navBar?.visibility = View.VISIBLE
            frame.requestApplyInsets()
        }
    }
    /** Pressing Home while a video is full screen shrinks it into a small floating window, like the YouTube app. */
    private var pipping = false
    private var geckoFs = false
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) { pipping = false; setFs(geckoFs) }
        else if (fs) { topBar.visibility = View.GONE; navBar?.visibility = View.GONE; handle.visibility = View.GONE }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (fs && android.os.Build.VERSION.SDK_INT >= 26) try {
            pipping = true
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
        } catch (_: Throwable) {}
    }
    private fun popTop(): Boolean {
        if (fs) { cur?.session?.exitFullScreen(); return true }; if (pageStack.isEmpty()) return false; popPage(); return true }
    internal fun closeAllTabs() {
        val all = tabs.toList(); gv.releaseSession(); tabs.clear(); cur = null
        all.forEach { try { it.session.close() } catch (_: Throwable) {} }
        newTab("about:home")
    }
    internal fun quit() {
        val sel = pages.quitSet()
        if (sel.isNotEmpty()) pages.runDelete(sel) { finish() } else finish()
    }
    internal fun applySecure() {
        val on = cur?.priv == true && !prefs.getBoolean("privShots", false)
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    internal fun restyle() {
        val urls = tabs.filter { !it.priv && it.url.startsWith("http") }.map { it.url }
        prefs.edit().putString("restore", JSONArray(urls).toString()).apply()
        recreate()
    }
    internal fun refreshBar() { micBtn?.visibility = if (prefs.getBoolean("sg_voice", true)) View.VISIBLE else View.GONE }
    private var micBtn: ImageButton? = null

    /** Firefox-style tab tray: grid of cards, Normal / Private switch, new tab, close all. */
    /** Takes a small picture of the page showing in a tab, for the tab tray. */
    private fun snap(t: Tab?, then: () -> Unit) {
        if (t == null || t !== cur || t.home || !t.url.startsWith("http")) { then(); return }
        try {
            gv.capturePixels().accept({ bmp ->
                if (bmp != null) try { t.thumb = android.graphics.Bitmap.createScaledBitmap(bmp, 360, maxOf(1, bmp.height * 360 / bmp.width), true) } catch (_: Throwable) {}
                runOnUiThread { then() }
            }, { _ -> runOnUiThread { then() } })
        } catch (_: Throwable) { then() }
    }
    internal fun tabTray(startPrivate: Boolean = cur?.priv == true) { snap(cur) { tabTray2(startPrivate) } }
    private fun tabTray2(startPrivate: Boolean) {
        var showPriv = startPrivate
        lateinit var page: View
        val grid = prefs.getBoolean("tabGrid", true)
        val v = fx.page("Tabs", listOf(R.drawable.bt_trash to { tabs.filter { it.priv == showPriv }.toList().forEach { closeTab(it) }; popPage(); if (tabs.any { it.priv == showPriv }) tabTray(showPriv) })) { b ->
            val seg = LinearLayout(this); seg.orientation = LinearLayout.HORIZONTAL
            val body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL
            fun fill() {
                body.removeAllViews(); seg.removeAllViews()
                listOf("Normal" to false, "Private" to true).forEach { (n, p) ->
                    val c = label(this, n + " (" + tabs.count { it.priv == p } + ")", bold = showPriv == p, size = 14f); c.gravity = Gravity.CENTER; c.setPadding(0, dp(10), 0, dp(10))
                    c.setTextColor(if (showPriv == p) th.onacc else th.txt); c.background = roundBg(if (showPriv == p) th.acc else th.side, 18)
                    c.setOnClickListener { showPriv = p; fill() }
                    seg.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(4), 0, dp(4), 0) })
                }
                val list = tabs.filter { it.priv == showPriv }
                if (list.isEmpty()) body.addView(label(this, if (showPriv) "No private tabs" else "No open tabs", size = 15f).also { it.setTextColor(th.mute); it.gravity = Gravity.CENTER; it.setPadding(0, dp(40), 0, dp(40)) })
                val cols = if (grid) 2 else 1
                var rowL: LinearLayout? = null
                list.forEachIndexed { i, t ->
                    if (i % cols == 0) { rowL = LinearLayout(this); rowL!!.orientation = LinearLayout.HORIZONTAL; body.addView(rowL) }
                    val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL; card.setPadding(dp(12), dp(10), dp(6), dp(12))
                    card.background = GradientDrawable().also { it.setColor(th.side); it.cornerRadius = dp(14).toFloat(); if (t === cur) it.setStroke(dp(2), th.acc) }
                    val head = LinearLayout(this); head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
                    val nm = label(this, t.title.ifBlank { if (t.home) "Homepage" else t.url.ifBlank { "New tab" } }, bold = true, size = 13f); nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
                    head.addView(nm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val x = ibtn(R.drawable.bt_back) { closeTab(t); fill(); popPage(); if (tabs.isNotEmpty() && tabs.any { it.priv == showPriv }) tabTray(showPriv) }
                    x.rotation = 0f; x.visibility = View.GONE
                    val xb = label(this, "\u2715", bold = true, size = 15f); xb.setPadding(dp(10), dp(4), dp(10), dp(4))
                    xb.setOnClickListener { closeTab(t); popPage(); if (tabs.any { it.priv == showPriv }) tabTray(showPriv) }
                    head.addView(xb)
                    card.addView(head)
                    card.addView(label(this, if (t.home) "" else (Uri.parse(t.url).host ?: t.url), size = 12f).also { it.setTextColor(th.mute); it.maxLines = 1; it.setPadding(0, dp(2), 0, dp(8)) })
                    val th0 = t.thumb
                    if (th0 != null && !t.priv) { val iv = ImageView(this); iv.setImageBitmap(th0); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.adjustViewBounds = false; card.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)).also { it.rightMargin = dp(6) }) }
                    else card.addView(View(this), LinearLayout.LayoutParams(1, dp(60)))
                    card.setOnClickListener { select(t); popPage() }
                    rowL!!.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
                if (grid && list.size % 2 == 1) rowL?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            fill()
            b.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(12), dp(8), dp(12), dp(8)) })
            b.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(dp(8), 0, dp(8), dp(80)) })
        }
        val wrap = FrameLayout(this); wrap.addView(v)
        val plus = label(this, "+", bold = true, size = 28f); plus.gravity = Gravity.CENTER; plus.setTextColor(th.onacc); plus.background = GradientDrawable().also { it.setColor(th.acc); it.shape = GradientDrawable.OVAL }
        plus.setOnClickListener { popPage(); newTab("about:home", showPriv) }
        wrap.addView(plus, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(20), dp(24)) })
        page = wrap
        pushPage(wrap)
    }


    // ---------- address-bar suggestions ----------
    private var sugBox: LinearLayout? = null
    private var sugSeq = 0
    private var sugView: View? = null
    private fun hideSug() { sugView?.let { holder.removeView(it) }; sugView = null; sugBox = null; sugSeq++ }
    private fun showSug(q0: String) {
        val q = q0.trim()
        if (appMode || (q.isEmpty() && cur?.url?.startsWith("http") == true && urlBox.text.toString() == cur?.url)) { hideSug(); return }
        val items = ArrayList<Pair<String, String>>()   // text to show, address or search words
        fun local(key: String, on: Boolean) { if (!on || q.isEmpty()) return; val a = load(key); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if ((o.optString("t") + o.getString("u")).contains(q, true) && items.size < 5 && items.none { it.second == o.getString("u") }) items.add((o.optString("t").ifBlank { o.getString("u") }) to o.getString("u")) } }
        if (cur?.priv != true) { local("bm", prefs.getBoolean("sg_bm", true)); local("hist", prefs.getBoolean("sg_hist", true)) }
        val clip = try { if (q.isEmpty() && prefs.getBoolean("sg_clip", true)) (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() else null } catch (e: Throwable) { null }
        if (!clip.isNullOrBlank() && clip.length < 300) items.add("Clipboard: $clip" to clip)
        if (q.isEmpty() && prefs.getBoolean("recent_searches", true) && cur?.priv != true) { val r = load("recent"); for (i in 0 until minOf(r.length(), 4)) items.add(r.getString(i) to r.getString(i)) }
        fun draw(extra: List<String>) {
            hideSug()
            if (items.isEmpty() && extra.isEmpty()) return
            val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setBackgroundColor(th.bg); box.isClickable = true
            fun row(t: String, v: String) {
                val tv = label(this, t, size = 15f); tv.maxLines = 1; tv.ellipsize = android.text.TextUtils.TruncateAt.END; tv.setPadding(dp(16), dp(12), dp(16), dp(12))
                tv.setOnClickListener { urlBox.clearFocus(); go(v) }
                box.addView(tv)
            }
            items.forEach { row(it.first, it.second) }
            extra.forEach { row(it, it) }
            val sv = ScrollView(this).also { it.addView(box); it.setBackgroundColor(th.bg) }; sugView = sv
            holder.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            sugBox = box
        }
        draw(emptyList())
        val e = pages.engine()
        if (q.isNotEmpty() && e.sug.isNotEmpty() && prefs.getBoolean("sugg", true) && (cur?.priv != true || prefs.getBoolean("sugg_priv", false))) {
            val my = ++sugSeq
            Thread {
                val body = httpGet(e.sug + java.net.URLEncoder.encode(q, "UTF-8"), 100_000)?.toString(Charsets.UTF_8)
                val out = ArrayList<String>()
                try { val a = JSONArray(body).getJSONArray(1); for (i in 0 until minOf(a.length(), 6)) out.add(a.get(i).toString()) } catch (_: Throwable) {}
                runOnUiThread { if (my == sugSeq && urlBox.hasFocus()) draw(out) }
            }.start()
        }
    }

    // ---------- bottom navigation (expanded toolbar) ----------
    private var navBar: LinearLayout? = null
    private fun buildNav() {
        val n = LinearLayout(this); n.orientation = LinearLayout.HORIZONTAL; n.gravity = Gravity.CENTER_VERTICAL; n.setBackgroundColor(th.bg)
        fun cell(v: View) { n.addView(v, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        cell(ibtn(R.drawable.bt_back) { cur?.let { if (it.back) it.session.goBack() } })
        cell(ibtn(R.drawable.bt_forward) { cur?.let { if (it.fwd) it.session.goForward() } })
        cell(ibtn(R.drawable.bt_reload) { cur?.session?.reload() })
        val c = TextView(this); c.gravity = Gravity.CENTER; c.setTextColor(th.txt); c.textSize = 12f; c.setTypeface(null, Typeface.BOLD); c.text = tabs.size.toString()
        c.setOnClickListener { tabTray() }; navCount = c
        val w = FrameLayout(this); w.addView(c, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)); c.background = GradientDrawable().also { it.setColor(Color.TRANSPARENT); it.setStroke(dp(2), th.txt); it.cornerRadius = dp(6).toFloat() }
        w.setOnClickListener { tabTray() }; cell(w)
        cell(ibtn(R.drawable.bt_more) { pages.menu() })
        navBar = n
    }
    private var navCount: TextView? = null


    /** A round icon: the site's own icon fetched from the web, with a letter shown until it arrives. */
    private fun favView(site: String, size: Int, fill: Int, letter: String): View {
        val f = FrameLayout(this); f.background = GradientDrawable().also { it.setColor(fill); it.shape = GradientDrawable.OVAL }
        val t = label(this, letter, bold = true, size = (size * 0.45f)); t.gravity = Gravity.CENTER; t.setTextColor(if (fill == Color.WHITE) Color.parseColor("#333333") else th.txt)
        f.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER; val pd = dp((size * 0.22f).toInt()); iv.setPadding(pd, pd, pd, pd)
        val host = try { Uri.parse(site).host ?: site } catch (e: Throwable) { site }
        f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fx.load("https://www.google.com/s2/favicons?sz=128&domain=" + host, iv)
        return f
    }

    internal var oneShot: Eng? = null
    /** Firefox's "This time search in:" list under the search bar. */
    private fun engineMenu(anchor: View) {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(0, dp(8), 0, dp(8))
        box.background = roundBg(if (th.dark) Color.parseColor("#2B2A33") else Color.WHITE, 16)
        lateinit var pw: PopupWindow
        fun row(ico: View, name: String, f: () -> Unit) {
            val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(20), dp(12), dp(24), dp(12))
            r.addView(ico, LinearLayout.LayoutParams(dp(34), dp(34)))
            r.addView(label(this, name, size = 17f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(20) })
            r.setOnClickListener { pw.dismiss(); f() }
            box.addView(r)
        }
        fun sym(res: Int) = ImageView(this).also { it.setImageResource(res); it.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN); it.setPadding(dp(4), dp(4), dp(4), dp(4)) }
        box.addView(label(this, "This time search in:", size = 17f).also { it.setPadding(dp(20), dp(14), dp(20), dp(10)) })
        pages.visibleEngines().forEach { e -> row(favView(e.url, 34, Color.WHITE, e.letter), e.name) { oneShot = e; buildHome() } }
        row(sym(R.drawable.bt_star), "Bookmarks") { pages.bookmarks() }
        row(sym(R.drawable.bt_tabs), "Tabs") { tabTray() }
        row(sym(R.drawable.bt_history), "History") { pages.history() }
        row(sym(R.drawable.bt_gear), "Search settings") { pages.searchSettings() }
        val sc = ScrollView(this); sc.addView(box)
        pw = PopupWindow(sc, minOf(resources.displayMetrics.widthPixels - dp(24), dp(330)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(8).toFloat(); pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.showAsDropDown(anchor, dp(8), -dp(8))
    }

    private lateinit var tabBtn: TextView
    private lateinit var holder: FrameLayout
    private lateinit var homeBox: FrameLayout
    private lateinit var rootView: LinearLayout
    private fun barBottom() = !appMode && prefs.getBoolean("barBottom", false)
    /** Puts the address bar above or below the page. */
    internal fun placeBar() {
        rootView.removeAllViews()
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        if (barBottom()) {
            rootView.addView(holder, LinearLayout.LayoutParams(mp, 0, 1f))
            rootView.addView(prog, LinearLayout.LayoutParams(mp, dp(3)))
            rootView.addView(topBar)
        } else {
            rootView.addView(topBar)
            rootView.addView(prog, LinearLayout.LayoutParams(mp, dp(3)))
            rootView.addView(holder, LinearLayout.LayoutParams(mp, 0, 1f))
        }
        val ex = !appMode && prefs.getBoolean("toolbarExpanded", false)
        tabBtn.visibility = if (ex) View.GONE else View.VISIBLE; moreBtn?.visibility = if (ex) View.GONE else View.VISIBLE
        if (ex) { if (navBar == null) buildNav(); (navBar!!.parent as? ViewGroup)?.removeView(navBar); rootView.addView(navBar, LinearLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        if (::frame.isInitialized) frame.requestApplyInsets()
    }
    private fun updCount() { if (::tabBtn.isInitialized) tabBtn.text = tabs.size.toString(); navCount?.text = tabs.size.toString() }
    private var moreBtn: ImageButton? = null

    // ---------- home page ----------
    private val ENGINES = listOf(
        Triple("DuckDuckGo", "https://duckduckgo.com/?q=", "D"),
        Triple("Google", "https://www.google.com/search?q=", "G"),
        Triple("Bing", "https://www.bing.com/search?q=", "B"),
        Triple("Brave", "https://search.brave.com/search?q=", "B"),
        Triple("Ecosia", "https://www.ecosia.org/search?q=", "E"),
        Triple("Wikipedia", "https://en.wikipedia.org/w/index.php?search=", "W")
    )
    private fun tiles(): JSONArray {
        val raw = prefs.getString("tiles", null)
        if (raw != null) try { return JSONArray(raw) } catch (_: Throwable) {}
        val a = JSONArray()
        listOf("Wikipedia" to "https://www.wikipedia.org", "YouTube" to "https://m.youtube.com", "Reddit" to "https://www.reddit.com", "GitHub" to "https://github.com").forEach { a.put(JSONObject().put("n", it.first).put("u", it.second)) }
        return a
    }
    private fun roundBg(c: Int, r: Int) = GradientDrawable().also { it.setColor(c); it.cornerRadius = dp(r).toFloat() }

    private fun applyHome() {
        val show = cur?.home == true && pad == null
        homeBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) buildHome()
    }

    private fun buildHome() {
        homeBox.removeAllViews()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL; col.setPadding(dp(20), dp(28), dp(20), dp(28))
        val title = label(this, "Browser", bold = true, size = 26f); title.setTextColor(th.acc); title.gravity = Gravity.CENTER
        col.addView(title)
        val pill = label(this, "\u2713  You're protected", size = 13f); pill.gravity = Gravity.CENTER; pill.setPadding(dp(14), dp(6), dp(14), dp(6))
        pill.background = roundBg(th.side, 16)
        col.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10); it.bottomMargin = dp(20) })
        // search
        val sr = LinearLayout(this); sr.orientation = LinearLayout.HORIZONTAL; sr.gravity = Gravity.CENTER_VERTICAL; sr.background = roundBg(th.side, 24)
        val eb = LinearLayout(this); eb.orientation = LinearLayout.HORIZONTAL; eb.gravity = Gravity.CENTER_VERTICAL; eb.setPadding(dp(6), dp(4), dp(8), dp(4))
        eb.background = roundBg(Color.parseColor(if (th.dark) "#0F0F12" else "#E6E6EC"), 22)
        val shown = oneShot ?: pages.engine()
        eb.addView(favView(shown.url, 30, Color.WHITE, shown.letter), LinearLayout.LayoutParams(dp(30), dp(30)))
        eb.addView(ImageView(this).also { it.setImageResource(R.drawable.bt_chev); it.rotation = 90f; it.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN) }, LinearLayout.LayoutParams(dp(18), dp(18)).also { it.leftMargin = dp(4) })
        eb.setOnClickListener { engineMenu(sr) }
        sr.addView(eb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(6) })
        val se = EditText(this); se.setSingleLine(); se.hint = "Search"; se.setTextColor(th.txt); se.setHintTextColor(th.mute); se.textSize = 15f
        se.background = null; se.setPadding(dp(12), dp(12), dp(12), dp(12)); se.imeOptions = EditorInfo.IME_ACTION_GO; se.inputType = InputType.TYPE_TEXT_VARIATION_URI
        se.setOnEditorActionListener { _, _, _ -> val q = se.text.toString(); if (q.isNotBlank()) go(q); true }
        sr.addView(se, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(sr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // shortcut tiles
        if (prefs.getBoolean("hp_tiles", true)) {
            val grid = GridLayout(this); grid.columnCount = 4
            val arr = tiles()
            val w = (resources.displayMetrics.widthPixels - dp(40)) / 4
            fun cell(letter: String, name: String, fill: Int, site: String?, click: () -> Unit, long: (() -> Unit)?) {
                val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER_HORIZONTAL; c.setPadding(0, dp(14), 0, 0)
                val ic = if (site != null) favView(site, 64, fill, letter) else label(this, letter, bold = true, size = 28f).also { it.gravity = Gravity.CENTER; it.setTextColor(th.txt); it.background = GradientDrawable().also { g -> g.setColor(fill); g.shape = GradientDrawable.OVAL } }
                c.addView(ic, LinearLayout.LayoutParams(dp(64), dp(64)))
                val nm = label(this, name, size = 12f); nm.maxLines = 1; nm.gravity = Gravity.CENTER; nm.setPadding(dp(2), dp(6), dp(2), 0); nm.ellipsize = android.text.TextUtils.TruncateAt.END
                c.addView(nm, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
                c.setOnClickListener { click() }
                if (long != null) c.setOnLongClickListener { long(); true }
                grid.addView(c, GridLayout.LayoutParams().also { it.width = w })
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cell(o.getString("n").take(1).uppercase(), o.getString("n"), th.side, o.getString("u"), { go(o.getString("u")) }, {
                    bd().setTitle(o.getString("n")).setItems(arrayOf("Open in new tab", "Remove")) { _, k ->
                        if (k == 0) newTab(o.getString("u")) else { val n = JSONArray(); for (j in 0 until arr.length()) if (j != i) n.put(arr.get(j)); prefs.edit().putString("tiles", n.toString()).apply(); buildHome() }
                    }.show()
                })
            }
            cell("+", "Add shortcut", th.side, null, { addTile() }, null)
            col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })
        }
        // continue where you left off
        val last = tabs.lastOrNull { it !== cur && !it.home && it.url.startsWith("http") }
        if (prefs.getBoolean("hp_continue", true) && last != null) {
            val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL; card.setPadding(dp(16), dp(12), dp(16), dp(12)); card.background = roundBg(th.side, 14)
            card.addView(label(this, "Continue", bold = true, size = 13f).also { it.setTextColor(th.mute) })
            card.addView(label(this, last.title.ifBlank { last.url }, bold = true, size = 15f).also { it.maxLines = 2; it.setPadding(0, dp(4), 0, 0) })
            card.addView(label(this, Uri.parse(last.url).host ?: last.url, size = 12f).also { it.setTextColor(th.mute) })
            card.setOnClickListener { select(last) }
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(24) })
        }
        homeBox.addView(ScrollView(this).also { it.addView(col) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun addTile() {
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(20), dp(8), dp(20), 0)
        val n = EditText(dc); n.hint = "Name"; n.setSingleLine(); val u = EditText(dc); u.hint = "Address, e.g. example.com"; u.setSingleLine(); u.inputType = InputType.TYPE_TEXT_VARIATION_URI
        box.addView(n); box.addView(u)
        bd().setTitle("Add shortcut").setView(box).setPositiveButton("Add") { _, _ ->
            var url = u.text.toString().trim(); if (url.isEmpty()) return@setPositiveButton
            if (!url.startsWith("http")) url = "https://$url"
            val a = tiles(); a.put(JSONObject().put("n", n.text.toString().trim().ifBlank { Uri.parse(url).host ?: url }).put("u", url))
            prefs.edit().putString("tiles", a.toString()).apply(); buildHome()
        }.setNegativeButton("Cancel", null).show()
    }

    internal var appMode = false
    private var barHidden = false
    private var noBar = false   // chosen when the web app was made: no top bar and no button to bring it back
    internal var inTop = 0
    private lateinit var handle: ImageButton
    private fun barKey() = "hb_" + (if (appMode) (intent?.data?.host ?: "app") else "browser")
    internal fun setBarHidden(h: Boolean) { barHidden = h; prefs.edit().putBoolean(barKey(), h).apply(); applyBar() }
    private fun applyBar() {
        topBar.visibility = if (!barHidden && pad == null) View.VISIBLE else View.GONE
        handle.visibility = if (barHidden && pad == null && !noBar) View.VISIBLE else View.GONE
        frame.requestApplyInsets()
    }
    override fun onBackPressed() {
        if (popTop()) return
        val t = cur
        when {
            t != null && t.back -> t.session.goBack()
            tabs.size > 1 && t != null -> closeTab(t)
            else -> super.onBackPressed()
        }
    }

    private fun dlgStyle() = if (th.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    internal fun bd() = TB(this, dlgStyle(), th.acc)
    private fun baseUi() {
        window.decorView.systemUiVisibility = if (!th.dark) (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)) else View.SYSTEM_UI_FLAG_VISIBLE
    }

    internal fun dp(v: Int) = (v * dp).toInt()

    /** An icon button drawn from a vector, coloured with the app's text colour. */
    private fun ibtn(res: Int, f: () -> Unit): ImageButton {
        val b = ImageButton(this); b.setImageResource(res); b.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN)
        b.setBackgroundColor(Color.TRANSPARENT); b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        b.setOnClickListener { f() }; return b
    }
    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this); b.text = t; b.isAllCaps = false; b.setTextColor(th.acc); b.textSize = 14f
        b.setBackgroundColor(Color.TRANSPARENT); b.minWidth = 0; b.minimumWidth = dp(44); b.setPadding(dp(8), 0, dp(8), 0)
        b.setOnClickListener { f() }; return b
    }
    private fun label(c: Context, t: String, bold: Boolean = false, size: Float = 14f): TextView {
        val v = TextView(c); v.text = t; v.setTextColor(th.txt); v.textSize = size; if (bold) v.setTypeface(null, Typeface.BOLD); return v
    }

    private fun mkSession(priv: Boolean = false): Tab {
        val s = if (priv) GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build()) else GeckoSession()
        val t = Tab(s); t.priv = priv
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                t.url = url ?: ""; if (t.url.startsWith("http")) t.home = false
                if (t === cur) runOnUiThread { if (!urlBox.hasFocus()) urlBox.setText(if (t.home) "" else t.url); applyHome() }
            }
            override fun onCanGoBack(session: GeckoSession, can: Boolean) { t.back = can }
            override fun onCanGoForward(session: GeckoSession, can: Boolean) { t.fwd = can }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val nt = mkSession(t.priv); tabs.add(nt); runOnUiThread { select(nt) }
                return GeckoResult.fromValue(nt.session)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, p: Int) { if (t === cur) runOnUiThread { prog.progress = p; prog.visibility = if (p in 1..99) View.VISIBLE else View.GONE } }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: ""; if (t.url.startsWith("http") && !t.priv) addHistory(t.url, t.title) }
            override fun onExternalResponse(session: GeckoSession, resp: WebResponse) { download(resp.uri) }
            override fun onCloseRequest(session: GeckoSession) { runOnUiThread { closeTab(t) } }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { runOnUiThread { if (t === cur || !fullScreen) { geckoFs = fullScreen; if (!(pipping && !fullScreen)) setFs(fullScreen) } } }
        }
        s.open(rt)
        applyDesktop(t)
        return t
    }

    internal fun newTab(url: String, priv: Boolean = false) {
        val t = mkSession(priv); tabs.add(t)
        if (url == "about:home") t.home = true
        select(t); if (!t.home) t.session.loadUri(url)
    }
    internal fun select(t: Tab) {
        val old = cur; if (old != null && old !== t && !old.priv && old.url.startsWith("http")) try { gv.capturePixels().accept({ b -> if (b != null) try { old.thumb = android.graphics.Bitmap.createScaledBitmap(b, 360, maxOf(1, b.height * 360 / b.width), true) } catch (_: Throwable) {} }, { _ -> }) } catch (_: Throwable) {}
        cur = t; gv.setSession(t.session); applySecure(); urlBox.setText(if (t.home) "" else t.url); urlBox.clearFocus(); updCount(); applyHome()
    }
    internal fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i); updCount()
        if (!t.priv && t.url.startsWith("http")) pages.addClosed(t.url, t.title)
        if (tabs.isEmpty()) { if (appMode) { finish(); return }; gv.releaseSession(); t.session.close(); newTab("about:home"); return }
        if (t === cur) { gv.releaseSession(); select(tabs[minOf(i, tabs.size - 1)]) }
        t.session.close()
    }

    // ---------- downloads: the app's own downloader ----------
    internal fun download(u: String, quiet: Boolean = false) {
        val low = u.lowercase()
        if (!(low.startsWith("http://") || low.startsWith("https://") || low.startsWith("ftp://"))) {
            runOnUiThread { Toast.makeText(this, "This kind of link can not be downloaded here.", Toast.LENGTH_LONG).show() }
            return
        }
        try {
            DownloadCenter.init(this)
            DownloadCenter.manager.add(u, "file", "", DownloadCenter.filesDir, null, DownloadCenter.manager.defaultThreads)
            runOnUiThread { Toast.makeText(this, "Added to Downloads", Toast.LENGTH_SHORT).show(); if (!quiet) pages.downloads() }
        } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Could not download: " + e.message, Toast.LENGTH_LONG).show() } }
    }

    private fun mb(n: Long): String = if (n >= 1073741824L) String.format("%.2f GB", n / 1073741824.0) else if (n >= 1048576L) String.format("%.1f MB", n / 1048576.0) else String.format("%d KB", n / 1024)

    internal fun showDownloads() {
        DownloadCenter.init(this)
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = bd().setTitle("Downloads").setView(ScrollView(dc).apply { addView(box) }).setNegativeButton("Close", null).create()
        var live = true
        fun fill() {
            box.removeAllViews()
            val et = EditText(dc); et.hint = "Paste a link to download"; et.setSingleLine(); et.setTextColor(th.txt); et.setHintTextColor(th.mute)
            box.addView(et)
            box.addView(btn("Download this link") { val u = et.text.toString().trim(); if (u.isNotEmpty()) { download(u); live = false; dlg.dismiss() } })
            val items = DownloadCenter.manager.list()
            if (items.isEmpty()) box.addView(label(dc, "Nothing downloading yet. Files you open in the browser that can be saved show up here.", size = 13f).also { it.setPadding(0, dp(10), 0, dp(10)) })
            items.forEach { it ->
                val st = it.state.name
                val col = LinearLayout(dc); col.orientation = LinearLayout.VERTICAL; col.setPadding(0, dp(10), 0, dp(6))
                col.addView(label(dc, it.displayName(), bold = true, size = 14f))
                val done = it.done.get(); val tot = it.total
                val pct = if (tot > 0) (done * 100 / tot).toInt() else 0
                val info = when (st) {
                    "DONE" -> "Finished" + (if (tot > 0) " - " + mb(tot) else "")
                    "ERROR", "FAILED" -> "Failed: " + (it.message ?: "")
                    "PAUSED" -> "Paused at " + pct + "%" + (if (tot > 0) " of " + mb(tot) else "")
                    else -> st.lowercase().replaceFirstChar { c -> c.uppercase() } + " " + (if (tot > 0) "$pct% of " + mb(tot) else mb(done)) + (if (it.speed > 0) " - " + mb(it.speed) + "/s" else "")
                }
                col.addView(label(dc, info, size = 12f).also { v -> v.setTextColor(th.mute) })
                if (st != "DONE") {
                    val p = ProgressBar(dc, null, android.R.attr.progressBarStyleHorizontal); p.max = 100; p.progress = pct
                    p.progressDrawable.setColorFilter(th.acc, PorterDuff.Mode.SRC_IN); col.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
                }
                val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL
                if (st == "DONE") row.addView(btn("Save to Downloads folder") { saveToPublic(it) })
                else if (st == "PAUSED" || st == "ERROR" || st == "FAILED") row.addView(btn("Resume") { DownloadCenter.manager.resume(it.id); fill() })
                else row.addView(btn("Pause") { DownloadCenter.manager.pause(it.id); fill() })
                row.addView(btn("Remove") { DownloadCenter.manager.remove(it.id, false); fill() })
                col.addView(row)
                box.addView(col)
            }
        }
        fill()
        dlg.setOnDismissListener { live = false }
        // refresh the numbers while the list is open (rebuilding the text box would lose typing, so only when it is empty)
        val tick = object : Runnable {
            override fun run() {
                if (!live) return
                val et = (box.getChildAt(0) as? EditText)
                if (et == null || et.text.isEmpty()) fill()
                main.postDelayed(this, 1000)
            }
        }
        dlg.show(); main.postDelayed(tick, 1000)
    }

    internal fun saveToPublic(d: com.mobileaistudio.app.dl.DownloadItem) {
        val src = d.file()
        if (src == null || !src.exists()) { Toast.makeText(this, "That file is not ready.", Toast.LENGTH_SHORT).show(); return }
        if (android.os.Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "Saving to Downloads needs Android 10 or newer.", Toast.LENGTH_LONG).show(); return }
        Thread {
            try {
                val v = android.content.ContentValues()
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/MobileAIStudio")
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw Exception("Could not create the file.")
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { i -> i.copyTo(out, 64 * 1024) } } ?: throw Exception("Could not write the file.")
                runOnUiThread { Toast.makeText(this, "Saved to Downloads/MobileAIStudio/" + src.name, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Could not save: " + e.message, Toast.LENGTH_LONG).show() } }
        }.start()
    }

    // ---------- bookmarks and history ----------
    internal val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    internal fun load(k: String): JSONArray = try { JSONArray(prefs.getString(k, "[]")) } catch (e: Throwable) { JSONArray() }
    private fun addHistory(url: String, title: String) {
        val a = load("hist"); val out = JSONArray()
        out.put(JSONObject().put("u", url).put("t", title).put("d", System.currentTimeMillis()))
        for (i in 0 until a.length()) { if (out.length() >= 300) break; val o = a.getJSONObject(i); if (o.getString("u") != url) out.put(o) }
        prefs.edit().putString("hist", out.toString()).apply()
    }
    internal fun isMarked(url: String): Boolean { val a = load("bm"); for (i in 0 until a.length()) if (a.getJSONObject(i).getString("u") == url) return true; return false }
    internal fun toggleMark(t: Tab) {
        val a = load("bm"); val out = JSONArray(); var had = false
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getString("u") == t.url) had = true else out.put(o) }
        if (!had) out.put(JSONObject().put("u", t.url).put("t", t.title.ifBlank { t.url }))
        prefs.edit().putString("bm", out.toString()).apply()
        Toast.makeText(this, if (had) "Bookmark removed" else "Bookmarked", Toast.LENGTH_SHORT).show()
    }

    private fun showSaved() {
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = bd().setTitle("Bookmarks and history").setView(ScrollView(dc).apply { addView(box) }).setNegativeButton("Close", null).create()
        val c = cur
        if (c != null && c.url.startsWith("http")) box.addView(btn(if (isMarked(c.url)) "Remove bookmark for this page" else "Bookmark this page") { toggleMark(c); dlg.dismiss() })
        fun list(head: String, key: String, empty: String, canRemove: Boolean) {
            box.addView(label(dc, head, bold = true).also { it.setPadding(0, dp(12), 0, dp(4)) })
            val a = load(key)
            if (a.length() == 0) box.addView(label(dc, empty))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val u = o.getString("u")
                val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                val tv = label(dc, o.optString("t").ifBlank { u } + "\n" + u, size = 13f); tv.maxLines = 2; tv.setPadding(0, dp(8), 0, dp(8))
                tv.setOnClickListener { dlg.dismiss(); go(u) }
                row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (canRemove) row.addView(btn("Remove") {
                    val n = JSONArray(); for (j in 0 until a.length()) if (j != i) n.put(a.getJSONObject(j))
                    prefs.edit().putString(key, n.toString()).apply(); dlg.dismiss(); showSaved()
                })
                box.addView(row)
            }
        }
        list("Bookmarks", "bm", "No bookmarks yet.", true)
        list("History", "hist", "Nothing yet.", false)
        if (load("hist").length() > 0) box.addView(btn("Clear history") { prefs.edit().remove("hist").apply(); dlg.dismiss() })
        dlg.show()
    }

    internal fun go(q: String) {
        val s = q.trim(); if (s.isEmpty()) return
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:") -> s
            !s.contains(" ") && s.contains(".") -> "https://$s"
            else -> { val eng = oneShot ?: pages.engine(); oneShot = null; if (cur?.priv != true && prefs.getBoolean("recent_searches", true)) { val r = load("recent"); val n = JSONArray(); n.put(s); for (i in 0 until r.length()) if (r.getString(i) != s && n.length() < 8) n.put(r.getString(i)); prefs.edit().putString("recent", n.toString()).apply() }; eng.searchUrl(s) }
        }
        cur?.home = false; applyHome()
        cur?.session?.loadUri(u); hideSug(); urlBox.clearFocus(); gv.requestFocus()
    }

    // ---------- website as an app ----------
    private var waIcon: android.graphics.Bitmap? = null      // icon found on the site
    private var waCustom: android.graphics.Bitmap? = null    // picture the user chose
    private var waPreview: ImageView? = null
    private var waName: EditText? = null

    private fun httpGet(u: String, max: Int = 2_000_000): ByteArray? = try {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (c.responseCode !in 200..299) null else c.inputStream.use { ins ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int
            while (ins.read(buf).also { n = it } > 0) { out.write(buf, 0, n); if (out.size() > max) break }
            out.toByteArray()
        }
    } catch (e: Throwable) { null }

    /** Looks for the site's own icon: touch icon and link tags first, then favicon.ico, then a public icon service. */
    private fun fetchSiteIcon(page: String): android.graphics.Bitmap? {
        val base = try { java.net.URL(page) } catch (e: Throwable) { return null }
        val origin = base.protocol + "://" + base.host
        val cands = ArrayList<String>()
        httpGet(page, 300_000)?.let { b ->
            val html = String(b, Charsets.UTF_8)
            val found = ArrayList<Pair<Boolean, String>>()
            Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val tag = m.value
                val rel = Regex("rel\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
                if (!rel.contains("icon")) return@forEach
                val href = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@forEach
                try { found.add(rel.contains("apple") to java.net.URL(base, href.replace("&amp;", "&")).toString()) } catch (_: Throwable) {}
            }
            found.sortedByDescending { it.first }.forEach { cands.add(it.second) }
        }
        cands.add("$origin/apple-touch-icon.png"); cands.add("$origin/favicon.ico")
        cands.add("https://www.google.com/s2/favicons?sz=256&domain=" + base.host)
        var best: android.graphics.Bitmap? = null
        for (u in cands.distinct()) {
            val b = httpGet(u) ?: continue
            val bmp = try { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null } ?: continue
            if (bmp.width >= 96) return bmp
            if (best == null || bmp.width > best.width) best = bmp
        }
        return best
    }

    /** Draws a full-bleed 432 px adaptive icon (the launcher cuts its own shape from the middle 288 px), so nothing is left as a gap. */
    private fun composeIcon(site: android.graphics.Bitmap?, custom: android.graphics.Bitmap?, name: String): android.graphics.Bitmap {
        val sz = 432; val vis = 288; val o = (sz - vis) / 2f
        val out = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val all = android.graphics.RectF(0f, 0f, sz.toFloat(), sz.toFloat())
        fun square(b: android.graphics.Bitmap): android.graphics.Rect { val m = minOf(b.width, b.height); return android.graphics.Rect((b.width - m) / 2, (b.height - m) / 2, (b.width + m) / 2, (b.height + m) / 2) }
        if (custom != null) {
            c.drawBitmap(custom, square(custom), all, p)                                   // outer edge, hidden by the mask
            c.drawBitmap(custom, square(custom), android.graphics.RectF(o, o, o + vis, o + vis), p)  // whole picture in the visible part
        } else if (site != null) {
            val px = site.getPixel(1, 1)
            p.color = if (android.graphics.Color.alpha(px) == 255) px else Color.WHITE
            c.drawRect(all, p)
            val k = (vis * 0.71f) / maxOf(site.width, site.height).toFloat()
            val w = site.width * k; val h = site.height * k
            c.drawBitmap(site, null, android.graphics.RectF((sz - w) / 2, (sz - h) / 2, (sz + w) / 2, (sz + h) / 2), p)
        } else {
            p.color = th.acc; c.drawRect(all, p)
            p.color = th.onacc; p.textSize = 165f; p.textAlign = android.graphics.Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText((name.trim().firstOrNull() ?: 'W').uppercaseChar().toString(), sz / 2f, sz / 2f + 57f, p)
        }
        return out
    }

    /** What the launcher will show: the middle part of the icon in a rounded shape. */
    private fun previewOf(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val vis = 288; val o = (b.width - vis) / 2
        val out = android.graphics.Bitmap.createBitmap(vis, vis, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.clipPath(android.graphics.Path().also { it.addRoundRect(android.graphics.RectF(0f, 0f, vis.toFloat(), vis.toFloat()), 80f, 80f, android.graphics.Path.Direction.CW) })
        c.drawBitmap(b, -o.toFloat(), -o.toFloat(), p)
        return out
    }

    internal fun showWebApp() {
        val t = cur ?: return
        val page = t.url
        if (!page.startsWith("http")) { Toast.makeText(this, "Open a website first.", Toast.LENGTH_LONG).show(); return }
        waIcon = null; waCustom = null
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(20), dp(8), dp(20), dp(8))
        val nm = EditText(dc); nm.setSingleLine(); nm.hint = "App name"; nm.setText(t.title.ifBlank { java.net.URL(page).host }.take(30))
        nm.setTextColor(th.txt); nm.setHintTextColor(th.mute); waName = nm
        val adr = EditText(dc); adr.setSingleLine(); adr.setText(page); adr.setTextColor(th.txt); adr.hint = "Website address"; adr.setHintTextColor(th.mute)
        val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(0, dp(12), 0, dp(4))
        val iv = ImageView(dc); iv.scaleType = ImageView.ScaleType.FIT_CENTER; waPreview = iv
        row.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
        val col = LinearLayout(dc); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(12), 0, 0, 0)
        val note = label(dc, "Looking for the site icon...", size = 13f); note.setTextColor(th.mute)
        col.addView(note)
        col.addView(btn("Choose my own picture") {
            try { startActivityForResult(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*"), "Pick an icon"), 7301) }
            catch (e: Throwable) { Toast.makeText(this, "No picture picker found.", Toast.LENGTH_LONG).show() }
        }.also { it.gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        col.addView(btn("Use the site icon") { waCustom = null; refreshWaPreview(note) }.also { it.gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hl = label(dc, "Top bar (back button, site name, menu)", bold = true, size = 13f); hl.setPadding(0, dp(10), 0, 0)
        val rg = RadioGroup(dc); rg.orientation = RadioGroup.VERTICAL
        val rIn = RadioButton(dc); rIn.text = "Included"; rIn.setTextColor(th.txt); rIn.id = 9101
        val rEx = RadioButton(dc); rEx.text = "Excluded (full screen)"; rEx.setTextColor(th.txt); rEx.id = 9102
        rg.addView(rIn); rg.addView(rEx); rg.check(9101)
        val sl = label(dc, "Shortcut style", bold = true, size = 13f); sl.setPadding(0, dp(10), 0, 0)
        val sg = RadioGroup(dc); sg.orientation = RadioGroup.VERTICAL
        val sNew = RadioButton(dc); sNew.text = "Standard"; sNew.setTextColor(th.txt); sNew.id = 9111
        val sOld = RadioButton(dc); sOld.text = "Old style (may remove the ring around the icon)"; sOld.setTextColor(th.txt); sOld.id = 9112
        sg.addView(sNew); sg.addView(sOld); sg.check(9111)
        box.addView(nm); box.addView(adr); box.addView(row); box.addView(hl); box.addView(rg); box.addView(sl); box.addView(sg)
        waNote = note
        refreshWaPreview(note)
        Thread { val b = fetchSiteIcon(adr.text.toString().trim().ifBlank { page }); runOnUiThread { waIcon = b; waNote?.let { refreshWaPreview(it, true) } } }.start()
        bd().setTitle("Make this site an app").setView(box)
            .setPositiveButton("Add to home screen") { _, _ ->
                val u = adr.text.toString().trim().let { if (it.startsWith("http")) it else "https://$it" }
                pinWebApp(u, nm.text.toString().trim().ifBlank { "Web app" }, rg.checkedRadioButtonId == 9102, sg.checkedRadioButtonId == 9112)
            }.setNegativeButton("Cancel", null).show()
    }
    private var waNote: TextView? = null
    private fun refreshWaPreview(note: TextView, loaded: Boolean = false) {
        val bmp = composeIcon(waIcon, waCustom, waName?.text?.toString() ?: "")
        waPreview?.setImageBitmap(previewOf(bmp))
        note.text = when { waCustom != null -> "Using your picture"; waIcon != null -> "Using the site icon"; loaded -> "No site icon found, using a letter. You can choose your own picture." ; else -> "Looking for the site icon..." }
    }
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7302) { onRole?.invoke(); onRole = null }
        if (req == 7303 && res == RESULT_OK) { data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { go(it) } }
        if (req == 7301 && res == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().also { o -> o.inSampleSize = 2 }) }
                if (bmp != null) { waCustom = bmp; waNote?.let { refreshWaPreview(it) } } else Toast.makeText(this, "Could not read that picture.", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) { Toast.makeText(this, "Could not read that picture.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun pinWebApp(url: String, name: String, hideBar: Boolean, oldStyle: Boolean = false) {
        try {
            if (oldStyle) {
                val icon = composeIcon(waIcon, waCustom, name)
                val id = "web_" + Integer.toHexString(url.hashCode())
                val f = java.io.File(filesDir, "webapp_$id.png")
                f.outputStream().use { icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val launch = android.content.Intent(this, BrowserActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(Uri.parse(url))
                    .putExtra("app", true).putExtra("hidebar", hideBar).putExtra("name", name).putExtra("icon", f.path)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                val small = android.graphics.Bitmap.createScaledBitmap(previewOf(icon), 192, 192, true)
                sendBroadcast(android.content.Intent("com.android.launcher.action.INSTALL_SHORTCUT")
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_INTENT, launch)
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_NAME, name)
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_ICON, small)
                    .putExtra("duplicate", false))
                Toast.makeText(this, "Sent to your launcher. If nothing appears on the home screen, your launcher does not support the old style: use Standard.", Toast.LENGTH_LONG).show()
                return
            }
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (sm == null || !sm.isRequestPinShortcutSupported) { Toast.makeText(this, "Your launcher can not add shortcuts.", Toast.LENGTH_LONG).show(); return }
            val id = "web_" + Integer.toHexString(url.hashCode())
            val icon = composeIcon(waIcon, waCustom, name)
            val f = java.io.File(filesDir, "webapp_$id.png")
            f.outputStream().use { icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val intent = android.content.Intent(this, BrowserActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).setData(Uri.parse(url))
                .putExtra("app", true).putExtra("hidebar", hideBar).putExtra("name", name).putExtra("icon", f.path)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            val info = android.content.pm.ShortcutInfo.Builder(this, id).setShortLabel(name.take(24)).setLongLabel(name)
                .setIcon(android.graphics.drawable.Icon.createWithAdaptiveBitmap(icon)).setIntent(intent).build()
            sm.requestPinShortcut(info, null)
            Toast.makeText(this, "Confirm adding it on the screen that appears.", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) { Toast.makeText(this, "Could not add it: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
    }

    // ---------- menu ----------
    private fun showMenu() {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val sheet = LinearLayout(this); sheet.orientation = LinearLayout.VERTICAL; sheet.setPadding(dp(8), dp(12), dp(8), dp(8))
        sheet.background = GradientDrawable().also { it.setColor(th.panel); it.cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f) }
        fun quick(sym: String, name: String, f: () -> Unit): View {
            val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER; c.setPadding(0, dp(8), 0, dp(8))
            c.addView(label(this, sym, bold = true, size = 20f).also { it.gravity = Gravity.CENTER })
            c.addView(label(this, name, size = 11f).also { it.gravity = Gravity.CENTER; it.setTextColor(th.mute) })
            c.setOnClickListener { d.dismiss(); f() }; return c
        }
        val q = LinearLayout(this); q.orientation = LinearLayout.HORIZONTAL
        val t = cur
        val marked = t != null && isMarked(t.url)
        q.addView(quick("\u2192", "Forward") { cur?.session?.goForward() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        q.addView(quick("\u21BB", "Reload") { cur?.session?.reload() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        q.addView(quick(if (marked) "\u2605" else "\u2606", "Bookmark") { cur?.let { if (it.url.startsWith("http")) toggleMark(it) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        q.addView(quick("\u2302", "Home") { cur?.home = true; applyHome() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(q)
        val list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL
        fun row(text: String, f: () -> Unit) {
            val v = label(this, text, size = 16f); v.setPadding(dp(16), dp(14), dp(16), dp(14)); v.setOnClickListener { d.dismiss(); f() }; list.addView(v)
        }
        row("Add-ons") { showAddons() }
        row("Bookmarks and history") { showSaved() }
        row("Downloads") { showDownloads() }
        row("Make this site an app on the home screen") { showWebApp() }
        row("Duck.ai (DuckDuckGo AI chat)") { go("https://duck.ai") }
        row("Desktop site: " + (if (prefs.getBoolean("desktop", false)) "ON" else "off")) { toggleDesktop() }
        if (!appMode) row("Address bar at the " + (if (barBottom()) "top" else "bottom")) { prefs.edit().putBoolean("barBottom", !barBottom()).apply(); placeBar() }
        row("Hide top bar") { setBarHidden(true) }
        row("Controller layout") { showPadMenu() }
        row("Graphics test") { gfxTest() }
        row("Graphics limit workaround: " + (if (prefs.getBoolean("gfxfix", false)) "ON" else "off")) { toggleGfx() }
        row("Close browser") { finish() }
        sheet.addView(ScrollView(this).also { it.addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(420), (resources.displayMetrics.heightPixels * 0.6).toInt())))
        d.setContentView(sheet)
        d.window?.let { w ->
            w.setGravity(Gravity.BOTTOM); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); w.setDimAmount(0.4f); w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.setCanceledOnTouchOutside(true)
        d.show()
    }

    internal fun showPadMenu() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Move and arrange buttons" to { startPad(true) })
        acts.add("Button size" to { bd().setTitle("Button size").setItems(arrayOf("Small", "Medium", "Large")) { _, k -> prefs.edit().putFloat("padSize", floatArrayOf(0.8f, 1f, 1.25f)[k]).apply(); pad?.rebuild() }.show() })
        acts.add("Button transparency" to { bd().setTitle("Button transparency").setItems(arrayOf("Very see-through", "See-through", "Solid")) { _, k -> prefs.edit().putFloat("padAlpha", floatArrayOf(0.3f, 0.55f, 0.9f)[k]).apply(); pad?.rebuild() }.show() })
        acts.add("Key for each button" to { bd().setTitle("Keys sent by the controller").setMessage(PadOverlay.DEFS.joinToString("\n") { it.label + "  =  " + it.keyName }).setPositiveButton("OK", null).show() })
        acts.add("Reset layout" to { prefs.edit().remove("padPos").apply(); pad?.rebuild(); Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show() })
        bd().setTitle("Controller layout").setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    /** Fullscreen mode: top bar hidden, on-screen controller shown. */
    internal fun startPad(edit: Boolean = false) {
        if (pad != null) { pad?.setEdit(edit); return }
        urlBox.clearFocus()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        frame.setBackgroundColor(Color.BLACK)
        val p = PadOverlay(this, prefs, th, { code, down -> sendKey(code, down) }, { stopPad() })
        pad = p; frame.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); p.setEdit(edit); applyBar()
    }
    private fun stopPad() {
        pad?.let { frame.removeView(it) }; pad = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        baseUi()
        frame.setBackgroundColor(th.bg); applyBar()
    }
    private fun sendKey(code: Int, down: Boolean) {
        val t = android.os.SystemClock.uptimeMillis()
        gv.dispatchKeyEvent(KeyEvent(t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0))
    }

    private fun showTabs() {
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = bd().setTitle("Tabs").setView(ScrollView(dc).apply { addView(box) }).setNegativeButton("Close", null).create()
        tabs.toList().forEach { t ->
            val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            val tv = label(dc, t.title.ifBlank { t.url.ifBlank { "New tab" } }, bold = t === cur); tv.maxLines = 2; tv.setPadding(0, dp(10), 0, dp(10))
            if (t === cur) tv.setTextColor(th.acc)
            tv.setOnClickListener { select(t); dlg.dismiss() }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Close") { closeTab(t); dlg.dismiss(); if (tabs.isNotEmpty()) showTabs() })
            box.addView(row)
        }
        box.addView(btn("New tab") { dlg.dismiss(); newTab("about:home") })
        dlg.show()
    }

    private val RECOMMENDED = listOf(
        Triple("uBlock Origin", "Blocks ads and trackers", "ublock-origin"),
        Triple("Bitwarden", "Password manager", "bitwarden-password-manager"),
        Triple("Dark Reader", "Dark mode for any site", "darkreader"),
        Triple("SponsorBlock", "Skips sponsor segments on YouTube", "sponsorblock")
    )

    private fun showAddons() {
        val box = LinearLayout(dc); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = bd().setTitle("Add-ons").setView(ScrollView(dc).apply { addView(box) }).setNegativeButton("Close", null).create()
        fun head(t: String) { box.addView(label(dc, t, bold = true).also { it.setPadding(0, dp(12), 0, dp(4)) }) }
        head("Installed")
        val inst = LinearLayout(dc); inst.orientation = LinearLayout.VERTICAL; box.addView(inst)
        inst.addView(label(dc, "Loading..."))
        rt.webExtensionController.list().accept({ list ->
            runOnUiThread {
                inst.removeAllViews()
                val mine = (list ?: emptyList()).filter { !it.isBuiltIn }
                if (mine.isEmpty()) inst.addView(label(dc, "None yet. Add one below."))
                mine.forEach { e ->
                    val on = e.metaData.enabled
                    val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                    val tv = label(dc, (e.metaData.name ?: e.id) + "  " + (e.metaData.version ?: "") + (if (on) "" else "  (off)"))
                    row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(btn(if (on) "Turn off" else "Turn on") {
                        val r = if (on) rt.webExtensionController.disable(e, WebExtensionController.EnableSource.USER) else rt.webExtensionController.enable(e, WebExtensionController.EnableSource.USER)
                        r.accept({ runOnUiThread { dlg.dismiss(); showAddons() } }, { _ -> })
                    })
                    row.addView(btn("Remove") {
                        bd().setTitle("Remove " + (e.metaData.name ?: e.id) + "?").setPositiveButton("Remove") { _, _ ->
                            rt.webExtensionController.uninstall(e).accept({ runOnUiThread { dlg.dismiss(); showAddons() } }, { _ -> })
                        }.setNegativeButton("Cancel", null).show()
                    })
                    inst.addView(row)
                }
            }
        }, { _ -> })
        head("Add from the Firefox add-on store")
        RECOMMENDED.forEach { (name, sub, slug) ->
            val row = LinearLayout(dc); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            val tv = label(dc, name + "\n" + sub, size = 14f)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Add") { install("https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi", dlg) })
            box.addView(row)
        }
        head("Or paste an add-on link (.xpi)")
        val et = EditText(dc); et.hint = "https://.../addon.xpi"; et.setSingleLine(); et.setTextColor(th.txt); et.setHintTextColor(th.mute); box.addView(et)
        box.addView(btn("Add from link") { val u = et.text.toString().trim(); if (u.isNotEmpty()) install(u, dlg) })
        box.addView(label(dc, "Only add-ons you trust. They can read the pages you visit.", size = 12f).also { it.setTextColor(th.mute); it.setPadding(0, dp(8), 0, 0) })
        dlg.show()
    }

    private fun install(url: String, dlg: AlertDialog) {
        Toast.makeText(this, "Getting the add-on...", Toast.LENGTH_SHORT).show()
        rt.webExtensionController.install(url).accept({ e ->
            runOnUiThread { Toast.makeText(this, "Added " + (e?.metaData?.name ?: "add-on"), Toast.LENGTH_SHORT).show(); dlg.dismiss(); showAddons() }
        }, { err -> runOnUiThread { Toast.makeText(this, "Could not add it: " + (err?.message ?: "unknown error"), Toast.LENGTH_LONG).show() } })
    }

    private val GFX_HTML = """<html><body style='font:14px sans-serif;padding:12px'><b id=h>Graphics test</b><pre id=o></pre><script>
var o='';function t(v){var c=document.createElement('canvas'),g=c.getContext(v);if(!g){o+=v+': NOT AVAILABLE\n';return}
o+=v+':\n MAX_VERTEX_UNIFORM_VECTORS='+g.getParameter(g.MAX_VERTEX_UNIFORM_VECTORS)+'\n MAX_FRAGMENT_UNIFORM_VECTORS='+g.getParameter(g.MAX_FRAGMENT_UNIFORM_VECTORS)+'\n MAX_TEXTURE_SIZE='+g.getParameter(g.MAX_TEXTURE_SIZE)+'\n';
var e=g.getExtension('WEBGL_debug_renderer_info');if(e)o+=' GPU='+g.getParameter(e.UNMASKED_RENDERER_WEBGL)+'\n';}
t('webgl');t('webgl2');document.getElementById('o').textContent=o;</script></body></html>"""

    private val GFX_URI = "resource://android/assets/gfxfix/"
    private val GFX_ID = "gfxfix@mobileaistudio"
    internal fun toggleGfx() {
        val on = !prefs.getBoolean("gfxfix", false)
        prefs.edit().putBoolean("gfxfix", on).apply()
        val c = rt.webExtensionController
        if (on) {
            c.installBuiltIn(GFX_URI).accept({ _ -> runOnUiThread { Toast.makeText(this, "Workaround on. Reloading the page.", Toast.LENGTH_SHORT).show(); cur?.session?.reload() } },
                { e -> runOnUiThread { Toast.makeText(this, "Could not turn it on: " + (e?.message ?: "error"), Toast.LENGTH_LONG).show() } })
        } else {
            c.list().accept({ l -> (l ?: emptyList()).filter { it.id == GFX_ID }.forEach { c.uninstall(it) }; runOnUiThread { Toast.makeText(this, "Workaround off", Toast.LENGTH_SHORT).show(); cur?.session?.reload() } }, { _ -> })
        }
    }

    internal fun gfxTest() {
        val wv = android.webkit.WebView(this); wv.settings.javaScriptEnabled = true
        wv.loadDataWithBaseURL("https://example.com/", GFX_HTML, "text/html", "utf-8", null)
        bd().setTitle("Graphics test: Chrome engine").setView(wv).setNegativeButton("Close", null)
            .setPositiveButton("Test Firefox engine") { _, _ -> cur?.session?.loadUri("data:text/html;charset=utf-8," + Uri.encode(GFX_HTML)) }.show()
    }

    private fun applyDesktop(t: Tab) {
        val d = prefs.getBoolean("desktop", false)
        t.session.settings.userAgentMode = if (d) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode = if (d) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }
    internal fun toggleDesktop() {
        val on = !prefs.getBoolean("desktop", false)
        prefs.edit().putBoolean("desktop", on).apply()
        tabs.forEach { applyDesktop(it) }
        cur?.session?.reload()
        Toast.makeText(this, if (on) "Desktop site on" else "Desktop site off", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { if (popTop()) return true; if (pad != null) { stopPad(); return true }; val t = cur; if (t != null && t.back) { t.session.goBack(); return true } else if (tabs.size > 1 && t != null) { closeTab(t); return true } }
        return super.onKeyDown(k, e)
    }

    override fun onStop() {
        super.onStop()
        if (!appMode && !isChangingConfigurations) {
            val urls = tabs.filter { !it.priv && it.url.startsWith("http") }.map { it.url }
            prefs.edit().putString("restore", JSONArray(urls).toString()).putLong("lastSeen", System.currentTimeMillis()).apply()
        }
    }

    override fun onDestroy() { tabs.forEach { try { it.session.close() } catch (_: Throwable) {} }; super.onDestroy() }
}

/** One on-screen button of the controller. rot is set for the four arrow buttons (drawn as vector triangles). */
class PadDef(val id: String, val label: String, val key: Int, val keyName: String, val x: Float, val y: Float, val rot: Float? = null)

/** The overlay controller: buttons sit on top of the page and send key presses. */
class PadOverlay(private val act: Activity, private val prefs: android.content.SharedPreferences, private val th: BTheme, private val onKey: (Int, Boolean) -> Unit, private val onClose: () -> Unit) : FrameLayout(act) {
    companion object {
        val DEFS = listOf(
            PadDef("up", "Up", KeyEvent.KEYCODE_W, "W", 0.13f, 0.52f, 0f),
            PadDef("left", "Left", KeyEvent.KEYCODE_A, "A", 0.06f, 0.68f, 270f),
            PadDef("right", "Right", KeyEvent.KEYCODE_D, "D", 0.20f, 0.68f, 90f),
            PadDef("down", "Down", KeyEvent.KEYCODE_S, "S", 0.13f, 0.84f, 180f),
            PadDef("Y", "Y", KeyEvent.KEYCODE_Q, "Q", 0.87f, 0.52f),
            PadDef("X", "X", KeyEvent.KEYCODE_E, "E", 0.80f, 0.68f),
            PadDef("B", "B", KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 0.94f, 0.68f),
            PadDef("A", "A", KeyEvent.KEYCODE_SPACE, "Space", 0.87f, 0.84f),
            PadDef("LT", "LT", KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", 0.07f, 0.22f),
            PadDef("LB", "LB", KeyEvent.KEYCODE_Z, "Z", 0.07f, 0.36f),
            PadDef("RT", "RT", KeyEvent.KEYCODE_F, "F", 0.93f, 0.22f),
            PadDef("RB", "RB", KeyEvent.KEYCODE_C, "C", 0.93f, 0.36f),
            PadDef("SEL", "Select", KeyEvent.KEYCODE_TAB, "Tab", 0.40f, 0.92f),
            PadDef("STA", "Start", KeyEvent.KEYCODE_ESCAPE, "Esc", 0.60f, 0.92f)
        )
    }
    private var edit = false
    private val dp = act.resources.displayMetrics.density
    private val views = ArrayList<Pair<PadDef, View>>()

    init { rebuild() }

    private fun pos(): JSONObject = try { JSONObject(prefs.getString("padPos", "{}")) } catch (e: Throwable) { JSONObject() }

    fun setEdit(on: Boolean) { edit = on; rebuild() }

    fun rebuild() {
        removeAllViews(); views.clear()
        val size = (56 * dp * prefs.getFloat("padSize", 1f)).toInt()
        val alpha = prefs.getFloat("padAlpha", 0.55f)
        val saved = pos()
        val fill = if (th.dark) th.side else th.panel
        for (d in DEFS) {
            val wide = d.id == "SEL" || d.id == "STA"
            val w = if (wide) (size * 1.5f).toInt() else size
            val h = if (wide) (size * 0.6f).toInt() else size
            val bg = GradientDrawable()
            if (wide) { bg.shape = GradientDrawable.RECTANGLE; bg.cornerRadius = h / 2f } else bg.shape = GradientDrawable.OVAL
            bg.setColor(fill); bg.setStroke((2 * dp).toInt(), th.acc)
            val v: View
            if (d.rot != null) {
                val iv = ImageView(act); iv.setImageResource(R.drawable.bt_triangle); iv.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN)
                iv.rotation = d.rot; iv.scaleType = ImageView.ScaleType.CENTER_INSIDE; iv.setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt()); v = iv
            } else {
                val tv = TextView(act); tv.text = d.label; tv.gravity = Gravity.CENTER; tv.setTextColor(th.txt); tv.textSize = if (d.label.length > 2) 11f else 17f
                tv.setTypeface(null, Typeface.BOLD); v = tv
            }
            v.background = bg; v.alpha = alpha
            addView(v, LayoutParams(w, h))
            views.add(d to v)
            val fx = if (saved.has(d.id)) saved.getJSONArray(d.id).getDouble(0).toFloat() else d.x
            val fy = if (saved.has(d.id)) saved.getJSONArray(d.id).getDouble(1).toFloat() else d.y
            v.tag = floatArrayOf(fx, fy)
            v.setOnTouchListener { vv, e -> touch(d, vv, e) }
        }
        val bar = LinearLayout(act); bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        val g = GradientDrawable(); g.cornerRadius = 10 * dp; g.setColor(if (edit) th.acc else Color.argb(220, Color.red(th.bg), Color.green(th.bg), Color.blue(th.bg))); bar.background = g
        val ic = ImageView(act); ic.setImageResource(R.drawable.bt_close); ic.setColorFilter(if (edit) th.onacc else th.txt, PorterDuff.Mode.SRC_IN)
        bar.addView(ic, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
        val tx = TextView(act); tx.text = if (edit) "  Done - drag buttons to move them" else "  Close controller"; tx.textSize = 13f; tx.setTextColor(if (edit) th.onacc else th.txt)
        bar.addView(tx)
        bar.setOnClickListener { if (edit) { edit = false; rebuild() } else onClose() }
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = (24 * dp).toInt() })
        setBackgroundColor(if (edit) Color.parseColor("#44000000") else Color.TRANSPARENT)
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val W = r - l; val H = b - t
        for ((_, v) in views) {
            val f = v.tag as FloatArray
            val x = (f[0] * W - v.measuredWidth / 2f).toInt(); val y = (f[1] * H - v.measuredHeight / 2f).toInt()
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }

    // buttons only grab touches they sit on; the rest of the screen still reaches the page
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    private fun touch(d: PadDef, v: View, e: MotionEvent): Boolean {
        if (edit) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val W = width.toFloat(); val H = height.toFloat()
                    val cx = (v.left + e.x).coerceIn(0f, W); val cy = (v.top + e.y).coerceIn(0f, H)
                    v.tag = floatArrayOf(cx / W, cy / H); requestLayout()
                }
                MotionEvent.ACTION_UP -> {
                    val o = pos(); val f = v.tag as FloatArray
                    o.put(d.id, JSONArray().put(f[0].toDouble()).put(f[1].toDouble()))
                    prefs.edit().putString("padPos", o.toString()).apply()
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.alpha = 1f; v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onKey(d.key, true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = prefs.getFloat("padAlpha", 0.55f); onKey(d.key, false) }
        }
        return true
    }
}
