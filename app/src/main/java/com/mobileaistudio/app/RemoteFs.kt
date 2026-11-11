package com.mobileaistudio.app

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** FTP and SFTP browsing and transfers. Each call opens its own short connection. */
object RemoteFs {
    class NeedHostKey(val fp: String) : Exception("hostkey")
    class Conn(val type: String, val host: String, val port: Int, val user: String, val pass: String, val hostKey: String) {
        companion object { fun of(o: JSONObject) = Conn(o.optString("type", "ftp"), o.getString("host").trim(), o.optInt("port", when (o.optString("type")) { "sftp" -> 22; "smb" -> 445; else -> 21 }), o.optString("user", "anonymous"), o.optString("pass", ""), o.optString("hostKey", "")) }
    }
    class Item(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    interface Client : AutoCloseable {
        fun list(path: String): List<Item>
        fun download(remote: String, local: File)
        fun upload(local: File, remote: String)
        fun mkdir(path: String)
        fun rename(from: String, to: String)
        fun deleteFile(path: String)
        fun deleteDir(path: String)
        fun isDir(path: String): Boolean
    }

    private class Ftp(c: Conn) : Client {
        val f = FTPClient()
        init {
            f.connectTimeout = 15000; f.defaultTimeout = 30000
            f.connect(c.host, c.port)
            if (!f.login(c.user.ifEmpty { "anonymous" }, c.pass.ifEmpty { "guest@" })) throw Exception("Wrong user name or password.")
            f.enterLocalPassiveMode(); f.setFileType(FTP.BINARY_FILE_TYPE); f.controlEncoding = "UTF-8"
        }
        override fun list(path: String) = (f.listFiles(path) ?: emptyArray()).filter { it.name != "." && it.name != ".." }.map { Item(it.name, it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0L) }
        override fun download(remote: String, local: File) { FileOutputStream(local).use { if (!f.retrieveFile(remote, it)) throw Exception("Download failed: " + f.replyString.trim()) } }
        override fun upload(local: File, remote: String) { local.inputStream().use { if (!f.storeFile(remote, it)) throw Exception("Upload failed: " + f.replyString.trim()) } }
        override fun mkdir(path: String) { if (!f.makeDirectory(path)) throw Exception("Could not make the folder: " + f.replyString.trim()) }
        override fun rename(from: String, to: String) { if (!f.rename(from, to)) throw Exception("Could not rename: " + f.replyString.trim()) }
        override fun deleteFile(path: String) { if (!f.deleteFile(path)) throw Exception("Could not delete: " + f.replyString.trim()) }
        override fun deleteDir(path: String) { if (!f.removeDirectory(path)) throw Exception("Could not delete the folder: " + f.replyString.trim()) }
        override fun isDir(path: String): Boolean { val cur = f.printWorkingDirectory(); val ok = f.changeWorkingDirectory(path); if (ok && cur != null) f.changeWorkingDirectory(cur); return ok }
        override fun close() { try { if (f.isConnected) { f.logout(); f.disconnect() } } catch (e: Exception) {} }
    }

    private class Sftp(c: Conn) : Client {
        val sess: Session; val ch: ChannelSftp
        init {
            val j = JSch()
            sess = j.getSession(c.user, c.host, c.port)
            sess.setPassword(c.pass)
            sess.setConfig("StrictHostKeyChecking", "no")
            sess.connect(15000)
            val fp = sess.hostKey.getFingerPrint(j)
            if (c.hostKey.isEmpty()) { sess.disconnect(); throw NeedHostKey(fp) }
            if (c.hostKey != fp) { sess.disconnect(); throw Exception("The server's key changed since you trusted it. Someone may be intercepting the connection. Remove and re-add the connection only if you expect this.") }
            ch = sess.openChannel("sftp") as ChannelSftp; ch.connect(15000)
        }
        @Suppress("UNCHECKED_CAST")
        override fun list(path: String) = (ch.ls(path) as java.util.Vector<ChannelSftp.LsEntry>).filter { it.filename != "." && it.filename != ".." }
            .map { Item(it.filename, it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }
        override fun download(remote: String, local: File) { FileOutputStream(local).use { ch.get(remote, it) } }
        override fun upload(local: File, remote: String) { local.inputStream().use { ch.put(it, remote) } }
        override fun mkdir(path: String) { ch.mkdir(path) }
        override fun rename(from: String, to: String) { ch.rename(from, to) }
        override fun deleteFile(path: String) { ch.rm(path) }
        override fun deleteDir(path: String) { ch.rmdir(path) }
        override fun isDir(path: String): Boolean = try { ch.stat(path).isDir } catch (e: Exception) { false }
        override fun close() { try { ch.disconnect(); sess.disconnect() } catch (e: Exception) {} }
    }

    /** Windows / Samba shares. Path "/" lists the shares, "/share/folder" goes inside. */
    private class Smb(val c: Conn) : Client {
        val ctx: jcifs.CIFSContext
        init {
            val props = java.util.Properties()
            props.setProperty("jcifs.smb.client.responseTimeout", "20000"); props.setProperty("jcifs.smb.client.connTimeout", "15000")
            props.setProperty("jcifs.smb.client.minVersion", "SMB202"); props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
            val base = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(props))
            val dom = if (c.user.contains('\\')) c.user.substringBefore('\\') else ""
            val usr = c.user.substringAfter('\\')
            ctx = if (c.user.isEmpty()) base.withGuestCredentials() else base.withCredentials(jcifs.smb.NtlmPasswordAuthenticator(dom, usr, c.pass))
        }
        private fun f(path: String, dir: Boolean = false): jcifs.smb.SmbFile {
            var p = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            if (dir || p.isEmpty()) p += "/"
            return jcifs.smb.SmbFile("smb://" + c.host + (if (c.port != 445) ":" + c.port else "") + "/" + p, ctx)
        }
        override fun list(path: String) = (f(path, true).listFiles() ?: emptyArray()).map {
            val n = it.name.trimEnd('/')
            Item(n, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified())
        }
        override fun download(remote: String, local: File) { f(remote).inputStream.use { i -> FileOutputStream(local).use { i.copyTo(it, 65536) } } }
        override fun upload(local: File, remote: String) { f(remote).outputStream.use { o -> local.inputStream().use { it.copyTo(o, 65536) } } }
        override fun mkdir(path: String) { f(path, true).mkdir() }
        override fun rename(from: String, to: String) { f(from).renameTo(f(to)) }
        override fun deleteFile(path: String) { f(path).delete() }
        override fun deleteDir(path: String) { f(path, true).delete() }
        override fun isDir(path: String): Boolean = try { f(path).isDirectory } catch (e: Exception) { false }
        override fun close() { try { ctx.close() } catch (e: Exception) {} }
    }

    fun open(c: Conn): Client = when (c.type) { "sftp" -> Sftp(c); "smb" -> Smb(c); else -> Ftp(c) }
    private fun join(a: String, b: String) = a.trimEnd('/') + "/" + b
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 1; val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
        while (f.exists()) { f = File(dir, if (ext.isEmpty() || base == name) "$name ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    fun list(c: Conn, path: String): JSONArray = open(c).use { cl ->
        val arr = JSONArray()
        for (i in cl.list(path)) arr.put(JSONObject().put("name", i.name).put("dir", i.dir).put("size", i.size).put("mtime", i.mtime))
        arr
    }

    /** Copies a remote file or folder into a local folder. Never replaces anything. Returns how many files came across. */
    fun download(c: Conn, remote: String, localDir: File): Int = open(c).use { cl ->
        fun go(r: String, dir: File): Int {
            val name = r.trimEnd('/').substringAfterLast('/')
            return if (cl.isDir(r)) {
                val d = unique(dir, name); d.mkdirs(); var n = 0
                for (i in cl.list(r)) n += go(join(r, i.name), d); n
            } else { cl.download(r, unique(dir, name)); 1 }
        }
        go(remote, localDir)
    }

    fun upload(c: Conn, local: File, remoteDir: String): Int = open(c).use { cl ->
        fun exists(dir: String, name: String) = cl.list(dir).any { it.name == name }
        fun go(l: File, dir: String): Int {
            var name = l.name; var n = 1
            while (exists(dir, name)) { name = (if (l.isDirectory) l.name else l.nameWithoutExtension) + " ($n)" + (if (l.isFile && l.extension.isNotEmpty()) "." + l.extension else ""); n++ }
            val target = join(dir, name)
            return if (l.isDirectory) { cl.mkdir(target); var k = 0; for (ch in l.listFiles() ?: emptyArray()) k += go(ch, target); k } else { cl.upload(l, target); 1 }
        }
        go(local, remoteDir)
    }

    fun mkdir(c: Conn, path: String) = open(c).use { it.mkdir(path) }
    fun rename(c: Conn, from: String, to: String) = open(c).use { it.rename(from, to) }
    fun delete(c: Conn, path: String) = open(c).use { cl ->
        fun go(p: String) {
            if (cl.isDir(p)) { for (i in cl.list(p)) go(join(p, i.name)); cl.deleteDir(p) } else cl.deleteFile(p)
        }
        go(path)
    }
}
