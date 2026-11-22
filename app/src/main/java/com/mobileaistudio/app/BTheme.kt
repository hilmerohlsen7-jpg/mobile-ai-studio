package com.mobileaistudio.app

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/** The app's colours, copied from the page so native screens (the browser) match it. */
class BTheme(val bg: Int, val panel: Int, val side: Int, val line: Int, val txt: Int, val mute: Int, val acc: Int, val onacc: Int, val dark: Boolean) {
    companion object {
        fun load(c: Context, mode: Int = 0): BTheme {
            if (mode == 1) return BTheme(Color.parseColor("#FBFBFE"), Color.WHITE, Color.parseColor("#F0F0F4"), Color.parseColor("#DADAE2"), Color.parseColor("#15141A"), Color.parseColor("#5B5B66"), Color.parseColor("#0061E0"), Color.WHITE, false)
            if (mode == 2) return BTheme(Color.parseColor("#1C1B22"), Color.parseColor("#2B2A33"), Color.parseColor("#2B2A33"), Color.parseColor("#3A3944"), Color.parseColor("#FBFBFE"), Color.parseColor("#B0B0BA"), Color.parseColor("#00DDFF"), Color.parseColor("#15141A"), true)
            val raw = c.getSharedPreferences("mas_theme", Context.MODE_PRIVATE).getString("json", "") ?: ""
            val o = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
            fun col(k: String, d: String): Int = try { Color.parseColor(o.optString(k, d).trim().ifEmpty { d }) } catch (e: Exception) { Color.parseColor(d) }
            return BTheme(col("bg", "#FAF9F5"), col("panel", "#FFFFFF"), col("side", "#F0EEE6"), col("line", "#E3E0D5"),
                col("txt", "#141413"), col("mute", "#6B6A64"), col("acc", "#C96442"), col("onacc", "#FFFFFF"), o.optBoolean("dark", false))
        }
        fun save(c: Context, json: String) { c.getSharedPreferences("mas_theme", Context.MODE_PRIVATE).edit().putString("json", json).apply() }
    }
}
