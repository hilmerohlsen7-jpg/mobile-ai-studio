package com.mobileaistudio.app

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    class Conn(val type: String, val host: String, val port: Int, val user: String, val pass: String, val hostKey: String, val ftps: Boolean = false, val active: Boolean = false) {
        companion object { fun of(o: JSONObject) = Conn(o.optString("type", "ftp"), o.getString("host").trim(), o.optInt("port", when (o.optString("type")) { "sftp" -> 22; "smb" -> 445; "dav" -> 443; else -> 21 }), o.optString("user", "anonymous"), o.optString("pass", ""), o.optString("hostKey", ""), o.optBoolean("ftps", false), o.optBoolean("active", false)) }
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
        val f: FTPClient = if (c.ftps) org.apache.commons.net.ftp.FTPSClient("TLS", false) else FTPClient()
        init {
            f.connectTimeout = 15000; f.defaultTimeout = 30000
            f.connect(c.host, c.port)
            if (!f.login(c.user.ifEmpty { "anonymous" }, c.pass.ifEmpty { "guest@" })) throw Exception("Wrong user name or password.")
            if (c.ftps) { val sf = f as org.apache.commons.net.ftp.FTPSClient; sf.execPBSZ(0); sf.execPROT("P") }
            if (c.active) f.enterLocalActiveMode() else f.enterLocalPassiveMode(); f.setFileType(FTP.BINARY_FILE_TYPE); f.controlEncoding = "UTF-8"
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
            ctx = if (c.user.isEmpty()) base.withCredentials(jcifs.smb.NtlmPasswordAuthenticator("", "guest", "")) else base.withCredentials(jcifs.smb.NtlmPasswordAuthenticator(dom, usr, c.pass))
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

    /** WebDAV: Nextcloud, ownCloud, Box, Koofr, pCloud, Yandex and most NAS boxes. host = the full web address. */
    private class Dav(val c: Conn) : Client {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
        val base = (if (c.host.startsWith("http")) c.host else "https://" + c.host).trimEnd('/')
        val auth = if (c.user.isNotEmpty()) okhttp3.Credentials.basic(c.user, c.pass) else ""
        private fun enc(p: String) = p.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        private fun url(p: String, dir: Boolean = false): String { val e = enc(p); return base + (if (e.isEmpty()) "/" else "/" + e + (if (dir) "/" else "")) }
        private fun req(url: String): okhttp3.Request.Builder { val b = okhttp3.Request.Builder().url(url); if (auth.isNotEmpty()) b.header("Authorization", auth); return b }
        private fun check(r: okhttp3.Response, what: String) { if (r.code == 401) throw Exception("Wrong user name or password."); if (!r.isSuccessful && r.code != 207) throw Exception(what + " (" + r.code + " " + r.message + ")") }
        private fun propfind(p: String, depth: String): org.w3c.dom.Document {
            val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
            val r = http.newCall(req(url(p, true)).header("Depth", depth).method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()).execute()
            r.use { check(it, "Could not open this folder"); val f = javax.xml.parsers.DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
                try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) {}
                return f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(it.body!!.string()))) }
        }
        override fun list(path: String): List<Item> {
            val d = propfind(path, "1"); val rs = d.getElementsByTagNameNS("DAV:", "response"); val out = ArrayList<Item>()
            val self = java.net.URLDecoder.decode(java.net.URL(url(path, true)).path, "UTF-8").trimEnd('/')
            for (i in 0 until rs.length) {
                val e = rs.item(i) as org.w3c.dom.Element
                val href = java.net.URLDecoder.decode(e.getElementsByTagNameNS("DAV:", "href").item(0).textContent.trim().let { if (it.startsWith("http")) java.net.URL(it).path else it }.replace("+", "%2B"), "UTF-8")
                if (href.trimEnd('/') == self) continue
                val dir = e.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = e.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                val mt = try { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(e.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent ?: "")?.time ?: 0L } catch (x: Exception) { 0L }
                out.add(Item(href.trimEnd('/').substringAfterLast('/'), dir, if (dir) 0L else size, mt))
            }
            return out
        }
        override fun download(remote: String, local: File) { http.newCall(req(url(remote)).build()).execute().use { check(it, "Download failed"); FileOutputStream(local).use { o -> it.body!!.byteStream().copyTo(o, 65536) } } }
        override fun upload(local: File, remote: String) { http.newCall(req(url(remote)).put(local.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use { check(it, "Upload failed") } }
        override fun mkdir(path: String) { http.newCall(req(url(path, true)).method("MKCOL", null).build()).execute().use { check(it, "Could not make the folder") } }
        override fun rename(from: String, to: String) { http.newCall(req(url(from)).header("Destination", url(to)).header("Overwrite", "F").method("MOVE", null).build()).execute().use { check(it, "Could not rename") } }
        override fun deleteFile(path: String) { http.newCall(req(url(path)).delete().build()).execute().use { check(it, "Could not delete") } }
        override fun deleteDir(path: String) { http.newCall(req(url(path, true)).delete().build()).execute().use { check(it, "Could not delete the folder") } }
        override fun isDir(path: String): Boolean = try { propfind(path, "0").getElementsByTagNameNS("DAV:", "collection").length > 0 } catch (e: Exception) { false }
        override fun close() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    /** Google Drive. user = "clientId|clientSecret", pass = refresh token. Deleting only moves things to the Drive trash. */
    private class Gdrive(val c: Conn) : Client {
        val http = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        val token: String
        private val folder = "application/vnd.google-apps.folder"
        init {
            val id = c.user.substringBefore('|'); val sec = c.user.substringAfter('|', "")
            val body = okhttp3.FormBody.Builder().add("client_id", id).add("client_secret", sec).add("refresh_token", c.pass).add("grant_type", "refresh_token").build()
            http.newCall(okhttp3.Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use {
                val j = JSONObject(it.body?.string() ?: "{}")
                if (!it.isSuccessful || !j.has("access_token")) throw Exception("Google sign-in expired. Remove this storage and add it again (in Testing mode Google signs you out after 7 days).")
                token = j.getString("access_token")
            }
        }
        private fun call(url: String, method: String = "GET", body: okhttp3.RequestBody? = null): String {
            val r = okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, if (method == "GET") null else (body ?: "".toRequestBody(null))).build()
            http.newCall(r).execute().use { if (!it.isSuccessful) throw Exception("Google Drive said " + it.code + ": " + (try { JSONObject(it.body?.string() ?: "{}").optJSONObject("error")?.optString("message") } catch (e: Exception) { "" })); return it.body?.string() ?: "" }
        }
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
        private fun q(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        private val exts = mapOf(".pdf" to "application/vnd.google-apps.document", ".xlsx" to "application/vnd.google-apps.spreadsheet", ".pptx" to "application/vnd.google-apps.presentation")
        private fun child(parent: String, name: String): JSONObject? {
            var r = JSONObject(call("https://www.googleapis.com/drive/v3/files?pageSize=5&fields=files(id,mimeType)&q=" + q("name='" + esc(name) + "' and '" + parent + "' in parents and trashed=false"))).getJSONArray("files")
            if (r.length() > 0) return r.getJSONObject(0)
            for ((e, mt) in exts) if (name.endsWith(e)) {
                r = JSONObject(call("https://www.googleapis.com/drive/v3/files?pageSize=5&fields=files(id,mimeType)&q=" + q("name='" + esc(name.removeSuffix(e)) + "' and '" + parent + "' in parents and mimeType='" + mt + "' and trashed=false"))).getJSONArray("files")
                if (r.length() > 0) return r.getJSONObject(0)
            }
            return null
        }
        private fun idOf(path: String): String {
            var cur = "root"
            for (seg in path.split('/').filter { it.isNotEmpty() }) cur = child(cur, seg)?.getString("id") ?: throw Exception("Not found in Google Drive: $seg")
            return cur
        }
        private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "")
        private fun nameOf(path: String) = path.trimEnd('/').substringAfterLast('/')
        private fun time(s: String) = try { val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US); f.timeZone = java.util.TimeZone.getTimeZone("UTC"); f.parse(s.take(19))?.time ?: 0L } catch (e: Exception) { 0L }
        override fun list(path: String): List<Item> {
            val id = idOf(path); val out = ArrayList<Item>(); var page = ""
            do {
                val j = JSONObject(call("https://www.googleapis.com/drive/v3/files?pageSize=1000&fields=nextPageToken,files(name,mimeType,size,modifiedTime)&q=" + q("'" + id + "' in parents and trashed=false") + (if (page.isNotEmpty()) "&pageToken=" + q(page) else "")))
                val a = j.getJSONArray("files")
                for (i in 0 until a.length()) { val f = a.getJSONObject(i); val mt = f.getString("mimeType"); val dir = mt == folder
                    val ext = exts.entries.firstOrNull { it.value == mt }?.key ?: ""
                    out.add(Item(f.getString("name") + ext, dir, f.optString("size", "0").toLongOrNull() ?: 0L, time(f.optString("modifiedTime", "")))) }
                page = j.optString("nextPageToken", "")
            } while (page.isNotEmpty())
            return out
        }
        override fun download(remote: String, local: File) {
            val f = child(idOf(parentOf(remote)), nameOf(remote)) ?: throw Exception("Not found in Google Drive.")
            val mt = f.getString("mimeType"); val id = f.getString("id")
            val url = if (mt.startsWith("application/vnd.google-apps.")) { val exp = when (mt) { "application/vnd.google-apps.spreadsheet" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; "application/vnd.google-apps.presentation" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"; else -> "application/pdf" }
                "https://www.googleapis.com/drive/v3/files/$id/export?mimeType=" + q(exp) } else "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            http.newCall(okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { if (!it.isSuccessful) throw Exception("Download failed (" + it.code + ")"); FileOutputStream(local).use { o -> it.body!!.byteStream().copyTo(o, 65536) } }
        }
        override fun upload(local: File, remote: String) {
            val meta = JSONObject().put("name", nameOf(remote)).put("parents", JSONArray().put(idOf(parentOf(remote)))).toString()
            val body = okhttp3.MultipartBody.Builder().setType("multipart/related".toMediaType()).addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType())).addPart(local.asRequestBody("application/octet-stream".toMediaType())).build()
            call("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", "POST", body)
        }
        override fun mkdir(path: String) { call("https://www.googleapis.com/drive/v3/files", "POST", JSONObject().put("name", nameOf(path)).put("mimeType", folder).put("parents", JSONArray().put(idOf(parentOf(path)))).toString().toRequestBody("application/json".toMediaType())) }
        override fun rename(from: String, to: String) {
            val id = idOf(from); val np = idOf(parentOf(to)); val op = idOf(parentOf(from))
            call("https://www.googleapis.com/drive/v3/files/$id" + (if (np != op) "?addParents=$np&removeParents=$op" else ""), "PATCH", JSONObject().put("name", nameOf(to)).toString().toRequestBody("application/json".toMediaType()))
        }
        private fun trash(path: String) { call("https://www.googleapis.com/drive/v3/files/" + idOf(path), "PATCH", JSONObject().put("trashed", true).toString().toRequestBody("application/json".toMediaType())) }
        override fun deleteFile(path: String) = trash(path)
        override fun deleteDir(path: String) = trash(path)
        override fun isDir(path: String): Boolean = try { if (path.trim('/').isEmpty()) true else child(idOf(parentOf(path)), nameOf(path))?.getString("mimeType") == folder } catch (e: Exception) { false }
        override fun close() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    /** A folder picked through Android's own storage picker (Google Drive app, Dropbox, OneDrive...). host = the tree address. */
    private class Saf(val c: Conn) : Client {
        val cr = appContext!!.contentResolver; val tree: android.net.Uri = android.net.Uri.parse(c.host)
        class D(val id: String, val name: String, val mime: String, val size: Long, val mtime: Long)
        private fun docUri(id: String) = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
        private fun kids(id: String): List<D> {
            val out = ArrayList<D>()
            val cols = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            cr.query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { while (it.moveToNext()) out.add(D(it.getString(0), it.getString(1) ?: "?", it.getString(2) ?: "", if (it.isNull(3)) 0L else it.getLong(3), if (it.isNull(4)) 0L else it.getLong(4))) }
            return out
        }
        private val isDirMime = android.provider.DocumentsContract.Document.MIME_TYPE_DIR
        private fun rootId() = android.provider.DocumentsContract.getTreeDocumentId(tree)
        private fun find(path: String): D? {
            var cur: D? = null; var id = rootId()
            for (seg in path.split('/').filter { it.isNotEmpty() }) { cur = kids(id).firstOrNull { it.name == seg } ?: return null; id = cur.id }
            return cur
        }
        private fun idOf(path: String): String = if (path.trim('/').isEmpty()) rootId() else (find(path)?.id ?: throw Exception("Not found: " + path))
        private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "")
        private fun nameOf(path: String) = path.trimEnd('/').substringAfterLast('/')
        override fun list(path: String) = kids(idOf(path)).map { Item(it.name, it.mime == isDirMime, if (it.mime == isDirMime) 0L else it.size, it.mtime) }
        override fun download(remote: String, local: File) { val i = cr.openInputStream(docUri(idOf(remote))) ?: throw Exception("Could not open it."); i.use { x -> FileOutputStream(local).use { x.copyTo(it, 65536) } } }
        override fun upload(local: File, remote: String) {
            val name = nameOf(remote); val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.extension.lowercase()) ?: "application/octet-stream"
            val u = android.provider.DocumentsContract.createDocument(cr, docUri(idOf(parentOf(remote))), mime, name) ?: throw Exception("This storage refused the upload.")
            (cr.openOutputStream(u, "w") ?: throw Exception("Could not write it.")).use { o -> local.inputStream().use { it.copyTo(o, 65536) } }
        }
        override fun mkdir(path: String) { android.provider.DocumentsContract.createDocument(cr, docUri(idOf(parentOf(path))), isDirMime, nameOf(path)) ?: throw Exception("Could not make the folder.") }
        override fun rename(from: String, to: String) { if (parentOf(from) != parentOf(to)) throw Exception("This storage can only rename inside the same folder."); android.provider.DocumentsContract.renameDocument(cr, docUri(idOf(from)), nameOf(to)) ?: throw Exception("Could not rename.") }
        override fun deleteFile(path: String) { if (!android.provider.DocumentsContract.deleteDocument(cr, docUri(idOf(path)))) throw Exception("This storage refused the delete.") }
        override fun deleteDir(path: String) = deleteFile(path)
        override fun isDir(path: String): Boolean = if (path.trim('/').isEmpty()) true else try { find(path)?.mime == isDirMime } catch (e: Exception) { false }
        override fun close() {}
    }

    @JvmField var appContext: android.content.Context? = null
    fun open(c: Conn): Client = when (c.type) { "saf" -> Saf(c); "gdrive" -> Gdrive(c); "dav" -> Dav(c); "sftp" -> Sftp(c); "smb" -> Smb(c); else -> Ftp(c) }
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
