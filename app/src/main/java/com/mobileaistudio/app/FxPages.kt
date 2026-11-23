package com.mobileaistudio.app

import org.mozilla.geckoview.ContentBlocking.AntiTracking as AT
import org.mozilla.geckoview.ContentBlocking.CookieBehavior as CB
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A search engine: name, search address (the words are added at the end, or replace %s), suggestion address and a letter badge. */
class Eng(val name: String, val url: String, val letter: String, val sug: String = "") {
    fun searchUrl(q: String): String {
        val e = java.net.URLEncoder.encode(q, "UTF-8")
        return if (url.contains("%s")) url.replace("%s", e) else url + e
    }
}

private class MaxScroll(c: Context, private val maxH: Int) : ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)) }
}

/** Every full-screen page of the browser: menu sheet, Settings and its sub-pages, History, Bookmarks, Downloads, Extensions. */
class FxPages(private val a: BrowserActivity, private val fx: Fx) {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val th: BTheme get() = a.th
    private fun dp(v: Int) = a.dp(v)
    private fun bool(k: String, d: Boolean) = a.prefs.getBoolean(k, d)
    private fun int(k: String, d: Int) = a.prefs.getInt(k, d)
    private fun put(k: String, v: Boolean) { a.prefs.edit().putBoolean(k, v).apply() }
    private fun put(k: String, v: Int) { a.prefs.edit().putInt(k, v).apply() }

    // ---------- search engines ----------
    val builtin = listOf(
        Eng("Google", "https://www.google.com/search?q=", "G", "https://suggestqueries.google.com/complete/search?client=firefox&q="),
        Eng("DuckDuckGo", "https://duckduckgo.com/?q=", "D", "https://duckduckgo.com/ac/?type=list&q="),
        Eng("Bing", "https://www.bing.com/search?q=", "B", "https://api.bing.com/osjson.aspx?query="),
        Eng("Brave", "https://search.brave.com/search?q=", "B", "https://search.brave.com/api/suggest?q="),
        Eng("Ecosia", "https://www.ecosia.org/search?q=", "E", "https://ac.ecosia.org/autocomplete?type=list&q="),
        Eng("Wikipedia", "https://en.wikipedia.org/w/index.php?search=", "W", "https://en.wikipedia.org/w/api.php?action=opensearch&format=json&limit=6&search=")
    )
    private fun custom(): List<Eng> {
        val a2 = try { JSONArray(a.prefs.getString("engines_custom", "[]")) } catch (e: Throwable) { JSONArray() }
        return (0 until a2.length()).map { val o = a2.getJSONObject(it); Eng(o.getString("n"), o.getString("u"), o.getString("n").take(1).uppercase()) }
    }
    fun allEngines(): List<Eng> = builtin + custom()
    private fun hidden(): Set<String> = a.prefs.getStringSet("engines_hidden", emptySet()) ?: emptySet()
    fun visibleEngines(): List<Eng> = allEngines().filter { it.name !in hidden() || it.name == engine().name }
    fun engine(): Eng { val n = a.prefs.getString("engine_name", "DuckDuckGo"); return allEngines().firstOrNull { it.name == n } ?: builtin[1] }

    // ---------- applying settings to the browser engine ----------
    fun applyAll() {
        val s = a.rt.settings
        try {
            s.setAllowInsecureConnections(when (int("httpsOnly", 0)) { 1 -> GeckoRuntimeSettings.HTTPS_ONLY; 2 -> GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE; else -> 0 })
            val doh = int("doh", 0)
            s.setTrustedRecursiveResolverUri("https://mozilla.cloudflare-dns.com/dns-query")
            s.setTrustedRecursiveResolverMode(when (doh) { 1 -> GeckoRuntimeSettings.TRR_MODE_FIRST; 2 -> GeckoRuntimeSettings.TRR_MODE_ONLY; 3 -> GeckoRuntimeSettings.TRR_MODE_DISABLED; else -> GeckoRuntimeSettings.TRR_MODE_OFF })
        } catch (_: Throwable) {}
        applyEtp()
        try {
            s.setAutomaticFontSizeAdjustment(bool("autoFont", true))
            if (!bool("autoFont", true)) s.setFontSizeFactor(int("fontPct", 100) / 100f)
            s.setForceUserScalableEnabled(bool("forceZoom", false))
            s.setRemoteDebuggingEnabled(bool("usbDebug", false))
        } catch (_: Throwable) {}
        applyLocale()
    }

    fun applyEtp() {
        try {
            val cb = a.rt.settings.contentBlocking
            when (int("etp", 0)) {
                0 -> { cb.setAntiTracking(AT.DEFAULT); cb.setCookieBehavior(CB.ACCEPT_NON_TRACKERS) }
                1 -> { cb.setAntiTracking(AT.STRICT); cb.setCookieBehavior(CB.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS) }
                else -> {
                    var m = 0
                    if (bool("etp_social", true)) m = m or AT.SOCIAL
                    if (bool("etp_content", false)) m = m or AT.CONTENT or AT.AD or AT.ANALYTIC
                    if (bool("etp_crypto", true)) m = m or AT.CRYPTOMINING
                    if (bool("etp_finger", true)) m = m or AT.FINGERPRINTING
                    cb.setAntiTracking(m)
                    cb.setCookieBehavior(when (int("etp_cookies", 1)) { 0 -> CB.ACCEPT_ALL; 1 -> CB.ACCEPT_NON_TRACKERS; 2 -> CB.ACCEPT_FIRST_PARTY; else -> CB.ACCEPT_NONE })
                }
            }
        } catch (_: Throwable) {}
    }

    private val LANGS = listOf("Follow device language" to "", "English" to "en", "English (UK)" to "en-GB", "Afrikaans" to "af", "isiZulu" to "zu", "Francais" to "fr", "Deutsch" to "de",
        "Espanol" to "es", "Portugues (Brasil)" to "pt-BR", "Italiano" to "it", "Nederlands" to "nl", "Russkij" to "ru", "Hindi" to "hi", "Arabic" to "ar", "Chinese (Simplified)" to "zh-CN", "Japanese" to "ja")
    private fun applyLocale() {
        try {
            val c = a.prefs.getString("lang", "") ?: ""
            a.rt.settings.setLocales(arrayOf(if (c.isEmpty()) Locale.getDefault().toLanguageTag() else c))
        } catch (_: Throwable) {}
    }

    // ---------- the bottom menu sheet ----------
    fun menu() {
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
        val sheet = LinearLayout(a); sheet.orientation = LinearLayout.VERTICAL
        sheet.background = GradientDrawable().also { it.setColor(th.bg); val r = dp(22).toFloat(); it.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        val body = LinearLayout(a); body.orientation = LinearLayout.VERTICAL; body.setPadding(dp(10), dp(8), dp(10), dp(14))
        val grab = View(a); grab.background = fx.round(th.mute, 3)
        body.addView(grab, LinearLayout.LayoutParams(dp(40), dp(5)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(10) })

        fun card(): LinearLayout { val c = LinearLayout(a); c.orientation = LinearLayout.VERTICAL; c.background = fx.round(th.side, 18); body.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8) }); return c }
        fun act(f: () -> Unit): () -> Unit = { d.dismiss(); f() }

        val t = a.cur
        val web = t != null && t.url.startsWith("http")
        if (web) {
            val q = LinearLayout(a); q.orientation = LinearLayout.HORIZONTAL; q.background = fx.round(th.side, 18)
            fun quick(ico: Int, name: String, tint: Int = th.txt, f: () -> Unit) {
                val c = LinearLayout(a); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER; c.setPadding(0, dp(12), 0, dp(10))
                val i = ImageView(a); i.setImageResource(ico); i.setColorFilter(tint, PorterDuff.Mode.SRC_IN); c.addView(i, LinearLayout.LayoutParams(dp(24), dp(24)))
                c.addView(fx.tv(name, 12f, th.txt).also { it.setPadding(0, dp(4), 0, 0); it.gravity = Gravity.CENTER })
                c.setOnClickListener(View.OnClickListener { act(f)() }); q.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            quick(R.drawable.bt_forward, "Forward") { a.cur?.session?.goForward() }
            quick(R.drawable.bt_reload, "Reload") { a.cur?.session?.reload() }
            quick(R.drawable.bt_star, "Bookmark", if (a.isMarked(t!!.url)) th.acc else th.txt) { a.cur?.let { a.toggleMark(it) } }
            quick(R.drawable.bt_share, "Share") {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t!!.url)
                a.startActivity(Intent.createChooser(i, "Share"))
            }
            body.addView(q, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(2) })
        }

        val c1 = card(); fx.row(c1, "Extensions", null, R.drawable.bt_puzzle, act { extensions() })

        val tiles = LinearLayout(a); tiles.orientation = LinearLayout.HORIZONTAL
        fun tile(ico: Int, name: String, f: () -> Unit) {
            val c = LinearLayout(a); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER; c.setPadding(0, dp(14), 0, dp(12)); c.background = fx.round(th.side, 18)
            val i = ImageView(a); i.setImageResource(ico); i.setColorFilter(th.txt, PorterDuff.Mode.SRC_IN); c.addView(i, LinearLayout.LayoutParams(dp(26), dp(26)))
            c.addView(fx.tv(name, 12.5f, th.txt).also { it.setPadding(0, dp(6), 0, 0); it.gravity = Gravity.CENTER; it.setSingleLine() })
            c.setOnClickListener(View.OnClickListener { act(f)() })
            tiles.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        tile(R.drawable.bt_history, "History") { history() }
        tile(R.drawable.bt_star, "Bookmarks") { bookmarks() }
        tile(R.drawable.bt_download, "Downloads") { downloads() }
        tile(R.drawable.bt_mask, "Private") { a.newTab("about:home", true) }
        body.addView(tiles, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(8) })

        val c2 = card()
        fx.row(c2, "Customise homepage", null, R.drawable.bt_home, act { homeSettings() })
        fx.row(c2, "Settings", null, R.drawable.bt_gear, act { settings() })

        fx.header(body, "More")
        val c3 = card()
        fx.row(c3, "New tab", null, R.drawable.bt_plus, act { a.newTab("about:home") })
        fx.switchRow(c3, "Desktop site", null, bool("desktop", false)) { d.dismiss(); a.toggleDesktop() }
        fx.row(c3, "Make this site an app on the home screen", null, R.drawable.bt_globe, act { a.showWebApp() })
        fx.row(c3, "Duck.ai (DuckDuckGo AI chat)", null, R.drawable.bt_search, act { a.go("https://duck.ai") })
        if (!a.appMode) fx.row(c3, "Hide top bar", null, R.drawable.bt_up, act { a.setBarHidden(true) })
        fx.row(c3, "Game controller (full screen)", null, R.drawable.bt_gamepad, act { a.startPad() })
        fx.row(c3, "Controller layout", null, R.drawable.bt_gamepad, act { a.showPadMenu() })
        fx.row(c3, "Graphics test", null, R.drawable.bt_gear, act { a.gfxTest() })
        fx.row(c3, "Graphics limit workaround: " + (if (bool("gfxfix", false)) "ON" else "off"), null, R.drawable.bt_gear, act { a.toggleGfx() })
        fx.row(c3, "Quit", null, R.drawable.bt_close, act { a.quit() })

        val maxH = (a.resources.displayMetrics.heightPixels * 0.88).toInt()
        sheet.addView(MaxScroll(a, maxH).also { it.addView(body) }, LinearLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(sheet)
        d.window?.let { w ->
            w.setGravity(Gravity.BOTTOM); w.setLayout(MATCH, WRAP)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); w.setDimAmount(0.45f); w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.setCanceledOnTouchOutside(true)
        d.show()
    }

    // ---------- Settings ----------
    private fun isDefaultBrowser(): Boolean = try { Build.VERSION.SDK_INT >= 29 && a.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) } catch (e: Throwable) { false }
    private fun askDefaultBrowser() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(android.app.role.RoleManager::class.java)
                if (rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                    a.onRole = { a.popPage(); settings() }
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER), 7302); return
                }
            }
            a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Throwable) { fx.toast("Open your phone's Settings, Apps, Default apps.") }
    }

    private fun openingName() = listOf("Open on homepage", "Open on last tab", "Open on homepage after four hours")[int("opening", 1).coerceIn(0, 2)]
    private fun tabsName() = listOf("Close manually", "Close after one day", "Close after one week", "Close after one month")[int("closeAfter", 0).coerceIn(0, 3)]
    private fun etpName() = listOf("Standard", "Strict", "Custom")[int("etp", 0).coerceIn(0, 2)]
    private fun httpsName() = listOf("Off", "On in all tabs", "On in private tabs")[int("httpsOnly", 0).coerceIn(0, 2)]
    private fun dohName() = listOf("Default Protection", "Increased Protection", "Max Protection", "Off")[int("doh", 0).coerceIn(0, 3)]
    private fun openAppsName() = listOf("Always", "Ask before opening", "Never")[int("openApps", 1).coerceIn(0, 2)]

    fun settings() {
        a.pushPage(fx.page("Settings") { b ->
            fx.header(b, "General")
            fx.row(b, "Search", engine().name) { searchSettings() }
            fx.row(b, "Tabs", tabsName()) { tabsSettings() }
            fx.row(b, "Homepage", openingName()) { homeSettings() }
            fx.row(b, "Customise") { customise() }
            fx.row(b, "Accessibility") { accessibility() }
            fx.row(b, "Language", LANGS.firstOrNull { it.second == (a.prefs.getString("lang", "") ?: "") }?.first ?: "Follow device language") { language() }
            fx.switchRow(b, "Set as default browser", null, isDefaultBrowser()) { v -> if (v) askDefaultBrowser() else a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            fx.divider(b)
            fx.header(b, "Privacy and security")
            fx.row(b, "Private browsing") { privateSettings() }
            fx.row(b, "HTTPS-Only Mode", httpsName()) { httpsSettings() }
            fx.row(b, "DNS over HTTPS", dohName()) { dohSettings() }
            fx.row(b, "Enhanced Tracking Protection", etpName()) { etpSettings() }
            fx.row(b, "Site settings") { siteSettings() }
            fx.row(b, "Delete browsing data") { deleteData() }
            fx.row(b, "Delete browsing data on quit", if (bool("doq", false)) "On" else "Off") { deleteOnQuit() }
            fx.divider(b)
            fx.header(b, "Advanced")
            fx.row(b, "Extensions") { extensions() }
            fx.row(b, "Open links in apps", openAppsName()) { openLinks() }
            fx.row(b, "Download Settings") { downloadSettings() }
            fx.switchRow(b, "Remote debugging via USB", null, bool("usbDebug", false)) { put("usbDebug", it); applyAll() }
            fx.divider(b)
            fx.header(b, "About")
            fx.row(b, "About this browser") { about() }
        })
    }

    private fun about() {
        val v = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (e: Throwable) { "" }
        a.pushPage(fx.page("About") { b ->
            fx.row(b, "Mobile AI Studio", "Version $v")
            fx.row(b, "Browser engine", "Firefox engine (GeckoView 160)")
            fx.row(b, "Your data", "Bookmarks, history and settings stay on this phone. Nothing is sent to a Mozilla account.")
        })
    }

    // ---- Search ----
    fun searchSettings() {
        a.pushPage(fx.page("Search") { b ->
            fx.header(b, "Search engines")
            fx.row(b, "Default search engine", engine().name) {
                val l = allEngines(); a.bd().setTitle("Default search engine").setItems(l.map { it.name }.toTypedArray()) { _, i -> a.prefs.edit().putString("engine_name", l[i].name).apply(); a.popPage(); searchSettings() }.show()
            }
            fx.row(b, "Manage alternative search engines", "Edit engines visible in the search menu") { manageEngines() }
            fx.switchRow(b, "Enable Google Lens search", "Sends images and photos to Google for visual search.", bool("lens", true)) { put("lens", it) }
            fx.switchRow(b, "Home screen widget", null, bool("hs_widget", false)) { put("hs_widget", it); if (it) fx.toast("Add the widget from your phone's widget list") }
            fx.divider(b)
            fx.header(b, "Suggestions from search engines")
            fx.switchRow(b, "Show search suggestions", null, bool("sugg", true)) { put("sugg", it) }
            fx.checkRow(b, "Show in private sessions", bool("sugg_priv", false), 24) { put("sugg_priv", it) }
            fx.checkRow(b, "Show trending suggestions", bool("sugg_trend", true), 24) { put("sugg_trend", it) }
            fx.switchRow(b, "Show recent searches", null, bool("recent_searches", true)) { put("recent_searches", it) }
            fx.divider(b)
            fx.header(b, "Address bar - Firefox Suggest")
            fx.switchRow(b, "Search browsing history", null, bool("sg_hist", true)) { put("sg_hist", it) }
            fx.switchRow(b, "Search bookmarks", null, bool("sg_bm", true)) { put("sg_bm", it) }
            fx.header(b, "Address bar preferences")
            fx.switchRow(b, "Show clipboard suggestions", null, bool("sg_clip", true)) { put("sg_clip", it) }
            fx.switchRow(b, "Show voice search", null, bool("sg_voice", true)) { put("sg_voice", it); a.refreshBar() }
            fx.switchRow(b, "Autocomplete URLs", null, bool("sg_auto", true)) { put("sg_auto", it) }
        })
    }

    private fun manageEngines() {
        a.pushPage(fx.page("Search engines", listOf(R.drawable.bt_plus to { addEngine() })) { b ->
            fx.header(b, "Shown in the search menu")
            allEngines().forEach { e ->
                fx.checkRow(b, e.name, e.name !in hidden()) { on ->
                    val s = hidden().toMutableSet(); if (on) s.remove(e.name) else s.add(e.name)
                    a.prefs.edit().putStringSet("engines_hidden", s).apply()
                }
            }
        })
    }

    private fun addEngine() {
        val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL; box.setPadding(dp(20), dp(8), dp(20), 0)
        val n = EditText(a); n.hint = "Name"; n.setSingleLine(); val u = EditText(a); u.hint = "Address with %s where the words go"; u.setSingleLine()
        n.setTextColor(th.txt); u.setTextColor(th.txt); n.setHintTextColor(th.mute); u.setHintTextColor(th.mute)
        box.addView(n); box.addView(u)
        a.bd().setTitle("Add search engine").setView(box).setPositiveButton("Add") { _, _ ->
            val name = n.text.toString().trim(); var url = u.text.toString().trim()
            if (name.isEmpty() || !url.startsWith("http")) { fx.toast("Needs a name and an address that starts with https://"); return@setPositiveButton }
            if (!url.contains("%s")) url += "%s"
            val arr = try { JSONArray(a.prefs.getString("engines_custom", "[]")) } catch (e: Throwable) { JSONArray() }
            arr.put(JSONObject().put("n", name).put("u", url)); a.prefs.edit().putString("engines_custom", arr.toString()).apply()
            a.popPage(); manageEngines()
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- Tabs ----
    fun tabsSettings() {
        a.pushPage(fx.page("Tabs") { b ->
            fx.header(b, "Tab layout")
            fx.radios(b, listOf("List" to null, "Grid" to null), if (bool("tabGrid", true)) 1 else 0) { put("tabGrid", it == 1) }
            fx.divider(b)
            fx.header(b, "Close tabs")
            fx.radios(b, listOf("Never" to null, "After one day" to null, "After one week" to null, "After one month" to null), int("closeAfter", 0)) { put("closeAfter", it) }
            fx.divider(b)
            fx.header(b, "Tab organisation")
            fx.switchRow(b, "Inactive tabs", "Tabs you haven't viewed for two weeks get moved to the inactive section.", bool("inactiveTabs", true)) { put("inactiveTabs", it) }
            fx.divider(b)
            fx.header(b, "Privacy report")
            fx.switchRow(b, "Enable privacy report", null, bool("hp_privacy", true)) { put("hp_privacy", it) }
        })
    }

    // ---- Homepage ----
    fun homeSettings() {
        a.pushPage(fx.page("Homepage") { b ->
            fx.switchRow(b, "Shortcuts", null, bool("hp_tiles", true)) { put("hp_tiles", it) }
            fx.switchRow(b, "Privacy report", null, bool("hp_privacy", true)) { put("hp_privacy", it) }
            fx.switchRow(b, "Continue", null, bool("hp_continue", true)) { put("hp_continue", it) }
            fx.switchRow(b, "Bookmarks", null, bool("hp_bm", false)) { put("hp_bm", it) }
            fx.switchRow(b, "Recently visited", null, bool("hp_recent", false)) { put("hp_recent", it) }
            fx.divider(b)
            fx.header(b, "Opening screen")
            fx.radios(b, listOf("Homepage" to null, "Last tab" to null, "Homepage after four hours of inactivity" to null), int("opening", 1)) { put("opening", it) }
        })
    }

    // ---- Customise ----
    fun customise() {
        a.pushPage(fx.page("Customise") { b ->
            fx.header(b, "Theme")
            fx.radios(b, listOf("Light" to null, "Dark" to null, "Follow the app theme" to null), when (int("theme", 0)) { 1 -> 0; 2 -> 1; else -> 2 }) {
                put("theme", when (it) { 0 -> 1; 1 -> 2; else -> 0 }); a.restyle()
            }
            fx.divider(b)
            fx.header(b, "Address bar location")
            fx.radios(b, listOf("Top" to null, "Bottom" to null), if (bool("barBottom", false)) 1 else 0) { put("barBottom", it == 1); a.placeBar() }
            fx.divider(b)
            fx.header(b, "Toolbar layout")
            val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER; row.setPadding(0, dp(8), 0, dp(16))
            fun thumb(label: String, expanded: Boolean) {
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
                val sel = bool("toolbarExpanded", false) == expanded
                val ph = LinearLayout(a); ph.orientation = LinearLayout.VERTICAL; ph.setPadding(dp(5), dp(5), dp(5), dp(5))
                ph.background = GradientDrawable().also { it.setColor(th.side); it.setStroke(dp(if (sel) 3 else 2), if (sel) th.acc else th.line); it.cornerRadius = dp(10).toFloat() }
                val top = View(a); top.background = fx.round(if (sel) th.acc else th.line, 4); ph.addView(top, LinearLayout.LayoutParams(MATCH, dp(16)))
                ph.addView(View(a), LinearLayout.LayoutParams(MATCH, 0, 1f))
                if (expanded) { val bt = View(a); bt.background = fx.round(if (sel) th.acc else th.line, 4); ph.addView(bt, LinearLayout.LayoutParams(MATCH, dp(16))) }
                col.addView(ph, LinearLayout.LayoutParams(dp(96), dp(160)))
                col.addView(fx.tv(label, 15f).also { it.setPadding(0, dp(8), 0, 0) })
                col.setOnClickListener { put("toolbarExpanded", expanded); a.placeBar(); a.popPage(); customise() }
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            thumb("Simple", false); thumb("Expanded", true)
            b.addView(row)
        })
    }

    // ---- Accessibility ----
    fun accessibility() {
        a.pushPage(fx.page("Accessibility") { b ->
            fx.switchRow(b, "Automatic font sizing", "Follows the font size of your phone.", bool("autoFont", true)) { put("autoFont", it); applyAll(); a.popPage(); accessibility() }
            if (!bool("autoFont", true)) {
                val pct = int("fontPct", 100)
                val lab = fx.tv("Font size: $pct%", 16f); lab.setPadding(dp(16), dp(10), dp(16), 0); b.addView(lab)
                val sb = SeekBar(a); sb.max = 15; sb.progress = ((pct - 50) / 10).coerceIn(0, 15)
                sb.progressTintList = android.content.res.ColorStateList.valueOf(th.acc); sb.thumbTintList = android.content.res.ColorStateList.valueOf(th.acc)
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { lab.text = "Font size: " + (50 + p * 10) + "%" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { put("fontPct", 50 + sb.progress * 10); applyAll() }
                })
                sb.setPadding(dp(24), dp(8), dp(24), dp(8)); b.addView(sb)
                val sample = fx.tv("This is how text on websites will look.", 16f); sample.setPadding(dp(16), 0, dp(16), dp(10)); b.addView(sample)
            }
            fx.divider(b)
            fx.switchRow(b, "Zoom on all websites", "Lets you pinch to zoom on pages that block it.", bool("forceZoom", false)) { put("forceZoom", it); applyAll() }
        })
    }

    // ---- Language ----
    fun language() {
        a.pushPage(fx.page("Language") { b ->
            val cur = a.prefs.getString("lang", "") ?: ""
            fx.radios(b, LANGS.map { it.first to null }, LANGS.indexOfFirst { it.second == cur }.coerceAtLeast(0)) {
                a.prefs.edit().putString("lang", LANGS[it].second).apply(); applyLocale(); fx.toast("Websites will use this language from now on.")
            }
        })
    }

    // ---- Private browsing ----
    fun privateSettings() {
        a.pushPage(fx.page("Private browsing") { b ->
            fx.switchRow(b, "Open links in a private tab", "Links from other apps open in a private tab.", bool("linksPrivate", false)) { put("linksPrivate", it) }
            fx.switchRow(b, "Allow screenshots in private browsing", "Lets screenshots and screen recording see private tabs.", bool("privShots", false)) { put("privShots", it); a.applySecure() }
            fx.header(b, "Private tabs")
            fx.row(b, "Open a private tab", "Nothing is saved: no history, cookies or searches.") { a.popPage(); a.newTab("about:home", true) }
        })
    }

    // ---- HTTPS-Only / DoH / ETP ----
    fun httpsSettings() {
        a.pushPage(fx.page("HTTPS-Only Mode") { b ->
            val t = fx.tv("Websites load over a secure connection when they can. If a site has none, you get a warning first.", 14f, th.mute); t.setPadding(dp(16), dp(10), dp(16), dp(6)); b.addView(t)
            fx.radios(b, listOf("Off" to null, "On in all tabs" to null, "On in private tabs only" to null), int("httpsOnly", 0)) { put("httpsOnly", it); applyAll() }
        })
    }
    fun dohSettings() {
        a.pushPage(fx.page("DNS over HTTPS") { b ->
            val t = fx.tv("Encrypts the lookups that turn website names into addresses, so others on the network cannot see which sites you visit.", 14f, th.mute); t.setPadding(dp(16), dp(10), dp(16), dp(6)); b.addView(t)
            fx.radios(b, listOf(
                "Default Protection" to "Uses your network's own lookups.",
                "Increased Protection" to "Uses encrypted lookups when possible, and falls back to your network.",
                "Max Protection" to "Always uses encrypted lookups. Some sites may not load.",
                "Off" to "Never uses encrypted lookups."), int("doh", 0)) { put("doh", it); applyAll() }
        })
    }
    fun etpSettings() {
        a.pushPage(fx.page("Enhanced Tracking Protection") { b ->
            fx.radios(b, listOf(
                "Standard" to "Blocks social media trackers, cross-site tracking cookies, cryptominers and fingerprinters. Pages load normally.",
                "Strict" to "Stronger protection and faster pages, but some sites may not work properly.",
                "Custom" to "Choose which trackers and cookies to block."), int("etp", 0)) { put("etp", it); applyEtp(); a.popPage(); etpSettings() }
            if (int("etp", 0) == 2) {
                fx.divider(b)
                fx.header(b, "Choose what to block")
                fx.checkRow(b, "Social media trackers", bool("etp_social", true)) { put("etp_social", it); applyEtp() }
                fx.checkRow(b, "Tracking content (ads and analytics)", bool("etp_content", false)) { put("etp_content", it); applyEtp() }
                fx.checkRow(b, "Cryptominers", bool("etp_crypto", true)) { put("etp_crypto", it); applyEtp() }
                fx.checkRow(b, "Fingerprinters", bool("etp_finger", true)) { put("etp_finger", it); applyEtp() }
                fx.header(b, "Cookies")
                fx.radios(b, listOf("Allow all cookies" to null, "Block cross-site trackers" to null, "Block all third-party cookies" to null, "Block all cookies" to "Many sites will stop working.") , int("etp_cookies", 1)) { put("etp_cookies", it); applyEtp() }
            }
        })
    }

    // ---- Site settings ----
    fun siteSettings() {
        a.pushPage(fx.page("Site settings") { b ->
            fx.switchRow(b, "Request desktop site", null, bool("desktop", false)) { a.toggleDesktop() }
            fx.header(b, "Permissions")
            fun permRow(label: String, key: String) {
                fx.row(b, label, if (int(key, 0) == 1) "Blocked" else "Ask to allow") {
                    a.bd().setTitle(label).setItems(arrayOf("Ask to allow", "Blocked")) { _, i -> put(key, i); a.popPage(); siteSettings() }.show()
                }
            }
            permRow("Camera", "perm_camera"); permRow("Microphone", "perm_mic"); permRow("Location", "perm_loc")
            permRow("DRM-controlled content", "perm_drm")
            fx.divider(b)
            fx.row(b, "Exceptions", "Sites you have allowed or blocked") { exceptions() }
        })
    }
    private fun exceptions() {
        a.pushPage(fx.page("Exceptions") { b ->
            val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL; b.addView(box)
            box.addView(fx.tv("Loading...", 15f, th.mute).also { it.setPadding(dp(16), dp(16), dp(16), 0) })
            a.rt.storageController.allPermissions.accept({ list ->
                a.runOnUiThread {
                    box.removeAllViews()
                    val l = (list ?: emptyList()).filter { it.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT }
                    if (l.isEmpty()) box.addView(fx.tv("No exceptions yet.", 15f, th.mute).also { it.setPadding(dp(16), dp(16), dp(16), 0) })
                    l.forEach { p ->
                        val name = when (p.permission) {
                            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "Location"
                            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Notifications"
                            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "Persistent storage"
                            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> "DRM content"
                            else -> "Other"
                        }
                        val host = Uri.parse(p.uri).host ?: p.uri
                        val state = if (p.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) "Allowed" else "Blocked"
                        val r = fx.row(box, host, "$name: $state") {
                            a.bd().setTitle(host).setMessage("$name: $state").setPositiveButton("Reset") { _, _ -> a.rt.storageController.setPermission(p, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT); a.popPage(); exceptions() }.setNegativeButton("Close", null).show()
                        }
                    }
                }
            }, { _ -> a.runOnUiThread { box.removeAllViews(); box.addView(fx.tv("Could not read the list.", 15f, th.mute).also { it.setPadding(dp(16), dp(16), dp(16), 0) }) } })
        })
    }

    // ---- Delete browsing data ----
    private val DATA_ITEMS = listOf("tabs" to "Open tabs", "history" to "Browsing history", "cookies" to "Cookies and site data", "cache" to "Cached images and files", "perms" to "Site permissions")
    fun runDelete(sel: Set<String>, then: (() -> Unit)? = null) {
        if ("history" in sel) { a.prefs.edit().remove("hist").remove("searches").apply() }
        var flags = 0L
        if ("cookies" in sel) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
        if ("cache" in sel) flags = flags or StorageController.ClearFlags.ALL_CACHES
        if ("perms" in sel) flags = flags or StorageController.ClearFlags.PERMISSIONS or StorageController.ClearFlags.SITE_SETTINGS
        if (flags != 0L) try { a.rt.storageController.clearData(flags).accept({ _ -> a.runOnUiThread { then?.invoke() } }, { _ -> a.runOnUiThread { then?.invoke() } }) } catch (e: Throwable) { then?.invoke() } else then?.invoke()
        if ("tabs" in sel) a.closeAllTabs()
    }
    fun deleteData() {
        val sel = DATA_ITEMS.map { it.first }.toMutableSet()
        a.pushPage(fx.page("Delete browsing data") { b ->
            DATA_ITEMS.forEach { (k, label) ->
                val extra = when (k) { "tabs" -> " (" + a.tabs.size + ")"; "history" -> " (" + a.load("hist").length() + ")"; else -> "" }
                fx.checkRow(b, label + extra, true) { if (it) sel.add(k) else sel.remove(k) }
            }
            fx.bigButton(b, "Delete browsing data") {
                a.bd().setTitle("Delete browsing data?").setMessage("This can not be undone.").setPositiveButton("Delete") { _, _ -> runDelete(sel) { fx.toast("Browsing data deleted") } }.setNegativeButton("Cancel", null).show()
            }
        })
    }
    fun deleteOnQuit() {
        a.pushPage(fx.page("Delete browsing data on quit") { b ->
            val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL
            fun fill() {
                box.removeAllViews()
                if (bool("doq", false)) {
                    DATA_ITEMS.forEach { (k, label) -> fx.checkRow(box, label, bool("doq_$k", true)) { put("doq_$k", it) } }
                }
            }
            fx.switchRow(b, "Delete browsing data on quit", "Deletes the checked items when you choose Quit from the menu.", bool("doq", false)) { put("doq", it); fill() }
            b.addView(box); fill()
        })
    }
    fun quitSet(): Set<String> = if (bool("doq", false)) DATA_ITEMS.map { it.first }.filter { bool("doq_$it", true) }.toSet() else emptySet()

    // ---- Open links in apps ----
    fun openLinks() {
        a.pushPage(fx.page("Open links in apps") { b ->
            val t = fx.tv("Decides what happens when a page links to another app, such as a map, a store page or an email.", 14f, th.mute); t.setPadding(dp(16), dp(10), dp(16), dp(6)); b.addView(t)
            fx.radios(b, listOf("Always" to null, "Ask before opening" to null, "Never" to null), int("openApps", 1)) { put("openApps", it) }
        })
    }

    // ---- Downloads ----
    fun downloadSettings() {
        a.pushPage(fx.page("Download Settings") { b ->
            DownloadCenter.init(a)
            val m = DownloadCenter.manager
            fx.row(b, "Threads per download", "" + m.defaultThreads) {
                a.bd().setTitle("Threads per download").setItems(arrayOf("1", "2", "4", "8", "16")) { _, i -> m.setDefaultThreads(intArrayOf(1, 2, 4, 8, 16)[i]); a.popPage(); downloadSettings() }.show()
            }
            fx.row(b, "Downloads at the same time", "" + m.max) {
                a.bd().setTitle("Downloads at the same time").setItems(arrayOf("1", "2", "3", "4", "6", "8")) { _, i -> m.setMax(intArrayOf(1, 2, 3, 4, 6, 8)[i]); a.popPage(); downloadSettings() }.show()
            }
            fx.switchRow(b, "Ask before downloading", "Shows the file address and asks before it starts.", bool("askDl", false)) { put("askDl", it) }
        })
    }

    private fun mb(n: Long): String = if (n >= 1073741824L) String.format("%.2f GB", n / 1073741824.0) else if (n >= 1048576L) String.format("%.1f MB", n / 1048576.0) else String.format("%d KB", n / 1024)

    /** One list for the whole app: the browser opens the same Downloads screen as the AI side. */
    fun downloads() {
        a.startActivity(Intent(a, MainActivity::class.java).putExtra("open", "downloads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun downloadsOld() {
        DownloadCenter.init(a)
        lateinit var list: LinearLayout
        fun fill() {
            list.removeAllViews()
            val items = DownloadCenter.manager.list()
            if (items.isEmpty()) { fx.empty(list, R.drawable.bt_download, "No downloads yet", "Files you download will appear here."); return }
            items.forEach { it ->
                val st = it.state.name
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(16), dp(10), dp(16), dp(6))
                col.addView(fx.tv(it.displayName(), 16f, th.txt, true))
                val done = it.done.get(); val tot = it.total
                val pct = if (tot > 0) (done * 100 / tot).toInt() else 0
                val info = when (st) {
                    "DONE" -> "Finished" + (if (tot > 0) " - " + mb(tot) else "")
                    "ERROR", "FAILED" -> "Failed: " + (it.message ?: "")
                    "PAUSED" -> "Paused at " + pct + "%" + (if (tot > 0) " of " + mb(tot) else "")
                    else -> st.lowercase().replaceFirstChar { c -> c.uppercase() } + " " + (if (tot > 0) "$pct% of " + mb(tot) else mb(done)) + (if (it.speed > 0) " - " + mb(it.speed) + "/s" else "")
                }
                col.addView(fx.tv(info, 13f, th.mute))
                if (st != "DONE") {
                    val p = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal); p.max = 100; p.progress = pct
                    p.progressDrawable.setColorFilter(th.acc, PorterDuff.Mode.SRC_IN); col.addView(p, LinearLayout.LayoutParams(MATCH, dp(6)).also { it.topMargin = dp(6) })
                }
                val row = LinearLayout(a); row.orientation = LinearLayout.HORIZONTAL
                fun small(t: String, f: () -> Unit) { row.addView(fx.tv(t, 14f, th.acc, true).also { v -> v.setPadding(0, dp(10), dp(20), dp(6)); v.setOnClickListener { f() } }) }
                if (st == "DONE") small("Save to Downloads folder") { a.saveToPublic(it) }
                else if (st == "PAUSED" || st == "ERROR" || st == "FAILED") small("Resume") { DownloadCenter.manager.resume(it.id); fill() }
                else small("Pause") { DownloadCenter.manager.pause(it.id); fill() }
                small("Remove") { DownloadCenter.manager.remove(it.id, false); fill() }
                col.addView(row)
                list.addView(col, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        val v = fx.page("Downloads", listOf(R.drawable.bt_gear to { downloadSettings() })) { b ->
            fx.row(b, "Download from a link", null, R.drawable.bt_plus) {
                val et = EditText(a); et.hint = "https://..."; et.setSingleLine(); et.setTextColor(th.txt); et.setHintTextColor(th.mute)
                val box = LinearLayout(a); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
                a.bd().setTitle("Download from a link").setView(box).setPositiveButton("Download") { _, _ -> val u = et.text.toString().trim(); if (u.isNotEmpty()) { a.download(u, true); fill() } }.setNegativeButton("Cancel", null).show()
            }
            list = LinearLayout(a); list.orientation = LinearLayout.VERTICAL; b.addView(list)
            fill()
        }
        fx.tick(v) { if (DownloadCenter.manager.list().any { it.state.name != "DONE" && it.state.name != "PAUSED" }) fill() }
        a.pushPage(v)
    }

    // ---- Bookmarks ----
    fun bookmarks() {
        lateinit var list: LinearLayout
        fun fill() {
            list.removeAllViews()
            val arr = a.load("bm")
            if (arr.length() == 0) { fx.empty(list, R.drawable.bt_star, "Keep all your favourite bookmarks together", "Use the star in the menu to save a page."); return }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val u = o.getString("u")
                val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16), dp(8), dp(6), dp(8))
                r.addView(fx.badge(o.optString("t").ifBlank { u }, 40, Uri.parse(u).host ?: u), LinearLayout.LayoutParams(dp(40), dp(40)).also { it.rightMargin = dp(16) })
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
                col.addView(fx.tv(o.optString("t").ifBlank { u }, 16f).also { it.setSingleLine(); it.ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(fx.tv(u, 13f, th.mute).also { it.setSingleLine(); it.ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(fx.icon(R.drawable.bt_close, th.mute, 42) {
                    val n = JSONArray(); for (j in 0 until arr.length()) if (j != i) n.put(arr.getJSONObject(j))
                    a.prefs.edit().putString("bm", n.toString()).apply(); fill()
                })
                r.setOnClickListener { a.popPage(); a.go(u) }
                list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        a.pushPage(fx.page("Bookmarks", listOf(R.drawable.bt_trash to {
            a.bd().setTitle("Delete all bookmarks?").setPositiveButton("Delete") { _, _ -> a.prefs.edit().remove("bm").apply(); fill() }.setNegativeButton("Cancel", null).show()
        })) { b -> list = LinearLayout(a); list.orientation = LinearLayout.VERTICAL; b.addView(list); fill() })
    }

    // ---- History ----
    fun addClosed(url: String, title: String) {
        if (!url.startsWith("http")) return
        val arr = try { JSONArray(a.prefs.getString("closed", "[]")) } catch (e: Throwable) { JSONArray() }
        val out = JSONArray(); out.put(JSONObject().put("u", url).put("t", title))
        for (i in 0 until arr.length()) { if (out.length() >= 25) break; if (arr.getJSONObject(i).getString("u") != url) out.put(arr.getJSONObject(i)) }
        a.prefs.edit().putString("closed", out.toString()).apply()
    }
    private fun closedTabs() {
        a.pushPage(fx.page("Recently closed tabs") { b ->
            val arr = try { JSONArray(a.prefs.getString("closed", "[]")) } catch (e: Throwable) { JSONArray() }
            if (arr.length() == 0) fx.empty(b, R.drawable.bt_tabs, "No recently closed tabs")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fx.row(b, o.optString("t").ifBlank { o.getString("u") }, o.getString("u")) { a.popPage(); a.popPage(); a.newTab(o.getString("u")) }
            }
        })
    }
    fun history() {
        var filter = ""
        lateinit var list: LinearLayout
        fun fill() {
            list.removeAllViews()
            val arr = a.load("hist")
            val day = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            var last = ""; var shown = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val u = o.getString("u"); val t = o.optString("t")
                if (filter.isNotEmpty() && !(u.contains(filter, true) || t.contains(filter, true))) continue
                val d = day.format(Date(o.optLong("d", 0L)))
                if (d != last) { last = d; list.addView(fx.tv(d, 14f, th.txt, true).also { it.setPadding(dp(16), dp(20), dp(16), dp(8)) }) }
                shown++
                val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16), dp(8), dp(6), dp(8))
                r.addView(fx.badge(t.ifBlank { u }, 40, Uri.parse(u).host ?: u), LinearLayout.LayoutParams(dp(40), dp(40)).also { it.rightMargin = dp(16) })
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
                col.addView(fx.tv(t.ifBlank { u }, 16f).also { it.setSingleLine(); it.ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(fx.tv(u, 13f, th.mute).also { it.setSingleLine(); it.ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(fx.icon(R.drawable.bt_close, th.txt, 42) {
                    val n = JSONArray(); for (j in 0 until arr.length()) if (j != i) n.put(arr.getJSONObject(j))
                    a.prefs.edit().putString("hist", n.toString()).apply(); fill()
                })
                r.setOnClickListener { a.popPage(); a.go(u) }
                list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            if (shown == 0) list.addView(fx.tv(if (filter.isEmpty()) "No history yet." else "No results.", 15f, th.mute).also { it.setPadding(dp(16), dp(24), dp(16), 0) })
        }
        a.pushPage(fx.page("History", listOf(
            R.drawable.bt_search to {
                val et = EditText(a); et.hint = "Search history"; et.setSingleLine(); et.setTextColor(th.txt); et.setHintTextColor(th.mute); et.setText(filter)
                val box = LinearLayout(a); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
                a.bd().setTitle("Search history").setView(box).setPositiveButton("Search") { _, _ -> filter = et.text.toString().trim(); fill() }.setNegativeButton("Clear") { _, _ -> filter = ""; fill() }.show()
            },
            R.drawable.bt_trash to { a.bd().setTitle("Delete all history?").setPositiveButton("Delete") { _, _ -> a.prefs.edit().remove("hist").apply(); fill() }.setNegativeButton("Cancel", null).show() }
        )) { b ->
            val closed = try { JSONArray(a.prefs.getString("closed", "[]")).length() } catch (e: Throwable) { 0 }
            fx.row(b, "Recently closed tabs", "$closed tabs", R.drawable.bt_tabs) { closedTabs() }
            list = LinearLayout(a); list.orientation = LinearLayout.VERTICAL; b.addView(list); fill()
        })
    }

    // ---- Extensions ----
    private val STATIC_RECS = listOf(
        Triple("uBlock Origin", "Finally, an efficient wide-spectrum content blocker. Easy on CPU and memory.", "ublock-origin"),
        Triple("Dark Reader", "Dark mode for every website. Take care of your eyes, use dark theme for night and daily browsing.", "darkreader"),
        Triple("Bitwarden", "A secure and free password manager for all of your devices.", "bitwarden-password-manager"),
        Triple("SponsorBlock", "Skip sponsorships, intros, outros and more on YouTube videos.", "sponsorblock")
    )

    private fun stars(avg: Double, count: Int): LinearLayout {
        val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
        for (i in 1..5) {
            val s = ImageView(a); s.setImageResource(R.drawable.bt_star); s.setColorFilter(if (avg >= i - 0.25) th.acc else th.line, PorterDuff.Mode.SRC_IN)
            r.addView(s, LinearLayout.LayoutParams(dp(15), dp(15)))
        }
        r.addView(fx.tv("Reviews: " + String.format(Locale.US, "%,d", count), 13f, th.txt).also { it.setPadding(dp(8), 0, 0, 0) })
        return r
    }

    fun extensions() {
        a.pushPage(fx.page("Extensions") { b ->
            val inst = LinearLayout(a); inst.orientation = LinearLayout.VERTICAL; b.addView(inst)
            val rec = LinearLayout(a); rec.orientation = LinearLayout.VERTICAL; b.addView(rec)
            fun loadInstalled() {
                a.rt.webExtensionController.list().accept({ l ->
                    a.runOnUiThread {
                        inst.removeAllViews()
                        val mine = (l ?: emptyList()).filter { !it.isBuiltIn }
                        if (mine.isNotEmpty()) fx.header(inst, "Installed")
                        mine.forEach { e ->
                            val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL; r.setPadding(dp(16), dp(8), dp(8), dp(8))
                            val ic = ImageView(a); ic.background = fx.round(th.side, 8); ic.setPadding(dp(8), dp(8), dp(8), dp(8))
                            try { e.metaData.icon?.getBitmap(96)?.accept({ bm -> a.runOnUiThread { if (bm != null) ic.setImageBitmap(bm) } }, { _ -> }) } catch (_: Throwable) {}
                            r.addView(ic, LinearLayout.LayoutParams(dp(48), dp(48)).also { it.rightMargin = dp(14) })
                            val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
                            col.addView(fx.tv(e.metaData.name ?: e.id, 16f)); col.addView(fx.tv((e.metaData.version ?: "") + if (e.metaData.enabled) "" else "  (off)", 13f, th.mute))
                            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                            val on = e.metaData.enabled
                            val sw = Switch(a); sw.isChecked = on
                            sw.thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.onacc, th.mute))
                            sw.trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.acc, th.line))
                            sw.setOnCheckedChangeListener { _, v ->
                                val res = if (v) a.rt.webExtensionController.enable(e, WebExtensionController.EnableSource.USER) else a.rt.webExtensionController.disable(e, WebExtensionController.EnableSource.USER)
                                res.accept({ _ -> a.runOnUiThread { loadInstalled() } }, { _ -> })
                            }
                            r.addView(sw)
                            r.addView(fx.icon(R.drawable.bt_trash, th.mute, 44) {
                                a.bd().setTitle("Remove " + (e.metaData.name ?: e.id) + "?").setPositiveButton("Remove") { _, _ ->
                                    a.rt.webExtensionController.uninstall(e).accept({ _ -> a.runOnUiThread { loadInstalled() } }, { _ -> })
                                }.setNegativeButton("Cancel", null).show()
                            })
                            inst.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
                        }
                    }
                }, { _ -> })
            }
            fun recRow(name: String, summary: String, iconUrl: String?, avg: Double, count: Int, xpi: String) {
                val r = LinearLayout(a); r.orientation = LinearLayout.HORIZONTAL; r.setPadding(dp(16), dp(12), dp(8), dp(12))
                val ic = ImageView(a); ic.background = fx.round(th.side, 8); ic.setPadding(dp(8), dp(8), dp(8), dp(8))
                if (iconUrl != null) fx.load(iconUrl, ic)
                r.addView(ic, LinearLayout.LayoutParams(dp(48), dp(48)).also { it.rightMargin = dp(14); it.topMargin = dp(2) })
                val col = LinearLayout(a); col.orientation = LinearLayout.VERTICAL
                col.addView(fx.tv(name, 17f))
                col.addView(fx.tv(summary, 14f, th.mute).also { it.maxLines = 4; it.ellipsize = android.text.TextUtils.TruncateAt.END; it.setPadding(0, dp(3), 0, dp(6)) })
                if (count > 0) col.addView(stars(avg, count))
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                r.addView(fx.icon(R.drawable.bt_plus, th.txt, 48) { installExt(xpi) { loadInstalled() } })
                rec.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            fun staticRecs() { STATIC_RECS.forEach { (n, s, slug) -> recRow(n, s, null, 0.0, 0, "https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi") } }
            fx.header(rec, "Recommended")
            rec.addView(fx.tv("Loading...", 14f, th.mute).also { it.setPadding(dp(16), dp(4), dp(16), dp(4)) })
            Thread {
                var ok = false
                val rows = ArrayList<() -> Unit>()
                try {
                    val c = java.net.URL("https://services.addons.mozilla.org/api/v5/addons/search/?app=android&type=extension&promoted=recommended&sort=users&page_size=24&lang=en-US").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val res = js.getJSONArray("results")
                    for (i in 0 until res.length()) {
                        val o = res.getJSONObject(i)
                        fun txt(k: String): String { val v = o.opt(k); return if (v is JSONObject) (v.optString("en-US").ifBlank { v.keys().asSequence().firstOrNull()?.let { kk -> v.optString(kk) } ?: "" }) else (v?.toString() ?: "") }
                        val slug = o.optString("slug"); val rt = o.optJSONObject("ratings")
                        val file = o.optJSONObject("current_version")?.optJSONObject("file")?.optString("url") ?: ""
                        val xpi = if (file.startsWith("http")) file else "https://addons.mozilla.org/firefox/downloads/latest/$slug/latest.xpi"
                        val name = txt("name"); val sum = txt("summary").replace(Regex("<[^>]*>"), "")
                        val icon = o.optString("icon_url"); val avg = rt?.optDouble("average", 0.0) ?: 0.0; val cnt = rt?.optInt("count", 0) ?: 0
                        rows.add { recRow(name, sum, icon, avg, cnt, xpi) }
                    }
                    ok = rows.isNotEmpty()
                } catch (_: Throwable) {}
                a.runOnUiThread {
                    rec.removeAllViews(); fx.header(rec, "Recommended")
                    if (ok) rows.forEach { it() } else staticRecs()
                }
            }.start()
            fx.bigButton(b, "Find more extensions") { a.popPage(); a.newTab("https://addons.mozilla.org/android/") }
            loadInstalled()
        })
    }

    private fun installExt(url: String, done: () -> Unit) {
        fx.toast("Getting the extension...")
        a.rt.webExtensionController.install(url).accept({ e ->
            a.runOnUiThread { fx.toast("Added " + (e?.metaData?.name ?: "extension")); done() }
        }, { err -> a.runOnUiThread { fx.toast("Could not add it: " + (err?.message ?: "unknown error")) } })
    }
}
