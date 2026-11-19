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
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private lateinit var gv: GeckoView
    private lateinit var urlBox: EditText
    private lateinit var prog: ProgressBar
    private lateinit var rt: GeckoRuntime
    private lateinit var topBar: LinearLayout
    private lateinit var frame: FrameLayout
    private var pad: PadOverlay? = null
    private var dp = 1f
    private lateinit var th: BTheme
    private lateinit var dc: Context
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dp = resources.displayMetrics.density
        th = BTheme.load(this)
        dc = ContextThemeWrapper(this, dlgStyle())
        rt = Gk.get(this)
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
        urlBox.setOnFocusChangeListener { _, f -> if (f) urlBox.post { urlBox.selectAll() } }
        top.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ibtn(R.drawable.bt_up) { setBarHidden(true) })
        top.addView(ibtn(R.drawable.bt_gamepad) { startPad() })
        top.addView(ibtn(R.drawable.bt_menu) { showMenu() })
        topBar = top
        root.addView(top)
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); prog.max = 100; prog.visibility = View.GONE
        prog.progressDrawable.setColorFilter(th.acc, PorterDuff.Mode.SRC_IN)
        root.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        gv = GeckoView(this)
        root.addView(gv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        // leave room for the status bar only when the system really draws under it
        frame.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") val t = ins.systemWindowInsetTop
            @Suppress("DEPRECATION") val bt = ins.systemWindowInsetBottom
            inTop = t
            topBar.setPadding(dp(6), t + dp(4), dp(6), dp(4))
            root.setPadding(0, if (topBar.visibility != View.VISIBLE && pad == null) t else 0, 0, if (pad == null) bt else 0)
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
        noBar = appMode && intent?.getBooleanExtra("hidebar", false) == true
        barHidden = if (intent?.hasExtra("hidebar") == true) intent.getBooleanExtra("hidebar", false) else prefs.getBoolean(barKey(), false); applyBar()
        newTab(if (first.isNullOrBlank()) "https://duckduckgo.com" else first)
    }

    private var appMode = false
    private var barHidden = false
    private var noBar = false   // chosen when the web app was made: no top bar and no button to bring it back
    private var inTop = 0
    private lateinit var handle: ImageButton
    private fun barKey() = "hb_" + (if (appMode) (intent?.data?.host ?: "app") else "browser")
    private fun setBarHidden(h: Boolean) { barHidden = h; prefs.edit().putBoolean(barKey(), h).apply(); applyBar() }
    private fun applyBar() {
        topBar.visibility = if (!barHidden && pad == null) View.VISIBLE else View.GONE
        handle.visibility = if (barHidden && pad == null && !noBar) View.VISIBLE else View.GONE
        frame.requestApplyInsets()
    }
    override fun onBackPressed() {
        val t = cur
        when {
            t != null && t.back -> t.session.goBack()
            tabs.size > 1 && t != null -> closeTab(t)
            else -> super.onBackPressed()
        }
    }

    private fun dlgStyle() = if (th.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    private fun bd() = TB(this, dlgStyle(), th.acc)
    private fun baseUi() {
        window.decorView.systemUiVisibility = if (!th.dark) (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)) else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun dp(v: Int) = (v * dp).toInt()

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

    private fun mkSession(): Tab {
        val s = GeckoSession()
        val t = Tab(s)
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                t.url = url ?: ""; if (t === cur) runOnUiThread { if (!urlBox.hasFocus()) urlBox.setText(t.url) }
            }
            override fun onCanGoBack(session: GeckoSession, can: Boolean) { t.back = can }
            override fun onCanGoForward(session: GeckoSession, can: Boolean) { t.fwd = can }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val nt = mkSession(); tabs.add(nt); runOnUiThread { select(nt) }
                return GeckoResult.fromValue(nt.session)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, p: Int) { if (t === cur) runOnUiThread { prog.progress = p; prog.visibility = if (p in 1..99) View.VISIBLE else View.GONE } }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: ""; if (t.url.startsWith("http")) addHistory(t.url, t.title) }
            override fun onExternalResponse(session: GeckoSession, resp: WebResponse) { download(resp.uri) }
            override fun onCloseRequest(session: GeckoSession) { runOnUiThread { closeTab(t) } }
        }
        s.open(rt)
        applyDesktop(t)
        return t
    }

    private fun newTab(url: String) {
        val t = mkSession(); tabs.add(t); select(t); t.session.loadUri(url)
    }
    private fun select(t: Tab) {
        cur = t; gv.setSession(t.session); urlBox.setText(t.url); urlBox.clearFocus()
    }
    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i)
        if (tabs.isEmpty()) { finish(); return }
        if (t === cur) { gv.releaseSession(); select(tabs[minOf(i, tabs.size - 1)]) }
        t.session.close()
    }

    // ---------- downloads: the app's own downloader ----------
    private fun download(u: String) {
        val low = u.lowercase()
        if (!(low.startsWith("http://") || low.startsWith("https://") || low.startsWith("ftp://"))) {
            runOnUiThread { Toast.makeText(this, "This kind of link can not be downloaded here.", Toast.LENGTH_LONG).show() }
            return
        }
        try {
            DownloadCenter.init(this)
            DownloadCenter.manager.add(u, "file", "", DownloadCenter.filesDir, null, DownloadCenter.manager.defaultThreads)
            runOnUiThread { Toast.makeText(this, "Added to Downloads", Toast.LENGTH_SHORT).show(); showDownloads() }
        } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Could not download: " + e.message, Toast.LENGTH_LONG).show() } }
    }

    private fun mb(n: Long): String = if (n >= 1073741824L) String.format("%.2f GB", n / 1073741824.0) else if (n >= 1048576L) String.format("%.1f MB", n / 1048576.0) else String.format("%d KB", n / 1024)

    private fun showDownloads() {
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

    private fun saveToPublic(d: com.mobileaistudio.app.dl.DownloadItem) {
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
    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private fun load(k: String): JSONArray = try { JSONArray(prefs.getString(k, "[]")) } catch (e: Throwable) { JSONArray() }
    private fun addHistory(url: String, title: String) {
        val a = load("hist"); val out = JSONArray()
        out.put(JSONObject().put("u", url).put("t", title).put("d", System.currentTimeMillis()))
        for (i in 0 until a.length()) { if (out.length() >= 300) break; val o = a.getJSONObject(i); if (o.getString("u") != url) out.put(o) }
        prefs.edit().putString("hist", out.toString()).apply()
    }
    private fun isMarked(url: String): Boolean { val a = load("bm"); for (i in 0 until a.length()) if (a.getJSONObject(i).getString("u") == url) return true; return false }
    private fun toggleMark(t: Tab) {
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

    private fun go(q: String) {
        val s = q.trim(); if (s.isEmpty()) return
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:") -> s
            !s.contains(" ") && s.contains(".") -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        }
        cur?.session?.loadUri(u); urlBox.clearFocus(); gv.requestFocus()
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

    /** Draws the 192 px shortcut icon: a picture of your own fills it; a site icon sits on white; with neither, the first letter. */
    private fun composeIcon(site: android.graphics.Bitmap?, custom: android.graphics.Bitmap?, name: String): android.graphics.Bitmap {
        val sz = 192
        val out = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val rect = android.graphics.RectF(0f, 0f, sz.toFloat(), sz.toFloat())
        val path = android.graphics.Path().also { it.addRoundRect(rect, 48f, 48f, android.graphics.Path.Direction.CW) }
        c.save(); c.clipPath(path)
        if (custom != null) {
            val m = minOf(custom.width, custom.height)
            c.drawBitmap(custom, android.graphics.Rect((custom.width - m) / 2, (custom.height - m) / 2, (custom.width + m) / 2, (custom.height + m) / 2), rect, p)
        } else if (site != null) {
            p.color = Color.WHITE; c.drawRect(rect, p)
            val pad = 28f; val m = maxOf(site.width, site.height).toFloat(); val k = (sz - 2 * pad) / m
            val w = site.width * k; val h = site.height * k
            c.drawBitmap(site, null, android.graphics.RectF((sz - w) / 2, (sz - h) / 2, (sz + w) / 2, (sz + h) / 2), p)
        } else {
            p.color = th.acc; c.drawRect(rect, p)
            p.color = th.onacc; p.textSize = 110f; p.textAlign = android.graphics.Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText((name.trim().firstOrNull() ?: 'W').uppercaseChar().toString(), sz / 2f, sz / 2f + 38f, p)
        }
        c.restore()
        return out
    }

    private fun showWebApp() {
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
        box.addView(nm); box.addView(adr); box.addView(row); box.addView(hl); box.addView(rg)
        waNote = note
        refreshWaPreview(note)
        Thread { val b = fetchSiteIcon(adr.text.toString().trim().ifBlank { page }); runOnUiThread { waIcon = b; waNote?.let { refreshWaPreview(it, true) } } }.start()
        bd().setTitle("Make this site an app").setView(box)
            .setPositiveButton("Add to home screen") { _, _ ->
                val u = adr.text.toString().trim().let { if (it.startsWith("http")) it else "https://$it" }
                pinWebApp(u, nm.text.toString().trim().ifBlank { "Web app" }, rg.checkedRadioButtonId == 9102)
            }.setNegativeButton("Cancel", null).show()
    }
    private var waNote: TextView? = null
    private fun refreshWaPreview(note: TextView, loaded: Boolean = false) {
        val bmp = composeIcon(waIcon, waCustom, waName?.text?.toString() ?: "")
        waPreview?.setImageBitmap(bmp)
        note.text = when { waCustom != null -> "Using your picture"; waIcon != null -> "Using the site icon"; loaded -> "No site icon found, using a letter. You can choose your own picture." ; else -> "Looking for the site icon..." }
    }
    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7301 && res == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().also { o -> o.inSampleSize = 2 }) }
                if (bmp != null) { waCustom = bmp; waNote?.let { refreshWaPreview(it) } } else Toast.makeText(this, "Could not read that picture.", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) { Toast.makeText(this, "Could not read that picture.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun pinWebApp(url: String, name: String, hideBar: Boolean) {
        try {
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
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon)).setIntent(intent).build()
            sm.requestPinShortcut(info, null)
            Toast.makeText(this, "Confirm adding it on the screen that appears.", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) { Toast.makeText(this, "Could not add it: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
    }

    // ---------- menu ----------
    private fun showMenu() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Forward" to { cur?.session?.goForward() })
        acts.add("Reload" to { cur?.session?.reload() })
        acts.add("Home" to { go("https://duckduckgo.com") })
        acts.add("Duck.ai (DuckDuckGo AI chat)" to { go("https://duck.ai") })
        acts.add("Hide top bar" to { setBarHidden(true) })
        acts.add("Make this site an app on the home screen" to { showWebApp() })
        acts.add("Tabs (" + tabs.size + ")" to { showTabs() })
        acts.add("Downloads" to { showDownloads() })
        acts.add("Bookmarks and history" to { showSaved() })
        acts.add("Add-ons" to { showAddons() })
        acts.add("Controller layout" to { showPadMenu() })
        acts.add("Desktop site: " + (if (prefs.getBoolean("desktop", false)) "ON" else "off") to { toggleDesktop() })
        acts.add("Graphics test" to { gfxTest() })
        acts.add("Graphics limit workaround: " + (if (prefs.getBoolean("gfxfix", false)) "ON" else "off") to { toggleGfx() })
        acts.add("Close browser" to { finish() })
        bd().setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun showPadMenu() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("Move and arrange buttons" to { startPad(true) })
        acts.add("Button size" to { bd().setTitle("Button size").setItems(arrayOf("Small", "Medium", "Large")) { _, k -> prefs.edit().putFloat("padSize", floatArrayOf(0.8f, 1f, 1.25f)[k]).apply(); pad?.rebuild() }.show() })
        acts.add("Button transparency" to { bd().setTitle("Button transparency").setItems(arrayOf("Very see-through", "See-through", "Solid")) { _, k -> prefs.edit().putFloat("padAlpha", floatArrayOf(0.3f, 0.55f, 0.9f)[k]).apply(); pad?.rebuild() }.show() })
        acts.add("Key for each button" to { bd().setTitle("Keys sent by the controller").setMessage(PadOverlay.DEFS.joinToString("\n") { it.label + "  =  " + it.keyName }).setPositiveButton("OK", null).show() })
        acts.add("Reset layout" to { prefs.edit().remove("padPos").apply(); pad?.rebuild(); Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show() })
        bd().setTitle("Controller layout").setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    /** Fullscreen mode: top bar hidden, on-screen controller shown. */
    private fun startPad(edit: Boolean = false) {
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
        box.addView(btn("New tab") { dlg.dismiss(); newTab("https://duckduckgo.com") })
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
    private fun toggleGfx() {
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

    private fun gfxTest() {
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
    private fun toggleDesktop() {
        val on = !prefs.getBoolean("desktop", false)
        prefs.edit().putBoolean("desktop", on).apply()
        tabs.forEach { applyDesktop(it) }
        cur?.session?.reload()
        Toast.makeText(this, if (on) "Desktop site on" else "Desktop site off", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { if (pad != null) { stopPad(); return true }; val t = cur; if (t != null && t.back) { t.session.goBack(); return true } else if (tabs.size > 1 && t != null) { closeTab(t); return true } }
        return super.onKeyDown(k, e)
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
