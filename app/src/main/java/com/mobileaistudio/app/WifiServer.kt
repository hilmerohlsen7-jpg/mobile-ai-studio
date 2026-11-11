package com.mobileaistudio.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/** A small web server so a computer on the same Wi-Fi can browse, download and (if allowed) upload files. Needs the PIN. */
object WifiServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false; private set
    var pin = ""; private set
    var url = ""; private set
    private var root: File = File("/")
    private var allowUpload = false
    private var fails = 0
    private var lockUntil = 0L
    val log = ArrayList<String>()

    fun localIp(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is java.net.Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            }
        } catch (e: Exception) {}
        return null
    }

    @Synchronized fun start(rootDir: File, upload: Boolean, port: Int = 8080): String? {
        stop()
        val ip = localIp() ?: return "Connect to Wi-Fi first."
        return try {
            val ss = ServerSocket(port)
            server = ss; root = rootDir.canonicalFile; allowUpload = upload
            pin = (100000 + SecureRandom().nextInt(900000)).toString(); fails = 0; lockUntil = 0
            url = "http://$ip:$port"; running = true; log.clear(); log.add("Started")
            pool.execute { while (running) { try { val c = ss.accept(); pool.execute { handle(c) } } catch (e: Exception) { break } } }
            null
        } catch (e: Exception) { e.message ?: "Could not start." }
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
        if (url.isNotEmpty()) log.add("Stopped")
    }

    private fun note(s: String) { synchronized(log) { log.add(s); if (log.size > 60) log.removeAt(0) } }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun readLine(ins: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun send(out: java.io.OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val st = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write(("HTTP/1.1 $code $st\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun safe(rel: String): File? {
        val f = File(root, rel).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + "/")) f else null
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 30000
                val ins = BufferedInputStream(sock.getInputStream()); val out = sock.getOutputStream()
                val first = readLine(ins) ?: return
                val parts = first.split(" "); if (parts.size < 2) return
                val method = parts[0]; val target = parts[1]
                val headers = HashMap<String, String>()
                while (true) { val l = readLine(ins) ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
                val now = System.currentTimeMillis()
                if (now < lockUntil) { send(out, 429, "text/plain", "Too many wrong PINs. Wait a minute.".toByteArray()); return }
                val auth = headers["authorization"] ?: ""
                val ok = auth.startsWith("Basic ") && try { String(android.util.Base64.decode(auth.substring(6), android.util.Base64.DEFAULT)).substringAfter(':') == pin } catch (e: Exception) { false }
                if (!ok) {
                    if (auth.isNotEmpty()) { fails++; if (fails >= 5) { lockUntil = now + 60000; fails = 0; note("Too many wrong PINs, locked for a minute") } }
                    send(out, 401, "text/plain", "PIN needed. Type any name and the PIN shown on the phone.".toByteArray(), "WWW-Authenticate: Basic realm=\"Mobile AI Studio\"\r\n"); return
                }
                fails = 0
                val q = target.substringAfter('?', ""); val path = target.substringBefore('?')
                val params = q.split('&').filter { it.contains('=') }.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val rel = params["p"] ?: ""
                val f = safe(rel) ?: run { send(out, 403, "text/plain", "No.".toByteArray()); return }
                when {
                    method == "GET" && path == "/dl" -> {
                        if (!f.isFile) { send(out, 404, "text/plain", "Not a file".toByteArray()); return }
                        note("Download: ${f.name}")
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length()}\r\nContent-Disposition: attachment; filename=\"${f.name.replace("\"", "")}\"\r\nConnection: close\r\n\r\n").toByteArray())
                        f.inputStream().use { it.copyTo(out, 65536) }; out.flush()
                    }
                    method == "POST" && path == "/up" -> {
                        if (!allowUpload) { send(out, 403, "text/plain", "Uploads are off. Turn them on when starting the server.".toByteArray()); return }
                        if (!f.isDirectory) { send(out, 404, "text/plain", "Not a folder".toByteArray()); return }
                        val name = File(params["n"] ?: "upload.bin").name.ifEmpty { "upload.bin" }
                        var dest = File(f, name); var n = 1
                        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
                        while (dest.exists()) { dest = File(f, if (ext.isEmpty() || base == name) "$name ($n)" else "$base ($n).$ext"); n++ }
                        var left = (headers["content-length"] ?: "0").toLong()
                        FileOutputStream(dest).use { fo -> val buf = ByteArray(65536); while (left > 0) { val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; fo.write(buf, 0, r); left -= r } }
                        note("Upload: ${dest.name}")
                        send(out, 200, "text/plain", ("Saved as " + dest.name).toByteArray())
                    }
                    method == "GET" && path == "/" -> {
                        if (!f.isDirectory) { send(out, 404, "text/plain", "Not a folder".toByteArray()); return }
                        val sb = StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Phone files</title><style>body{font-family:system-ui;background:#151515;color:#eee;max-width:760px;margin:20px auto;padding:0 12px}a{color:#7db7ff;text-decoration:none}li{padding:8px 0;border-bottom:1px solid #333;list-style:none}small{color:#999;float:right}ul{padding:0}button,input{font:inherit}</style>")
                        sb.append("<h2>/").append(esc(rel)).append("</h2><ul>")
                        if (f.path != root.path) sb.append("<li><a href='/?p=").append(enc(rel.substringBeforeLast('/', ""))).append("'>&#8593; Up</a></li>")
                        val kids = (f.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        for (k in kids) {
                            val kr = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                            if (k.isDirectory) sb.append("<li>&#128193; <a href='/?p=").append(enc(kr)).append("'>").append(esc(k.name)).append("</a></li>")
                            else sb.append("<li>&#128196; <a href='/dl?p=").append(enc(kr)).append("'>").append(esc(k.name)).append("</a><small>").append(k.length() / 1024).append(" KB</small></li>")
                        }
                        sb.append("</ul>")
                        if (allowUpload) sb.append("<h3>Send files to this folder</h3><input type=file id=f multiple><button onclick=\"up()\">Upload</button><pre id=m></pre><script>async function up(){const fs=document.getElementById('f').files;for(const x of fs){const r=await fetch('/up?p='+encodeURIComponent(").append("\"").append(esc(rel).replace("\\", "\\\\").replace("'", "\\'")).append("\"").append(")+'&n='+encodeURIComponent(x.name),{method:'POST',body:x});document.getElementById('m').textContent+=await r.text()+'\\n'}location.reload()}</script>")
                        else sb.append("<p><small>Uploads are off.</small></p>")
                        send(out, 200, "text/html; charset=utf-8", sb.toString().toByteArray())
                    }
                    else -> send(out, 404, "text/plain", "Not found".toByteArray())
                }
            } catch (e: Exception) {}
        }
    }
}
