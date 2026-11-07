package com.mobileaistudio.app.fs;

import com.mobileaistudio.app.dl.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and changes files inside the phone's storage. Every change is written to a history, anything
 * that is replaced or deleted is first kept in a Trash folder, and every change can be undone.
 * The approval question is asked by the app before any of these are called.
 */
public final class FileOps {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_READ = 200 * 1024;
    private static final int MAX_WRITE = 5 * 1024 * 1024;
    private static final int MAX_HISTORY = 1000;

    public static final class Item {
        public String name;
        public boolean dir;
        public long size;
        public long mtime;
    }

    public static final class Text {
        public String text;
        public boolean truncated;
        public long size;
    }

    public static final class Entry {
        public String id;
        public long time;
        public String op; // create, replace, copy_write, mkdir, rename, copy, delete
        public String path = "";
        public String from = "";
        public String backup = "";
        public String reason = "";
        public long postMtime;
        public long size;
        public boolean undone;
        public boolean purged;

        String toLine() {
            StringBuilder b = new StringBuilder();
            put(b, "id", id);
            put(b, "time", Long.toString(time));
            put(b, "op", op);
            put(b, "path", path);
            put(b, "from", from);
            put(b, "backup", backup);
            put(b, "reason", reason);
            put(b, "mtime", Long.toString(postMtime));
            put(b, "size", Long.toString(size));
            put(b, "undone", undone ? "1" : "0");
            put(b, "purged", purged ? "1" : "0");
            return b.toString();
        }

        private static void put(StringBuilder b, String k, String v) {
            if (b.length() > 0) b.append('&');
            b.append(k).append('=').append(Util.enc(v == null ? "" : v));
        }

        static Entry fromLine(String line) {
            Map<String, String> m = new HashMap<>();
            for (String part : line.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) m.put(part.substring(0, eq), Util.dec(part.substring(eq + 1)));
            }
            if (m.get("id") == null || m.get("op") == null) return null;
            Entry e = new Entry();
            e.id = m.get("id");
            e.op = m.get("op");
            e.path = nz(m.get("path"));
            e.from = nz(m.get("from"));
            e.backup = nz(m.get("backup"));
            e.reason = nz(m.get("reason"));
            e.time = num(m.get("time"));
            e.postMtime = num(m.get("mtime"));
            e.size = num(m.get("size"));
            e.undone = "1".equals(m.get("undone"));
            e.purged = "1".equals(m.get("purged"));
            return e;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }

        private static long num(String s) {
            try {
                return Long.parseLong(s);
            } catch (Exception e) {
                return 0;
            }
        }
    }

    public static final class Result {
        public boolean ok;
        public String msg = "";
        /** "changed" when undo would overwrite newer changes (ask the person, then try again with force). */
        public String code = "";
        public Entry entry;
        public String path = "";

        static Result fail(String msg) {
            Result r = new Result();
            r.msg = msg;
            return r;
        }

        static Result ok(String msg, Entry e, String path) {
            Result r = new Result();
            r.ok = true;
            r.msg = msg;
            r.entry = e;
            r.path = path;
            return r;
        }
    }

    private final File root;
    private final File trash;
    private final File historyFile;
    private final List<Entry> history = new ArrayList<>();

    public FileOps(File root, File trash, File historyFile) {
        this.root = root;
        this.trash = trash;
        this.historyFile = historyFile;
        loadHistory();
    }

    public File root() {
        return root;
    }

    // ---------- paths ----------

    /** Turns a path into a real file inside the storage, or explains why it is not allowed. */
    public File resolve(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) throw new IOException("No path given.");
        File f = new File(path.trim());
        if (!f.isAbsolute()) f = new File(root, path.trim());
        f = f.getCanonicalFile();
        String rp = root.getCanonicalPath();
        String fp = f.getPath();
        if (!(fp.equals(rp) || fp.startsWith(rp + File.separator))) {
            throw new IOException("That location is outside the phone's storage.");
        }
        String tp = trash.getCanonicalPath();
        if (fp.equals(tp) || fp.startsWith(tp + File.separator)) {
            throw new IOException("The Trash folder is managed by the app.");
        }
        String rel = fp.substring(rp.length());
        if (rel.equals("/Android/data") || rel.startsWith("/Android/data/") || rel.equals("/Android/obb") || rel.startsWith("/Android/obb/")) {
            throw new IOException("Android keeps this folder private.");
        }
        return f;
    }

    // ---------- finding space hogs ----------

    public static final class Big {
        public String path;
        public long size;
        public long mtime;
    }

    public static final class Scan {
        public List<Big> files = new ArrayList<>();
        public boolean partial;
        public long scanned;
    }

    /** The biggest files under a folder (at least minBytes), largest first. Stops after maxMillis. */
    public Scan largest(String startPath, long minBytes, int limit, long maxMillis) throws IOException {
        File start = resolve(startPath);
        if (!start.exists()) throw new IOException("That does not exist.");
        final java.util.PriorityQueue<Big> heap = new java.util.PriorityQueue<>(limit + 1, new Comparator<Big>() {
            @Override
            public int compare(Big a, Big b) {
                return Long.compare(a.size, b.size);
            }
        });
        Scan scan = new Scan();
        String rp = root.getCanonicalPath();
        String tp = trash.getCanonicalPath();
        long deadline = System.currentTimeMillis() + maxMillis;
        java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            if (System.currentTimeMillis() > deadline || scan.scanned > 600000) {
                scan.partial = true;
                break;
            }
            File d = stack.pop();
            if (d.isFile()) {
                consider(d, minBytes, limit, heap, scan);
                continue;
            }
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String kp = k.getPath();
                if (k.isDirectory()) {
                    if (isLink(k)) continue;
                    if (kp.equals(tp) || kp.equals(rp + "/Android/data") || kp.equals(rp + "/Android/obb")) continue;
                    stack.push(k);
                } else if (k.isFile()) {
                    consider(k, minBytes, limit, heap, scan);
                }
            }
        }
        scan.files.addAll(heap);
        Collections.sort(scan.files, new Comparator<Big>() {
            @Override
            public int compare(Big a, Big b) {
                return Long.compare(b.size, a.size);
            }
        });
        return scan;
    }

    private static void consider(File f, long minBytes, int limit, java.util.PriorityQueue<Big> heap, Scan scan) {
        scan.scanned++;
        long len = f.length();
        if (len < minBytes) return;
        if (heap.size() >= limit && len <= heap.peek().size) return;
        Big b = new Big();
        b.path = f.getPath();
        b.size = len;
        b.mtime = f.lastModified();
        heap.add(b);
        if (heap.size() > limit) heap.poll();
    }

    // ---------- reading ----------

    public List<Item> list(String path) throws IOException {
        File d = resolve(path);
        if (!d.isDirectory()) throw new IOException("That is not a folder.");
        File[] kids = d.listFiles();
        if (kids == null) throw new IOException("This folder cannot be opened.");
        String tp = trash.getCanonicalPath();
        List<Item> out = new ArrayList<>();
        for (File k : kids) {
            if (k.getCanonicalPath().equals(tp)) continue;
            Item i = new Item();
            i.name = k.getName();
            i.dir = k.isDirectory();
            i.size = i.dir ? 0 : k.length();
            i.mtime = k.lastModified();
            out.add(i);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        if (out.size() > 3000) return new ArrayList<>(out.subList(0, 3000));
        return out;
    }

    public Text read(String path) throws IOException {
        File f = resolve(path);
        if (!f.exists()) throw new IOException("That does not exist.");
        if (!f.isFile()) throw new IOException("That is not a file.");
        long size = f.length();
        byte[] buf = new byte[(int) Math.min(size, MAX_READ)];
        InputStream in = new FileInputStream(f);
        int got = 0;
        try {
            while (got < buf.length) {
                int n = in.read(buf, got, buf.length - got);
                if (n < 0) break;
                got += n;
            }
        } finally {
            in.close();
        }
        for (int i = 0; i < Math.min(got, 8192); i++) {
            if (buf[i] == 0) throw new IOException("This is not a text file.");
        }
        Text t = new Text();
        t.size = size;
        t.truncated = size > MAX_READ;
        String s = new String(buf, 0, got, UTF8);
        if (t.truncated && s.length() > 0 && s.charAt(s.length() - 1) == '�') s = s.substring(0, s.length() - 1);
        t.text = s;
        return t;
    }

    // ---------- changing ----------

    private Entry begin(String op, String reason) {
        Entry e = new Entry();
        e.id = UUID.randomUUID().toString().substring(0, 8);
        e.time = System.currentTimeMillis();
        e.op = op;
        e.reason = reason == null ? "" : reason;
        return e;
    }

    private static String uniqueName(File dir, String name, String tag) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String cand = base + " (" + tag + ")" + ext;
        int n = 2;
        while (new File(dir, cand).exists()) {
            cand = base + " (" + tag + " " + n + ")" + ext;
            n++;
        }
        return cand;
    }

    private static void writeFile(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp-" + UUID.randomUUID().toString().substring(0, 6));
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Could not write " + target.getName() + ".");
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), to.getName() + ".tmp-" + UUID.randomUUID().toString().substring(0, 6));
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (!tmp.renameTo(to)) {
            tmp.delete();
            throw new IOException("Could not copy to " + to.getName() + ".");
        }
        to.setLastModified(System.currentTimeMillis());
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.mkdirs() && !to.isDirectory()) throw new IOException("Could not create " + to.getName() + ".");
            File[] kids = from.listFiles();
            if (kids != null) {
                for (File k : kids) copyTree(k, new File(to, k.getName()));
            }
        } else {
            copyFile(from, to);
        }
    }

    private static void deleteTree(File f) {
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }

    private static boolean isLink(File f) {
        try {
            return !f.getCanonicalPath().equals(new File(f.getParentFile().getCanonicalFile(), f.getName()).getPath());
        } catch (IOException e) {
            return true;
        }
    }

    /** Moves into the trash folder: a quick rename, or copy and delete when that is not possible. */
    private File toTrash(File f, String id, String name) throws IOException {
        File dst = new File(new File(trash, id), name);
        if (!dst.getParentFile().mkdirs() && !dst.getParentFile().isDirectory()) throw new IOException("Could not create the Trash folder.");
        try {
            new File(trash, ".nomedia").createNewFile();
        } catch (IOException ignored) {
        }
        if (!f.renameTo(dst)) {
            copyTree(f, dst);
            deleteTree(f);
            if (f.exists()) throw new IOException("Could not remove " + f.getName() + ".");
        }
        return dst;
    }

    public synchronized Result write(String path, String text, boolean asCopy, String reason) {
        try {
            File f = resolve(path);
            if (f.isDirectory()) return Result.fail("That is a folder, not a file.");
            byte[] data = (text == null ? "" : text).getBytes(UTF8);
            if (data.length > MAX_WRITE) return Result.fail("That is too much text to save (limit 5 MB).");
            File parent = f.getParentFile();
            if (parent == null || !parent.isDirectory()) return Result.fail("The folder does not exist: " + (parent == null ? "" : parent.getPath()));
            Entry e;
            File target = f;
            if (f.exists()) {
                if (asCopy) {
                    e = begin("copy_write", reason);
                    target = new File(parent, uniqueName(parent, f.getName(), "copy"));
                    e.from = f.getPath();
                } else {
                    e = begin("replace", reason);
                    File b = new File(new File(trash, e.id), f.getName());
                    if (!b.getParentFile().mkdirs() && !b.getParentFile().isDirectory()) return Result.fail("Could not create the Trash folder.");
                    copyFile(f, b);
                    e.backup = b.getPath();
                }
            } else {
                e = begin("create", reason);
            }
            writeFile(target, data);
            e.path = target.getPath();
            e.postMtime = target.lastModified();
            e.size = data.length;
            record(e);
            String m = e.op.equals("replace") ? "Saved " + target.getName() + " (the old version is kept in the Trash)."
                    : e.op.equals("copy_write") ? "Saved as a copy: " + target.getName()
                    : "Created " + target.getName() + ".";
            return Result.ok(m, e, target.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    /** Saves a new binary file (for example a PDF). Never replaces anything: a taken name gets " (new)" added. */
    public synchronized Result createBinary(String path, byte[] data, String reason) {
        try {
            File f = resolve(path);
            if (f.isDirectory()) return Result.fail("That is a folder, not a file.");
            if (data.length > 100L * 1048576L) return Result.fail("That file is too big to save.");
            File parent = f.getParentFile();
            if (parent == null) return Result.fail("The folder does not exist.");
            if (!parent.isDirectory() && !parent.mkdirs()) return Result.fail("Could not create the folder: " + parent.getPath());
            File target = f.exists() ? new File(parent, uniqueName(parent, f.getName(), "new")) : f;
            Entry e = begin("create", reason);
            writeFile(target, data);
            e.path = target.getPath();
            e.postMtime = target.lastModified();
            e.size = data.length;
            record(e);
            return Result.ok("Created " + target.getName() + ".", e, target.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    public synchronized Result mkdir(String path, String reason) {
        try {
            File f = resolve(path);
            if (f.exists()) return Result.fail("Something with that name already exists.");
            File parent = f.getParentFile();
            if (parent == null || !parent.isDirectory()) return Result.fail("The folder does not exist: " + (parent == null ? "" : parent.getPath()));
            if (!f.mkdir()) return Result.fail("Could not create the folder.");
            Entry e = begin("mkdir", reason);
            e.path = f.getPath();
            e.postMtime = f.lastModified();
            record(e);
            return Result.ok("Created folder " + f.getName() + ".", e, f.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    public synchronized Result rename(String path, String newName, String reason) {
        try {
            File f = resolve(path);
            if (!f.exists()) return Result.fail("That does not exist.");
            if (f.equals(root.getCanonicalFile())) return Result.fail("The main storage folder cannot be renamed.");
            String n = newName == null ? "" : newName.trim();
            if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) return Result.fail("That name is not allowed.");
            File t = new File(f.getParentFile(), n);
            resolve(t.getPath());
            if (t.exists()) return Result.fail("Something named \"" + n + "\" already exists there.");
            if (!f.renameTo(t)) return Result.fail("Could not rename it.");
            Entry e = begin("rename", reason);
            e.from = f.getPath();
            e.path = t.getPath();
            record(e);
            return Result.ok("Renamed " + f.getName() + " to " + n + ".", e, t.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    public synchronized Result copy(String path, String destDir, String reason) {
        try {
            File f = resolve(path);
            File d = resolve(destDir);
            if (!f.exists()) return Result.fail("That does not exist.");
            if (!d.isDirectory()) return Result.fail("The destination is not a folder.");
            if (f.isDirectory() && (d.getPath().equals(f.getPath()) || d.getPath().startsWith(f.getPath() + File.separator))) {
                // copying a folder into itself is fine only into a different branch; here it would never end
                return Result.fail("A folder cannot be copied into itself.");
            }
            File t = new File(d, f.getName());
            if (t.exists()) t = new File(d, uniqueName(d, f.getName(), "copy"));
            copyTree(f, t);
            Entry e = begin("copy", reason);
            e.from = f.getPath();
            e.path = t.getPath();
            e.postMtime = t.lastModified();
            record(e);
            return Result.ok("Copied to " + t.getPath() + ".", e, t.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    /** Never deletes for good: the item goes to the Trash and can be restored. */
    public synchronized Result delete(String path, String reason) {
        try {
            File f = resolve(path);
            if (!f.exists()) return Result.fail("That does not exist.");
            if (f.equals(root.getCanonicalFile())) return Result.fail("The main storage folder cannot be deleted.");
            Entry e = begin("delete", reason);
            File dst = toTrash(f, e.id, f.getName());
            e.path = f.getPath();
            e.backup = dst.getPath();
            record(e);
            return Result.ok("Moved " + f.getName() + " to the Trash.", e, f.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    // ---------- undo ----------

    private Entry find(String id) {
        for (Entry e : history) if (e.id.equals(id)) return e;
        return null;
    }

    public synchronized Result undo(String id, boolean force) {
        Entry e = find(id);
        if (e == null) return Result.fail("That change is no longer in the history.");
        if (e.undone) return Result.fail("That change was already undone.");
        if (e.purged) return Result.fail("The saved copy was deleted from the Trash, so this cannot be undone.");
        try {
            String what;
            File p = new File(e.path);
            switch (e.op) {
                case "create":
                case "copy_write":
                case "copy": {
                    if (!p.exists()) {
                        what = "It was already gone.";
                        break;
                    }
                    resolve(p.getPath());
                    if (!p.isDirectory() && p.lastModified() != e.postMtime && !force) {
                        Result r = Result.fail("This file was changed after it was saved. Undoing will remove those changes too.");
                        r.code = "changed";
                        return r;
                    }
                    toTrash(p, e.id, "undone-" + p.getName());
                    what = "Removed " + p.getName() + " (kept in the Trash).";
                    break;
                }
                case "replace": {
                    File b = new File(e.backup);
                    if (!b.exists()) return Result.fail("The original copy is no longer in the Trash.");
                    resolve(p.getPath());
                    if (p.exists() && p.lastModified() != e.postMtime && !force) {
                        Result r = Result.fail("This file was changed after it was saved. Restoring the old version will overwrite those changes.");
                        r.code = "changed";
                        return r;
                    }
                    if (p.exists()) copyFile(b, p);
                    else {
                        if (p.getParentFile() == null || !p.getParentFile().isDirectory()) return Result.fail("The folder no longer exists.");
                        copyFile(b, p);
                    }
                    what = "Restored the old version of " + p.getName() + ".";
                    break;
                }
                case "mkdir": {
                    if (!p.exists()) {
                        what = "It was already gone.";
                        break;
                    }
                    String[] kids = p.list();
                    if (kids != null && kids.length > 0) return Result.fail("The folder is not empty now, so it was left alone.");
                    if (!p.delete()) return Result.fail("Could not remove the folder.");
                    what = "Removed the new folder " + p.getName() + ".";
                    break;
                }
                case "rename": {
                    File old = new File(e.from);
                    if (!p.exists()) return Result.fail("The renamed item is not there any more.");
                    if (old.exists()) return Result.fail("Something named \"" + old.getName() + "\" exists again, so it cannot be renamed back.");
                    if (!p.renameTo(old)) return Result.fail("Could not rename it back.");
                    what = "Renamed it back to " + old.getName() + ".";
                    break;
                }
                case "delete": {
                    File b = new File(e.backup);
                    if (!b.exists()) return Result.fail("It is no longer in the Trash.");
                    File dest = p;
                    if (dest.exists()) dest = new File(dest.getParentFile(), uniqueName(dest.getParentFile(), dest.getName(), "restored"));
                    File par = dest.getParentFile();
                    if (par != null && !par.isDirectory() && !par.mkdirs()) return Result.fail("The folder it was in no longer exists.");
                    if (!b.renameTo(dest)) {
                        copyTree(b, dest);
                        deleteTree(b);
                    }
                    deleteTree(new File(trash, e.id));
                    what = "Restored " + dest.getName() + (dest.equals(p) ? "." : " (the original name was taken).");
                    break;
                }
                default:
                    return Result.fail("Unknown change.");
            }
            e.undone = true;
            saveHistory();
            return Result.ok(what, e, p.getPath());
        } catch (IOException ex) {
            return Result.fail(msg(ex));
        }
    }

    /** Deletes the saved copy of a deleted or replaced item for good. */
    public synchronized Result purge(String id) {
        Entry e = find(id);
        if (e == null) return Result.fail("That item is no longer in the history.");
        if (e.purged) return Result.fail("Already deleted.");
        deleteTree(new File(trash, e.id));
        e.purged = true;
        saveHistory();
        return Result.ok("Deleted for good.", e, e.path);
    }

    public synchronized int purgeAllTrash() {
        int n = 0;
        for (Entry e : history) {
            if (e.op.equals("delete") && !e.undone && !e.purged) {
                deleteTree(new File(trash, e.id));
                e.purged = true;
                n++;
            }
        }
        saveHistory();
        return n;
    }

    // ---------- history ----------

    /** Newest first. */
    public synchronized List<Entry> history() {
        List<Entry> out = new ArrayList<>(history);
        Collections.reverse(out);
        return out;
    }

    /** Deleted items that can still be restored, newest first. */
    public synchronized List<Entry> trashEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : history()) {
            if (e.op.equals("delete") && !e.undone && !e.purged && new File(e.backup).exists()) out.add(e);
        }
        return out;
    }

    private void record(Entry e) {
        history.add(e);
        while (history.size() > MAX_HISTORY) {
            Entry old = history.get(0);
            // a change that is dropped from the list can no longer be restored, so free its saved copy
            if (!old.undone && !old.purged && old.backup != null && !old.backup.isEmpty()) deleteTree(new File(trash, old.id));
            history.remove(0);
        }
        saveHistory();
    }

    private void saveHistory() {
        try {
            File tmp = new File(historyFile.getPath() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                for (Entry e : history) out.write((e.toLine() + "\n").getBytes(UTF8));
            } finally {
                out.close();
            }
            if (historyFile.exists()) historyFile.delete();
            tmp.renameTo(historyFile);
        } catch (IOException ignored) {
        }
    }

    private void loadHistory() {
        if (!historyFile.exists()) return;
        try {
            InputStream in = new FileInputStream(historyFile);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            } finally {
                in.close();
            }
            for (String line : new String(bo.toByteArray(), UTF8).split("\n")) {
                if (line.trim().isEmpty()) continue;
                Entry e = Entry.fromLine(line);
                if (e != null) history.add(e);
            }
        } catch (IOException ignored) {
        }
    }

    private static String msg(IOException e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Something went wrong." : m;
    }
}
