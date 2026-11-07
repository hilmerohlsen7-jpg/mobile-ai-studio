package com.mobileaistudio.app

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Pulls readable text out of files: PDF, Word, Excel, PowerPoint, OpenDocument, zip, web pages and plain text. */
object DocReader {
    const val MAX_CHARS = 400_000
    private const val MAX_PDF_BYTES = 80L * 1048576L
    private const val MAX_OTHER_BYTES = 200L * 1048576L

    class Result(val status: String, val text: String = "", val note: String = "", val kind: String = "")

    private var pdfReady = false

    private fun initPdf(ctx: Context) {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            pdfReady = true
        }
    }

    private val textExt = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "html", "htm", "css", "js", "ts",
        "java", "kt", "py", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "sql", "swift", "gradle", "properties", "srt", "tex", "rtf"
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun read(ctx: Context, f: File, name: String, password: String?): Result {
        val ext = extOf(name)
        return try {
            when {
                ext == "torrent" -> Result("torrent")
                ext == "pdf" -> readPdf(ctx, f, password)
                ext in setOf("docx", "dotx", "docm") -> ooxml(f, "word/document.xml", "Word")
                ext in setOf("pptx", "ppsx", "pptm") -> pptx(f)
                ext in setOf("xlsx", "xlsm") -> xlsx(f)
                ext in setOf("odt", "ods", "odp") -> odf(f)
                ext == "epub" -> epub(f)
                ext in setOf("doc", "xls", "ppt") || isOle(f) ->
                    Result("unsupported", note = "This is an old or password-protected Office file. Save it as .docx, .xlsx or .pptx first (or use Print, then Save as PDF).")
                ext in setOf("zip", "jar", "apk", "aar") -> zip(f, password)
                ext in setOf("exe", "msi", "iso", "dmg", "bin", "dll", "so", "img", "7z", "rar", "tar", "gz") ->
                    Result("unsupported", note = "This is a program or archive that the AI cannot read. Zip files, PDFs, Office files and text files work.")
                else -> plain(f, ext)
            }
        } catch (e: OutOfMemoryError) {
            Result("error", note = "That file is too big for this phone to read.")
        } catch (e: Exception) {
            Result("error", note = e.message?.takeIf { it.isNotBlank() } ?: "Could not read that file.")
        }
    }

    private fun isOle(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte() && b[3] == 0xE0.toByte() }
    } catch (e: Exception) {
        false
    }

    // ---------- plain text ----------

    private fun plain(f: File, ext: String): Result {
        val head = ByteArray(8192)
        val n = f.inputStream().use { it.read(head) }
        if (n <= 0) return Result("error", note = "That file is empty.")
        for (i in 0 until n) if (head[i].toInt() == 0) return Result("unsupported", note = "That is not a text file. The AI cannot read it.")
        val bo = ByteArrayOutputStream()
        f.inputStream().use { ins ->
            val buf = ByteArray(32768)
            while (bo.size() < MAX_CHARS * 2) {
                val r = ins.read(buf)
                if (r < 0) break
                bo.write(buf, 0, r)
            }
        }
        var t = String(bo.toByteArray(), Charsets.UTF_8)
        if (ext == "html" || ext == "htm") t = htmlToText(t)
        if (ext == "rtf") t = t.replace(Regex("\\\\[a-z]+-?\\d* ?|[{}]"), "")
        val cut = f.length() > bo.size() || t.length > MAX_CHARS
        if (t.length > MAX_CHARS) t = t.substring(0, MAX_CHARS)
        return Result("ok", t, if (cut) "Only the first part was kept." else "", "text")
    }

    fun htmlToText(h: String): String {
        var t = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|</tr>"), "\n")
        t = t.replace(Regex("<[^>]+>"), "")
        return decodeEntities(t).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n").trim()
    }

    private fun decodeEntities(s: String): String =
        s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    // ---------- PDF ----------

    private fun readPdf(ctx: Context, f: File, pw: String?): Result {
        if (f.length() > MAX_PDF_BYTES) return Result("error", note = "That PDF is too big to read on a phone (limit 80 MB).")
        initPdf(ctx)
        val doc = try {
            PDDocument.load(f, pw ?: "")
        } catch (e: InvalidPasswordException) {
            return Result(if (pw == null) "password" else "wrong", kind = "pdf")
        }
        doc.use {
            val pages = it.numberOfPages
            val st = PDFTextStripper()
            st.sortByPosition = true
            st.startPage = 1
            st.endPage = Math.min(pages, 250)
            var t = st.getText(it).trim()
            val cut = t.length > MAX_CHARS || pages > 250
            if (t.length > MAX_CHARS) t = t.substring(0, MAX_CHARS)
            if (t.length < 20) {
                return Result("unsupported", note = "This PDF has no text in it (it is probably a scan or pictures). Take or choose a photo of the page instead and a picture model can read it.", kind = "pdf")
            }
            val note = "$pages page" + (if (pages == 1) "" else "s") + (if (cut) ". Only the first part was kept." else "") +
                (if (it.isEncrypted) " It was protected, and is open now." else "")
            return Result("ok", t, note, "pdf")
        }
    }

    /** Saves a copy of a protected PDF with the protection removed. */
    fun unlockPdf(ctx: Context, src: File, pw: String?, out: File): String? {
        initPdf(ctx)
        return try {
            PDDocument.load(src, pw ?: "").use {
                it.isAllSecurityToBeRemoved = true
                it.save(out)
            }
            null
        } catch (e: InvalidPasswordException) {
            "The password is not right."
        } catch (e: Exception) {
            e.message ?: "Could not save it."
        }
    }

    // ---------- Office / OpenDocument ----------

    private class Cap : Exception()

    private class Out {
        val sb = StringBuilder()
        fun add(s: CharSequence) {
            sb.append(s)
            if (sb.length > MAX_CHARS) throw Cap()
        }
    }

    private fun xmlText(ins: InputStream, out: Out, textTags: Set<String>?, nlEnd: Set<String>, tabEnd: Set<String>, nlStart: Set<String>, tabStart: Set<String>) {
        val p = Xml.newPullParser()
        p.setInput(ins, "UTF-8")
        var inText = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name
                    if (textTags != null && n in textTags) inText++
                    if (n in nlStart) out.add("\n")
                    if (n in tabStart) out.add("\t")
                    if (n == "text:s") out.add(" ")
                }
                XmlPullParser.TEXT -> if (textTags == null || inText > 0) out.add(p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name
                    if (textTags != null && n in textTags && inText > 0) inText--
                    if (n in nlEnd) out.add("\n")
                    if (n in tabEnd) out.add("\t")
                }
            }
            ev = p.next()
        }
    }

    private fun finish(o: Out, capped: Boolean, kind: String, extra: String = ""): Result {
        val t = o.sb.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (t.isEmpty()) return Result("error", note = "There was no text in that file.")
        val note = listOf(extra, if (capped) "Only the first part was kept." else "").filter { it.isNotEmpty() }.joinToString(" ")
        return Result("ok", t, note, kind)
    }

    private fun zipOrNull(f: File): java.util.zip.ZipFile? = try {
        java.util.zip.ZipFile(f)
    } catch (e: Exception) {
        null
    }

    private fun ooxml(f: File, entry: String, label: String): Result {
        val z = zipOrNull(f) ?: return Result("unsupported", note = "This $label file is damaged or password protected, so it cannot be read.")
        z.use {
            val e = it.getEntry(entry) ?: return Result("error", note = "This does not look like a $label file.")
            val o = Out()
            var capped = false
            try {
                it.getInputStream(e).use { ins ->
                    xmlText(ins, o, setOf("w:t"), setOf("w:p"), setOf("w:tc"), setOf("w:br"), setOf("w:tab"))
                }
            } catch (c: Cap) {
                capped = true
            }
            return finish(o, capped, "docx")
        }
    }

    private fun pptx(f: File): Result {
        val z = zipOrNull(f) ?: return Result("unsupported", note = "This PowerPoint file is damaged or password protected, so it cannot be read.")
        z.use {
            val slides = it.entries().toList().filter { e -> Regex("ppt/slides/slide\\d+\\.xml").matches(e.name) }
                .sortedBy { e -> Regex("\\d+").find(e.name.substringAfterLast('/'))!!.value.toInt() }
            if (slides.isEmpty()) return Result("error", note = "There are no slides in that file.")
            val o = Out()
            var capped = false
            try {
                slides.forEachIndexed { i, e ->
                    o.add("--- Slide ${i + 1} ---\n")
                    it.getInputStream(e).use { ins -> xmlText(ins, o, setOf("a:t"), setOf("a:p"), emptySet(), emptySet(), emptySet()) }
                    o.add("\n")
                }
            } catch (c: Cap) {
                capped = true
            }
            return finish(o, capped, "pptx", "${slides.size} slides.")
        }
    }

    private fun xlsx(f: File): Result {
        val z = zipOrNull(f) ?: return Result("unsupported", note = "This Excel file is damaged or password protected, so it cannot be read.")
        z.use {
            val shared = ArrayList<String>()
            it.getEntry("xl/sharedStrings.xml")?.let { e ->
                val p = Xml.newPullParser()
                it.getInputStream(e).use { ins ->
                    p.setInput(ins, "UTF-8")
                    val cur = StringBuilder()
                    var inT = false
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && p.name == "si") cur.setLength(0)
                        if (ev == XmlPullParser.START_TAG && p.name == "t") inT = true
                        if (ev == XmlPullParser.TEXT && inT) cur.append(p.text)
                        if (ev == XmlPullParser.END_TAG && p.name == "t") inT = false
                        if (ev == XmlPullParser.END_TAG && p.name == "si") shared.add(cur.toString())
                        ev = p.next()
                    }
                }
            }
            val sheets = it.entries().toList().filter { e -> Regex("xl/worksheets/sheet\\d+\\.xml").matches(e.name) }
                .sortedBy { e -> Regex("\\d+").find(e.name.substringAfterLast('/'))!!.value.toInt() }
            if (sheets.isEmpty()) return Result("error", note = "There are no sheets in that file.")
            val o = Out()
            var capped = false
            try {
                sheets.forEachIndexed { i, e ->
                    o.add("--- Sheet ${i + 1} ---\n")
                    val p = Xml.newPullParser()
                    it.getInputStream(e).use { ins ->
                        p.setInput(ins, "UTF-8")
                        var type = ""
                        var inV = false
                        var inT = false
                        var firstInRow = true
                        val v = StringBuilder()
                        var ev = p.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            when (ev) {
                                XmlPullParser.START_TAG -> when (p.name) {
                                    "row" -> firstInRow = true
                                    "c" -> { type = p.getAttributeValue(null, "t") ?: ""; v.setLength(0) }
                                    "v" -> inV = true
                                    "t" -> inT = true
                                }
                                XmlPullParser.TEXT -> if (inV || inT) v.append(p.text)
                                XmlPullParser.END_TAG -> when (p.name) {
                                    "v" -> inV = false
                                    "t" -> inT = false
                                    "c" -> {
                                        val cell = if (type == "s") shared.getOrElse(v.toString().trim().toIntOrNull() ?: -1) { "" } else v.toString()
                                        if (!firstInRow) o.add("\t")
                                        firstInRow = false
                                        o.add(cell)
                                    }
                                    "row" -> o.add("\n")
                                }
                            }
                            ev = p.next()
                        }
                    }
                }
            } catch (c: Cap) {
                capped = true
            }
            return finish(o, capped, "xlsx", "${sheets.size} sheet" + (if (sheets.size == 1) "." else "s."))
        }
    }

    private fun odf(f: File): Result {
        val z = zipOrNull(f) ?: return Result("error", note = "That file is damaged.")
        z.use {
            val e = it.getEntry("content.xml") ?: return Result("error", note = "This does not look like an OpenDocument file.")
            val o = Out()
            var capped = false
            try {
                it.getInputStream(e).use { ins ->
                    xmlText(ins, o, null, setOf("text:p", "text:h", "table:table-row"), setOf("table:table-cell"), emptySet(), setOf("text:tab"))
                }
            } catch (c: Cap) {
                capped = true
            }
            return finish(o, capped, "odf")
        }
    }

    private fun epub(f: File): Result {
        val z = zipOrNull(f) ?: return Result("error", note = "That file is damaged.")
        z.use {
            val parts = it.entries().toList().filter { e -> e.name.lowercase().let { n -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") } }.sortedBy { e -> e.name }
            if (parts.isEmpty()) return Result("error", note = "There is no text in that book.")
            val o = Out()
            var capped = false
            try {
                for (e in parts) {
                    val t = it.getInputStream(e).use { ins -> htmlToText(String(ins.readBytes(), Charsets.UTF_8)) }
                    if (t.isNotBlank()) o.add(t + "\n\n")
                }
            } catch (c: Cap) {
                capped = true
            }
            return finish(o, capped, "epub")
        }
    }

    // ---------- zip ----------

    private fun zip(f: File, pw: String?): Result {
        val zf = ZipFile(f)
        if (!zf.isValidZipFile) return Result("error", note = "That zip file is damaged.")
        val encrypted = zf.isEncrypted
        if (encrypted && pw == null) return Result("password", kind = "zip")
        if (pw != null) zf.setPassword(pw.toCharArray())
        val headers = zf.fileHeaders.filter { !it.isDirectory }
        val o = Out()
        var capped = false
        try {
            o.add("Zip file with ${headers.size} file" + (if (headers.size == 1) "" else "s") + ":\n")
            headers.take(300).forEach { o.add("- " + it.fileName + " (" + it.uncompressedSize / 1024 + " KB)\n") }
            if (headers.size > 300) o.add("- ... and ${headers.size - 300} more\n")
            o.add("\n")
            for (h in headers) {
                if (extOf(h.fileName) !in textExt || h.uncompressedSize > 400_000) continue
                val t = try {
                    zf.getInputStream(h).use { String(it.readBytes(), Charsets.UTF_8) }
                } catch (e: ZipException) {
                    if (e.type == ZipException.Type.WRONG_PASSWORD) return Result("wrong", kind = "zip")
                    continue
                }
                if (t.indexOf('\u0000') >= 0) continue
                o.add("=== ${h.fileName} ===\n" + (if (extOf(h.fileName).startsWith("htm")) htmlToText(t) else t) + "\n\n")
            }
        } catch (c: Cap) {
            capped = true
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) return Result("wrong", kind = "zip")
            throw e
        }
        return Result("ok", o.sb.toString().trim(), (if (capped) "Only the first part was kept. " else "") + (if (encrypted) "It was protected, and is open now." else ""), "zip")
    }

    /** Unpacks a (protected) zip into a new folder. Returns an error text, or null. */
    fun unzipTo(f: File, pw: String?, dest: File): String? {
        return try {
            val zf = ZipFile(f)
            if (pw != null) zf.setPassword(pw.toCharArray())
            dest.mkdirs()
            zf.extractAll(dest.absolutePath)
            null
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) "The password is not right." else (e.message ?: "Could not unpack it.")
        } catch (e: Exception) {
            e.message ?: "Could not unpack it."
        }
    }
}
