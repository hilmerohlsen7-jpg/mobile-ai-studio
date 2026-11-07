package com.mobileaistudio.app

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream

/** Turns chat-style text (with simple # headings, - bullets and ``` code) into a clean A4 PDF. */
object PdfMaker {
    private const val W = 595
    private const val H = 842
    private const val M = 48

    fun make(title: String, body: String): ByteArray {
        val doc = PdfDocument()
        val width = W - 2 * M
        val normal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = 0xFF111111.toInt() }
        val h1 = TextPaint(normal).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val h2 = TextPaint(normal).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val h3 = TextPaint(normal).apply { textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD }
        val mono = TextPaint(normal).apply { textSize = 9.5f; typeface = Typeface.MONOSPACE }

        data class Block(val text: String, val paint: TextPaint, val gap: Float, val indent: Int = 0)
        val blocks = ArrayList<Block>()
        if (title.isNotBlank()) blocks.add(Block(title.trim(), h1, 12f))
        var inCode = false
        val code = StringBuilder()
        for (raw in body.replace("\r", "").split("\n")) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) { blocks.add(Block(code.toString().trimEnd(), mono, 8f, 8)); code.setLength(0) }
                inCode = !inCode
                continue
            }
            if (inCode) { code.append(line).append('\n'); continue }
            val t = line.trim()
            when {
                t.isEmpty() -> blocks.add(Block("", normal, 4f))
                t.startsWith("### ") -> blocks.add(Block(clean(t.drop(4)), h3, 4f))
                t.startsWith("## ") -> blocks.add(Block(clean(t.drop(3)), h2, 5f))
                t.startsWith("# ") -> blocks.add(Block(clean(t.drop(2)), h1, 6f))
                t.startsWith("- ") || t.startsWith("* ") -> blocks.add(Block("\u2022 " + clean(t.drop(2)), normal, 3f, 10))
                else -> blocks.add(Block(clean(t), normal, 4f))
            }
        }
        if (inCode && code.isNotEmpty()) blocks.add(Block(code.toString().trimEnd(), mono, 8f, 8))

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }
        newPage()
        for (b in blocks) {
            if (b.text.isEmpty()) { y += b.gap + 4; continue }
            val lay = StaticLayout.Builder.obtain(b.text, 0, b.text.length, b.paint, width - b.indent)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            for (i in 0 until lay.lineCount) {
                val top = lay.getLineTop(i)
                val bottom = lay.getLineBottom(i)
                if (y + (bottom - top) > H - M) newPage()
                val c = page!!.canvas
                c.save()
                c.translate((M + b.indent).toFloat(), y - top)
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                lay.draw(c)
                c.restore()
                y += (bottom - top)
            }
            y += b.gap
        }
        page?.let { doc.finishPage(it) }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun clean(s: String): String = s.replace("**", "").replace("__", "").replace("`", "")
}
