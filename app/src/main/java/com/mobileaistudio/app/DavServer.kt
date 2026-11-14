package com.mobileaistudio.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import android.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * A WebDAV server so a PC can map a phone folder as a network drive (Windows: "Map network drive").
 * Safe by design, like the FTP server: it never deletes, renames, moves or replaces anything.
 * Uploads (PUT) and new folders (MKCOL) only create NEW things, and only when read-only is off and a PIN login is used.
 */
object DavServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false; private set
    var pin = ""; private set
    var url = ""; private set
    var port = 8090; private set
    var readOnly = true; private set
    var anon = true; private set
    private var root: File = File("/")
    private var fails = 0
    private var lockUntil = 0L
    val log = ArrayList<String>()

    private fun note(s: String) { synchronized(log) { log.add(s); if (log.size > 80) log.removeAt(0) } }

    @Synchronized fun start(rootDir: File, p: Int, ro: Boolean): String? {
        stop()
        val ip = WifiServer.localIp() ?: return "Connect to Wi-Fi first."
        if (p < 1024 || p > 65535) return "Use a port between 1024 and 65535."
        return try {
            val ss = ServerSocket(p); server = ss; root = rootDir.canonicalFile; port = p
            anon = ro; readOnly = ro   // read-only: no login needed. Otherwise a PIN login is needed for everything.
            pin = (100000 + SecureRandom().nextInt(900000)).toString(); fails = 0; lockUntil = 0
            url = "http://$ip:$p/"; running = true; synchronized(log) { log.clear() }; note("Started")
            pool.execute { while (running) { try { val c = ss.accept(); pool.execute { serve(c) } } catch (e: Exception) { break } } }
            null
        } catch (e: Exception) { e.message ?: "Could not start." }
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
        if (url.isNotEmpty()) note("Stopped")
    }

    private val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    private val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    private fun hd(t: Long) = synchronized(httpDate) { httpDate.format(Date(t)) }
    private fun iso(t: Long) = synchronized(isoDate) { isoDate.format(Date(t)) }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun hrefOf(f: File): String {
        val rel = f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')
        val p = if (rel.isEmpty()) "/" else "/" + rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return if (f.isDirectory && !p.endsWith("/")) "$p/" else p
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun resolve(path: String): File? {
        val dec = try { URLDecoder.decode(path.substringBefore('?').replace("+", "%2B"), "UTF-8") } catch (e: Exception) { return null }
        val f = File(root, dec).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
    }

    private fun reply(out: OutputStream, code: Int, body: ByteArray = ByteArray(0), type: String = "text/plain; charset=utf-8", extra: String = "", head: Boolean = false, keep: Boolean = true) {
        val st = when (code) { 100 -> "Continue"; 200 -> "OK"; 201 -> "Created"; 206 -> "Partial Content"; 207 -> "Multi-Status"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write(("HTTP/1.1 $code $st\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: ${if (keep) "keep-alive" else "close"}\r\nMS-Author-Via: DAV\r\n$extra\r\n").toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    private fun propEntry(f: File): String {
        val dir = f.isDirectory
        val sb = StringBuilder("<D:response><D:href>${esc(hrefOf(f))}</D:href><D:propstat><D:prop>")
        sb.append("<D:displayname>${esc(if (f.path == root.path) "" else f.name)}</D:displayname>")
        sb.append("<D:creationdate>${iso(f.lastModified())}</D:creationdate><D:getlastmodified>${hd(f.lastModified())}</D:getlastmodified>")
        if (dir) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            if (f.path == root.path) sb.append("<D:quota-available-bytes>${f.usableSpace}</D:quota-available-bytes><D:quota-used-bytes>${(f.totalSpace - f.usableSpace).coerceAtLeast(0)}</D:quota-used-bytes>")
        } else {
            sb.append("<D:resourcetype/><D:getcontentlength>${f.length()}</D:getcontentlength><D:getcontenttype>${ShareProvider.mimeOf(f.name)}</D:getcontenttype>")
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        return sb.toString()
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 30000
            val ins = java.io.BufferedInputStream(c.getInputStream()); val out = c.getOutputStream()
            while (running) {
                val line = readLine(ins) ?: break
                if (line.isBlank()) continue
                val parts = line.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase(); val target = parts[1].let { if (it.startsWith("http")) it.substringAfter("://").substringAfter('/').let { r -> "/$r" } else it }
                val hdr = HashMap<String, String>()
                while (true) { val l = readLine(ins) ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) hdr[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
                val clen = hdr["content-length"]?.toLongOrNull() ?: 0L
                val ip = c.inetAddress?.hostAddress ?: "?"

                if (method == "OPTIONS") {
                    reply(out, 200, extra = "DAV: 1\r\nAllow: OPTIONS, GET, HEAD, PROPFIND" + (if (!readOnly) ", PUT, MKCOL" else "") + "\r\n"); skip(ins, clen); continue
                }
                // login: read-only mode needs none; otherwise admin + PIN
                val needLogin = !anon
                if (needLogin) {
                    if (System.currentTimeMillis() < lockUntil) { reply(out, 429, "Too many wrong tries. Wait a minute.".toByteArray(), keep = false); break }
                    val a = hdr["authorization"]
                    val ok = a != null && a.startsWith("Basic ", true) && try { String(Base64.decode(a.substring(6).trim(), Base64.DEFAULT)).substringAfter(':') == pin } catch (e: Exception) { false }
                    if (!ok) {
                        if (a != null) { fails++; note("Wrong PIN from $ip"); if (fails >= 5) { lockUntil = System.currentTimeMillis() + 60000; fails = 0 } }
                        reply(out, 401, "Login needed".toByteArray(), extra = "WWW-Authenticate: Basic realm=\"Phone files\"\r\n"); skip(ins, clen); continue
                    }
                    fails = 0
                }
                val f = resolve(target)
                if (f == null) { reply(out, 403, "No".toByteArray()); skip(ins, clen); continue }

                when (method) {
                    "PROPFIND" -> {
                        skip(ins, clen)
                        if (!f.exists()) { reply(out, 404, "Not found".toByteArray()); continue }
                        val depth = hdr["depth"] ?: "1"
                        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
                        sb.append(propEntry(f))
                        if (f.isDirectory && depth != "0") f.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { sb.append(propEntry(it)) }
                        sb.append("</D:multistatus>")
                        reply(out, 207, sb.toString().toByteArray(), "text/xml; charset=utf-8")
                        note("Listed ${f.name.ifEmpty { "/" }} for $ip")
                    }
                    "GET", "HEAD" -> {
                        skip(ins, clen)
                        if (!f.exists()) { reply(out, 404, "Not found".toByteArray()); continue }
                        if (f.isDirectory) { reply(out, 200, "This is a WebDAV folder. Map it as a network drive on your computer.".toByteArray(), head = method == "HEAD"); continue }
                        val len = f.length(); var start = 0L; var end = len - 1; var code = 200
                        hdr["range"]?.let { r -> val m = Regex("bytes=(\\d*)-(\\d*)").find(r); if (m != null) {
                            val s = m.groupValues[1]; val e = m.groupValues[2]
                            if (s.isEmpty() && e.isNotEmpty()) { start = (len - e.toLong()).coerceAtLeast(0) } else if (s.isNotEmpty()) { start = s.toLong(); if (e.isNotEmpty()) end = minOf(e.toLong(), len - 1) }
                            code = 206 } }
                        if (start > end || start >= len && len > 0) { reply(out, 416, extra = "Content-Range: bytes */$len\r\n"); continue }
                        val n = if (len == 0L) 0 else end - start + 1
                        out.write(("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\nContent-Type: ${ShareProvider.mimeOf(f.name)}\r\nContent-Length: $n\r\nAccept-Ranges: bytes\r\nLast-Modified: ${hd(f.lastModified())}\r\n" +
                            (if (code == 206) "Content-Range: bytes $start-$end/$len\r\n" else "") + "Connection: keep-alive\r\n\r\n").toByteArray())
                        if (method == "GET" && n > 0) java.io.RandomAccessFile(f, "r").use { r ->
                            r.seek(start); val buf = ByteArray(65536); var left = n
                            while (left > 0) { val k = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) break; out.write(buf, 0, k); left -= k }
                        }
                        out.flush(); if (method == "GET") note("Sent ${f.name} to $ip")
                    }
                    "PUT" -> {
                        if (hdr["expect"]?.contains("100", true) == true) { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush() }
                        if (readOnly) { reply(out, 403, "This share is read-only.".toByteArray()); skip(ins, clen); continue }
                        val parent = f.parentFile
                        if (f.exists() || parent == null || !parent.isDirectory) { reply(out, if (f.exists()) 403 else 409, (if (f.exists()) "Not replaced: that name already exists." else "Folder missing.").toByteArray()); skip(ins, clen); continue }
                        var okw = true
                        try { FileOutputStream(f).use { o -> val buf = ByteArray(65536); var left = clen; while (left > 0) { val k = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) { okw = false; break }; o.write(buf, 0, k); left -= k } } } catch (e: Exception) { okw = false }
                        if (!okw) { try { f.delete() } catch (e: Exception) {}; reply(out, 409, "Upload failed.".toByteArray(), keep = false); break }
                        reply(out, 201); note("Received ${f.name} from $ip")
                    }
                    "MKCOL" -> {
                        skip(ins, clen)
                        if (readOnly) { reply(out, 403, "This share is read-only.".toByteArray()); continue }
                        if (f.exists()) { reply(out, 405, "Already exists.".toByteArray()); continue }
                        val parent = f.parentFile
                        if (parent == null || !parent.isDirectory) { reply(out, 409); continue }
                        if (f.mkdir()) { reply(out, 201); note("Folder ${f.name} created by $ip") } else reply(out, 403)
                    }
                    else -> { skip(ins, clen); reply(out, 403, "Deleting, moving, renaming and replacing are not allowed on this share.".toByteArray()) }
                }
            }
        } catch (e: Exception) {
        } finally { try { c.close() } catch (e: Exception) {} }
    }

    private fun skip(ins: InputStream, n: Long) { var left = n; val b = ByteArray(8192); try { while (left > 0) { val k = ins.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (k < 0) break; left -= k } } catch (e: Exception) {} }
}
