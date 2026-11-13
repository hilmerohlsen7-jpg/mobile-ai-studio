package com.mobileaistudio.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone's own FTP server. Safe by design: it never deletes, renames or replaces anything,
 * uploads only create NEW files, and "read-only" turns uploads off completely.
 * Logins: admin + a random PIN, or anonymous (anonymous is always read-only).
 */
object FtpServer {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false; private set
    var pin = ""; private set
    var url = ""; private set
    var port = 2222; private set
    var readOnly = true; private set
    var anon = false; private set
    private var root: File = File("/")
    private var fails = 0
    private var lockUntil = 0L
    val log = ArrayList<String>()

    private fun note(s: String) { synchronized(log) { log.add(s); if (log.size > 80) log.removeAt(0) } }

    @Synchronized fun start(rootDir: File, p: Int, ro: Boolean, allowAnon: Boolean): String? {
        stop()
        val ip = WifiServer.localIp() ?: return "Connect to Wi-Fi first."
        if (p < 1024 || p > 65535) return "Use a port between 1024 and 65535."
        return try {
            val ss = ServerSocket(p); server = ss; root = rootDir.canonicalFile; port = p; readOnly = ro; anon = allowAnon
            pin = (100000 + SecureRandom().nextInt(900000)).toString(); fails = 0; lockUntil = 0
            url = "ftp://$ip:$p"; running = true; synchronized(log) { log.clear() }; note("Started")
            pool.execute { while (running) { try { val c = ss.accept(); pool.execute { Session(c).run() } } catch (e: Exception) { break } } }
            null
        } catch (e: Exception) { e.message ?: "Could not start." }
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
        if (url.isNotEmpty()) note("Stopped")
    }

    private class Session(val ctl: Socket) {
        val out = ctl.getOutputStream().bufferedWriter(Charsets.UTF_8)
        var user = ""; var authed = false; var anonSession = false
        var cwd = "/"; var pasv: ServerSocket? = null; var rest = 0L
        fun say(s: String) { out.write(s + "\r\n"); out.flush() }
        fun file(v: String): File? {
            val full = if (v.startsWith("/")) v else (if (cwd == "/") "/$v" else "$cwd/$v")
            val f = File(root, full).canonicalFile
            return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
        }
        fun virt(f: File): String { val r = f.path.removePrefix(root.path); return if (r.isEmpty()) "/" else r }
        fun data(): Socket? { return try { val s = pasv?.accept(); s } catch (e: Exception) { null } finally { try { pasv?.close() } catch (e: Exception) {}; pasv = null } }
        fun ro() = readOnly || anonSession

        fun run() {
            try {
                ctl.soTimeout = 300000
                val inp = ctl.getInputStream().bufferedReader(Charsets.UTF_8)
                say("220 Mobile AI Studio FTP ready")
                while (true) {
                    val line = inp.readLine() ?: break
                    val sp = line.indexOf(' '); val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(); val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
                    if (cmd == "QUIT") { say("221 Bye"); break }
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {} finally { try { pasv?.close() } catch (e: Exception) {}; try { ctl.close() } catch (e: Exception) {} }
        }

        fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> { user = arg; say("331 Password please") }
                "PASS" -> {
                    if (System.currentTimeMillis() < lockUntil) { say("530 Too many wrong tries. Wait a minute."); return true }
                    val a = anon && (user.equals("anonymous", true) || user.equals("ftp", true))
                    if (a) { authed = true; anonSession = true; note("Anonymous login"); say("230 Logged in (read-only)") }
                    else if (user == "admin" && arg == pin) { authed = true; fails = 0; note("Login: admin"); say("230 Logged in") }
                    else { fails++; if (fails >= 5) { lockUntil = System.currentTimeMillis() + 60000; fails = 0; note("Locked for a minute after wrong tries") }; note("Wrong login"); say("530 Wrong user name or password") }
                }
                "SYST" -> say("215 UNIX Type: L8")
                "FEAT" -> say("211-Features:\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n UTF8\r\n211 End")
                "OPTS" -> say("200 OK")
                "NOOP" -> say("200 OK")
                "TYPE", "MODE", "STRU" -> say("200 OK")
                else -> { if (!authed) { say("530 Log in first"); return true }; return authed(cmd, arg) }
            }
            return true
        }

        fun authed(cmd: String, arg: String): Boolean {
            when (cmd) {
                "PWD", "XPWD" -> say("257 \"$cwd\"")
                "CWD", "XCWD" -> { val f = file(arg); if (f != null && f.isDirectory) { cwd = virt(f); say("250 OK") } else say("550 No such folder") }
                "CDUP", "XCUP" -> { val f = file(".."); if (f != null && f.isDirectory) { cwd = virt(f); say("250 OK") } else say("550 No such folder") }
                "PASV" -> {
                    try { pasv?.close() } catch (e: Exception) {}
                    val s = ServerSocket(0); s.soTimeout = 20000; pasv = s
                    val ip = ctl.localAddress.hostAddress!!.replace('.', ','); say("227 Entering Passive Mode ($ip,${s.localPort / 256},${s.localPort % 256})")
                }
                "EPSV" -> { try { pasv?.close() } catch (e: Exception) {}; val s = ServerSocket(0); s.soTimeout = 20000; pasv = s; say("229 Entering Extended Passive Mode (|||${s.localPort}|)") }
                "PORT", "EPRT" -> say("502 Use passive mode")
                "LIST", "NLST", "MLSD" -> {
                    val a = if (arg.startsWith("-")) "" else arg
                    val f = if (a.isEmpty()) file(".") else file(a)
                    if (f == null || !f.exists()) { say("550 Not found"); return true }
                    say("150 Here comes the listing"); val d = data() ?: run { say("425 No data connection"); return true }
                    try {
                        val w = d.getOutputStream().bufferedWriter(Charsets.UTF_8); val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                        val items = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() } else listOf(f)
                        for (i in items) {
                            if (cmd == "NLST") w.write(i.name + "\r\n")
                            else if (cmd == "MLSD") w.write("type=" + (if (i.isDirectory) "dir" else "file") + ";size=" + i.length() + ";modify=" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(i.lastModified())) + "; " + i.name + "\r\n")
                            else w.write((if (i.isDirectory) "drwxr-xr-x" else "-rw-r--r--") + " 1 owner group " + i.length().toString().padStart(12) + " " + fmt.format(Date(i.lastModified())) + " " + i.name + "\r\n")
                        }
                        w.flush(); d.close(); say("226 Done")
                    } catch (e: Exception) { try { d.close() } catch (x: Exception) {}; say("426 Transfer failed") }
                }
                "SIZE" -> { val f = file(arg); if (f != null && f.isFile) say("213 " + f.length()) else say("550 No such file") }
                "MDTM" -> { val f = file(arg); if (f != null && f.exists()) say("213 " + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(f.lastModified()))) else say("550 No such file") }
                "REST" -> { rest = arg.toLongOrNull() ?: 0L; say("350 OK") }
                "RETR" -> {
                    val f = file(arg); if (f == null || !f.isFile) { say("550 No such file"); return true }
                    say("150 Sending"); val d = data() ?: run { say("425 No data connection"); return true }
                    try { RandomAccessFile(f, "r").use { r -> if (rest > 0) r.seek(rest); val o = d.getOutputStream(); val b = ByteArray(65536); while (true) { val n = r.read(b); if (n < 0) break; o.write(b, 0, n) }; o.flush() }; d.close(); note("Sent " + f.name); say("226 Done") }
                    catch (e: Exception) { try { d.close() } catch (x: Exception) {}; say("426 Transfer failed") }
                    rest = 0
                }
                "STOR" -> {
                    if (ro()) { say("550 This server is read-only"); return true }
                    val f = file(arg); if (f == null) { say("550 Not allowed"); return true }
                    if (f.exists()) { say("550 That file already exists. This server never replaces files."); return true }
                    say("150 Receiving"); val d = data() ?: run { say("425 No data connection"); return true }
                    try { FileOutputStream(f).use { o -> val b = ByteArray(65536); val i = d.getInputStream(); while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n) } }; d.close(); note("Received " + f.name); say("226 Done") }
                    catch (e: Exception) { try { d.close() } catch (x: Exception) {}; f.delete(); say("426 Transfer failed") }
                }
                "MKD", "XMKD" -> {
                    if (ro()) { say("550 This server is read-only"); return true }
                    val f = file(arg); if (f != null && !f.exists() && f.mkdir()) { note("Folder " + f.name); say("257 \"" + virt(f) + "\" created") } else say("550 Could not create it")
                }
                "DELE", "RMD", "XRMD", "RNFR", "RNTO", "APPE" -> say("550 Not allowed: this server never deletes, renames or replaces files.")
                else -> say("502 Not supported")
            }
            return true
        }
    }
}
