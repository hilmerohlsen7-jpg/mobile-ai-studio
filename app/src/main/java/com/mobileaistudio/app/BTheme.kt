package com.mobileaistudio.app

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/** The app's colours, copied from the page so native screens (the browser) match it. */
class BTheme(val bg: Int, val panel: Int, val side: Int, val line: Int, val txt: Int, val mute: Int, val acc: Int, val onacc: Int, val dark: Boolean) {
    companion object {
        fun load(c: Context): BTheme {
            val raw = c.getSharedPreferences("mas_theme", Context.MODE_PRIVATE).getString("json", "") ?: ""
            val o = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            fun col(k: String, d: String): Int = try { Color.parseColor(o.optString(k, d).trim().ifEmpty { d }) } catch (e: Exception) { Color.parseColor(d) }
            return BTheme(col("bg", "#FAF9F5"), col("panel", "#FFFFFF"), col("side", "#F0EEE6"), col("line", "#E3E0D5"),
                col("txt", "#141413"), col("mute", "#6B6A64"), col("acc", "#C96442"), col("onacc", "#FFFFFF"), o.optBoolean("dark", false))
        }
        fun save(c: Context, json: String) { c.getSharedPreferences("mas_theme", Context.MODE_PRIVATE).edit().putString("json", json).apply() }
    }
}
