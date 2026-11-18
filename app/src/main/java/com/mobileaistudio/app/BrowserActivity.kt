package com.mobileaistudio.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
    private lateinit var tabBtn: Button
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
        top.addView(btn("↻") { cur?.session?.reload() })
        top.addView(btn("✕") { finish() })
        root.addView(top)
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); prog.max = 100; prog.visibility = View.GONE
        prog.progressDrawable.setColorFilter(ACC, android.graphics.PorterDuff.Mode.SRC_IN)
        root.addView(prog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        gv = GeckoView(this)
        root.addView(gv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER
        bar.setPadding(0, dp(4), 0, dp(4))
        bar.addView(btn("→") { cur?.session?.goForward() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("⌂") { go("https://duckduckgo.com") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBtn = btn("1") { showTabs() }
        bar.addView(tabBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("⚙ Add-ons") { showAddons() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(bar)
        setContentView(root)
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
            override fun onTitleChange(session: GeckoSession, title: String?) { t.title = title ?: "" }
            override fun onCloseRequest(session: GeckoSession) { runOnUiThread { closeTab(t) } }
        }
        s.open(rt)
        return t
    }

    private fun newTab(url: String) {
        val t = mkSession(); tabs.add(t); select(t); updCount(); t.session.loadUri(url)
    }
    private fun select(t: Tab) {
        cur = t; gv.setSession(t.session); urlBox.setText(t.url); urlBox.clearFocus()
    }
    private fun updCount() { tabBtn.text = "⧉ " + tabs.size }
    private fun closeTab(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i)
        if (tabs.isEmpty()) { finish(); return }
        if (t === cur) { gv.releaseSession(); select(tabs[minOf(i, tabs.size - 1)]) }
        t.session.close(); updCount()
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
        if (k == KeyEvent.KEYCODE_BACK) { val t = cur; if (t != null && t.back) { t.session.goBack(); return true } else if (tabs.size > 1 && t != null) { closeTab(t); return true } }
        return super.onKeyDown(k, e)
    }

    override fun onDestroy() { tabs.forEach { try { it.session.close() } catch (_: Throwable) {} }; super.onDestroy() }
}
