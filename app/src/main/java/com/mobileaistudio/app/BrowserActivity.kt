package com.mobileaistudio.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.mozilla.geckoview.*

/** The built-in Firefox-engine browser (GeckoView) with tabs and add-ons. */
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

    private val BG = Color.parseColor("#FAF9F5")
    private val INK = Color.parseColor("#1F1E1D")
    private val ACC = Color.parseColor("#D97757")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dp = resources.displayMetrics.density
        rt = Gk.get(this)
        rt.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(ext: WebExtension, perms: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val res = GeckoResult<WebExtension.PermissionPromptResponse>()
                runOnUiThread {
                    val all = (perms.toList() + origins.toList()).joinToString("\n• ", prefix = "• ").ifBlank { "No special permissions" }
                    AlertDialog.Builder(this@BrowserActivity).setTitle("Add " + (ext.metaData.name ?: "this add-on") + "?")
                        .setMessage("It will be allowed to:\n$all")
                        .setPositiveButton("Add") { _, _ -> res.complete(WebExtension.PermissionPromptResponse(true, false, true)) }
                        .setNegativeButton("Cancel") { _, _ -> res.complete(WebExtension.PermissionPromptResponse(false, false, false)) }
                        .setOnCancelListener { res.complete(WebExtension.PermissionPromptResponse(false, false, false)) }.show()
                }
                return res
            }
        })
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(BG)
        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL; top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(6), dp(30), dp(6), dp(4))
        top.addView(btn("←") { cur?.let { if (it.back) it.session.goBack() else finish() } })
        urlBox = EditText(this)
        urlBox.setSingleLine(); urlBox.hint = "Search or type an address"; urlBox.setTextColor(INK); urlBox.textSize = 15f
        urlBox.inputType = InputType.TYPE_TEXT_VARIATION_URI; urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setBackgroundColor(Color.parseColor("#EFEDE6")); urlBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        urlBox.setOnEditorActionListener { _, _, _ -> go(urlBox.text.toString()); true }
        urlBox.setOnFocusChangeListener { _, f -> if (f) urlBox.post { urlBox.selectAll() } }
        top.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btn("\uD83C\uDFAE") { startPad() })
        top.addView(btn("\u2630") { showMenu() })
        topBar = top
        root.addView(top)
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); prog.max = 100; prog.visibility = View.GONE
        prog.progressDrawable.setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN)
        root.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        gv = GeckoView(this)
        root.addView(gv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame = FrameLayout(this); frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        val first = intent?.dataString
        newTab(if (first.isNullOrBlank()) "https://duckduckgo.com" else first)
    }

    private fun dp(v: Int) = (v * dp).toInt()
    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this); b.text = t; b.isAllCaps = false; b.setTextColor(INK); b.textSize = 15f
        b.setBackgroundColor(Color.TRANSPARENT); b.minWidth = 0; b.minimumWidth = dp(44); b.setPadding(dp(8), 0, dp(8), 0)
        b.setOnClickListener { f() }; return b
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
                val nt = mkSession(); tabs.add(nt); runOnUiThread { select(nt); updCount() }
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
        val t = mkSession(); tabs.add(t); select(t); updCount(); t.session.loadUri(url)
    }
    private fun select(t: Tab) {
        cur = t; gv.setSession(t.session); urlBox.setText(t.url); urlBox.clearFocus()
    }
    private fun updCount() {}
    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i)
        if (tabs.isEmpty()) { finish(); return }
        if (t === cur) { gv.releaseSession(); select(tabs[minOf(i, tabs.size - 1)]) }
        t.session.close(); updCount()
    }

    private fun download(u: String) {
        try {
            val name = URLUtil.guessFileName(u, null, null)
            val req = DownloadManager.Request(Uri.parse(u)).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            runOnUiThread { Toast.makeText(this, "Downloading $name (saved in your Download folder)", Toast.LENGTH_LONG).show() }
        } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Could not download: " + e.message, Toast.LENGTH_LONG).show() } }
    }

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
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = AlertDialog.Builder(this).setTitle("Saved").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        val c = cur
        if (c != null && c.url.startsWith("http")) box.addView(btn(if (isMarked(c.url)) "Remove bookmark for this page" else "Bookmark this page") { toggleMark(c); dlg.dismiss() })
        fun list(head: String, key: String, empty: String, canRemove: Boolean) {
            box.addView(TextView(this).apply { text = head; setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, dp(4)) })
            val a = load(key)
            if (a.length() == 0) box.addView(TextView(this).apply { text = empty })
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val u = o.getString("u")
                val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                val tv = TextView(this); tv.text = o.optString("t").ifBlank { u } + "\n" + u; tv.maxLines = 2; tv.textSize = 13f; tv.setPadding(0, dp(8), 0, dp(8))
                tv.setOnClickListener { dlg.dismiss(); go(u) }
                row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (canRemove) row.addView(btn("\u2715") {
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

    private fun showMenu() {
        val items = arrayOf("Forward", "Reload", "Home", "Tabs (" + tabs.size + ")", "Bookmarks and history", "Add-ons", "Controller layout", "Desktop site: " + (if (prefs.getBoolean("desktop", false)) "ON" else "off"), "Close browser")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> cur?.session?.goForward()
                1 -> cur?.session?.reload()
                2 -> go("https://duckduckgo.com")
                3 -> showTabs()
                4 -> showSaved()
                5 -> showAddons()
                6 -> showPadMenu()
                7 -> toggleDesktop()
                8 -> finish()
            }
        }.show()
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

    private fun showPadMenu() {
        val items = arrayOf("Move and arrange buttons", "Button size", "Button transparency", "Key for each button", "Reset layout")
        AlertDialog.Builder(this).setTitle("Controller layout").setItems(items) { _, i ->
            when (i) {
                0 -> startPad(true)
                1 -> AlertDialog.Builder(this).setTitle("Button size").setItems(arrayOf("Small", "Medium", "Large")) { _, k -> prefs.edit().putFloat("padSize", floatArrayOf(0.8f, 1f, 1.25f)[k]).apply(); pad?.rebuild() }.show()
                2 -> AlertDialog.Builder(this).setTitle("Button transparency").setItems(arrayOf("Very see-through", "See-through", "Solid")) { _, k -> prefs.edit().putFloat("padAlpha", floatArrayOf(0.3f, 0.55f, 0.9f)[k]).apply(); pad?.rebuild() }.show()
                3 -> AlertDialog.Builder(this).setTitle("Keys sent by the controller").setMessage(PadOverlay.DEFS.joinToString("\n") { it.label + "  =  " + it.keyName }).setPositiveButton("OK", null).show()
                4 -> { prefs.edit().remove("padPos").apply(); pad?.rebuild(); Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show() }
            }
        }.show()
    }

    /** Fullscreen mode: top bar hidden, on-screen controller shown. */
    private fun startPad(edit: Boolean = false) {
        if (pad != null) { pad?.setEdit(edit); return }
        topBar.visibility = View.GONE; urlBox.clearFocus()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val p = PadOverlay(this, prefs, { code, down -> sendKey(code, down) }, { stopPad() })
        pad = p; frame.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); p.setEdit(edit)
    }
    private fun stopPad() {
        pad?.let { frame.removeView(it) }; pad = null
        topBar.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
    private fun sendKey(code: Int, down: Boolean) {
        val t = android.os.SystemClock.uptimeMillis()
        gv.dispatchKeyEvent(KeyEvent(t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0))
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

    private fun showTabs() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = AlertDialog.Builder(this).setTitle("Tabs").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        tabs.toList().forEach { t ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this); tv.text = (if (t === cur) "● " else "") + (t.title.ifBlank { t.url.ifBlank { "New tab" } }); tv.maxLines = 2; tv.setPadding(0, dp(10), 0, dp(10))
            if (t === cur) tv.setTypeface(null, Typeface.BOLD)
            tv.setOnClickListener { select(t); dlg.dismiss() }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("✕") { closeTab(t); dlg.dismiss(); if (tabs.isNotEmpty()) showTabs() })
            box.addView(row)
        }
        box.addView(btn("+ New tab") { dlg.dismiss(); newTab("https://duckduckgo.com") })
        dlg.show()
    }

    private val RECOMMENDED = listOf(
        Triple("uBlock Origin", "Blocks ads and trackers", "ublock-origin"),
        Triple("Bitwarden", "Password manager", "bitwarden-password-manager"),
        Triple("Dark Reader", "Dark mode for any site", "darkreader"),
        Triple("SponsorBlock", "Skips sponsor segments on YouTube", "sponsorblock")
    )

    private fun showAddons() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dlg = AlertDialog.Builder(this).setTitle("Add-ons").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        fun head(t: String) { box.addView(TextView(this).apply { text = t; setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, dp(4)) }) }
        head("Installed")
        val inst = LinearLayout(this); inst.orientation = LinearLayout.VERTICAL; box.addView(inst)
        inst.addView(TextView(this).apply { text = "Loading..." })
        rt.webExtensionController.list().accept({ list ->
            runOnUiThread {
                inst.removeAllViews()
                val mine = (list ?: emptyList()).filter { !it.isBuiltIn }
                if (mine.isEmpty()) inst.addView(TextView(this).apply { text = "None yet. Add one below." })
                mine.forEach { e ->
                    val on = e.metaData.enabled
                    val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
                    val tv = TextView(this); tv.text = (e.metaData.name ?: e.id) + "  " + (e.metaData.version ?: "") + (if (on) "" else "  (off)")
                    row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(btn(if (on) "Turn off" else "Turn on") {
                        val r = if (on) rt.webExtensionController.disable(e, WebExtensionController.EnableSource.USER) else rt.webExtensionController.enable(e, WebExtensionController.EnableSource.USER)
                        r.accept({ runOnUiThread { dlg.dismiss(); showAddons() } }, { _ -> })
                    })
                    row.addView(btn("Remove") {
                        AlertDialog.Builder(this).setTitle("Remove " + (e.metaData.name ?: e.id) + "?").setPositiveButton("Remove") { _, _ ->
                            rt.webExtensionController.uninstall(e).accept({ runOnUiThread { dlg.dismiss(); showAddons() } }, { _ -> })
                        }.setNegativeButton("Cancel", null).show()
                    })
                    inst.addView(row)
                }
            }
        }, { _ -> })
        head("Add from the Firefox add-on store")
        RECOMMENDED.forEach { (name, sub, slug) ->
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this); tv.text = name + "\n" + sub; tv.textSize = 14f
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Add") { install("https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi", dlg) })
            box.addView(row)
        }
        head("Or paste an add-on link (.xpi)")
        val et = EditText(this); et.hint = "https://.../addon.xpi"; et.setSingleLine(); box.addView(et)
        box.addView(btn("Add from link") { val u = et.text.toString().trim(); if (u.isNotEmpty()) install(u, dlg) })
        box.addView(TextView(this).apply { text = "Only add-ons you trust. They can read the pages you visit."; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        dlg.show()
    }

    private fun install(url: String, dlg: AlertDialog) {
        Toast.makeText(this, "Getting the add-on...", Toast.LENGTH_SHORT).show()
        rt.webExtensionController.install(url).accept({ e ->
            runOnUiThread { Toast.makeText(this, "Added " + (e?.metaData?.name ?: "add-on"), Toast.LENGTH_SHORT).show(); dlg.dismiss(); showAddons() }
        }, { err -> runOnUiThread { Toast.makeText(this, "Could not add it: " + (err?.message ?: "unknown error"), Toast.LENGTH_LONG).show() } })
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK) { if (pad != null) { stopPad(); return true }; val t = cur; if (t != null && t.back) { t.session.goBack(); return true } else if (tabs.size > 1 && t != null) { closeTab(t); return true } }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { tabs.forEach { try { it.session.close() } catch (_: Throwable) {} }; super.onDestroy() }
}


/** One on-screen button of the controller. */
class PadDef(val id: String, val label: String, val key: Int, val keyName: String, val x: Float, val y: Float)

/** The overlay controller: buttons sit on top of the page and send key presses. */
class PadOverlay(private val act: Activity, private val prefs: android.content.SharedPreferences, private val onKey: (Int, Boolean) -> Unit, private val onClose: () -> Unit) : FrameLayout(act) {
    companion object {
        val DEFS = listOf(
            PadDef("up", "\u25B2", KeyEvent.KEYCODE_W, "W", 0.13f, 0.52f),
            PadDef("left", "\u25C0", KeyEvent.KEYCODE_A, "A", 0.06f, 0.68f),
            PadDef("right", "\u25B6", KeyEvent.KEYCODE_D, "D", 0.20f, 0.68f),
            PadDef("down", "\u25BC", KeyEvent.KEYCODE_S, "S", 0.13f, 0.84f),
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
    private val views = ArrayList<Pair<PadDef, TextView>>()
    private var closeBtn: TextView? = null
    private var doneBtn: TextView? = null

    init { rebuild() }

    private fun pos(): org.json.JSONObject = try { org.json.JSONObject(prefs.getString("padPos", "{}")) } catch (e: Throwable) { org.json.JSONObject() }

    fun setEdit(on: Boolean) { edit = on; rebuild() }

    fun rebuild() {
        removeAllViews(); views.clear()
        val size = (56 * dp * prefs.getFloat("padSize", 1f)).toInt()
        val alpha = prefs.getFloat("padAlpha", 0.55f)
        val saved = pos()
        for (d in DEFS) {
            val tv = TextView(act); tv.text = d.label; tv.gravity = Gravity.CENTER; tv.setTextColor(Color.WHITE); tv.textSize = if (d.label.length > 2) 11f else 17f
            tv.setTypeface(null, Typeface.BOLD)
            val bg = android.graphics.drawable.GradientDrawable(); bg.shape = android.graphics.drawable.GradientDrawable.OVAL; bg.setColor(Color.parseColor("#33322F")); bg.setStroke((2 * dp).toInt(), Color.parseColor("#D97757"))
            tv.background = bg; tv.alpha = alpha
            val w = if (d.id == "SEL" || d.id == "STA") (size * 1.5f).toInt() else size
            val h = if (d.id == "SEL" || d.id == "STA") (size * 0.6f).toInt() else size
            if (w != h) bg.shape = android.graphics.drawable.GradientDrawable.RECTANGLE.also { bg.cornerRadius = h / 2f }
            addView(tv, LayoutParams(w, h))
            views.add(d to tv)
            val fx = if (saved.has(d.id)) saved.getJSONArray(d.id).getDouble(0).toFloat() else d.x
            val fy = if (saved.has(d.id)) saved.getJSONArray(d.id).getDouble(1).toFloat() else d.y
            tv.tag = floatArrayOf(fx, fy)
            tv.setOnTouchListener { v, e -> touch(d, v as TextView, e) }
        }
        val c = TextView(act); c.text = "\u2715  Close controller"; c.setTextColor(Color.WHITE); c.textSize = 13f; c.gravity = Gravity.CENTER
        c.setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt()); c.setBackgroundColor(Color.parseColor("#CC1F1E1D")); c.setOnClickListener { onClose() }
        addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = (24 * dp).toInt() })
        closeBtn = c
        if (edit) {
            c.visibility = GONE
            val dn = TextView(act); dn.text = "Done - drag buttons to move them"; dn.setTextColor(Color.WHITE); dn.textSize = 13f; dn.gravity = Gravity.CENTER
            dn.setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt()); dn.setBackgroundColor(Color.parseColor("#D97757")); dn.setOnClickListener { edit = false; rebuild() }
            addView(dn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = (24 * dp).toInt() })
            doneBtn = dn
            setBackgroundColor(Color.parseColor("#44000000"))
        } else setBackgroundColor(Color.TRANSPARENT)
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val W = r - l; val H = b - t
        for ((_, tv) in views) {
            val f = tv.tag as FloatArray
            val x = (f[0] * W - tv.measuredWidth / 2f).toInt(); val y = (f[1] * H - tv.measuredHeight / 2f).toInt()
            tv.layout(x, y, x + tv.measuredWidth, y + tv.measuredHeight)
        }
    }

    // buttons only grab touches they sit on; the rest of the screen still reaches the page
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = false

    private fun touch(d: PadDef, v: TextView, e: android.view.MotionEvent): Boolean {
        if (edit) {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    val W = width.toFloat(); val H = height.toFloat()
                    val cx = (v.left + e.x).coerceIn(0f, W); val cy = (v.top + e.y).coerceIn(0f, H)
                    v.tag = floatArrayOf(cx / W, cy / H); requestLayout()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val o = pos(); val f = v.tag as FloatArray
                    o.put(d.id, org.json.JSONArray().put(f[0].toDouble()).put(f[1].toDouble()))
                    prefs.edit().putString("padPos", o.toString()).apply()
                }
            }
            return true
        }
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { v.alpha = 1f; v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); onKey(d.key, true) }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.alpha = prefs.getFloat("padAlpha", 0.55f); onKey(d.key, false) }
        }
        return true
    }
}
