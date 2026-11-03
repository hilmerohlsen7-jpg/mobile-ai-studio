package com.mobileaistudio.app.dl;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** One entry in the download list. Fields are read by the UI while the engine updates them. */
public final class DownloadItem {
    public enum State { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }

    enum Stop { NONE, PAUSE, REQUEUE, CANCEL }

    public final String id;
    public final String url;
    /** "model" (saved in the models folder and checked) or "file" (any other file). */
    public final String kind;
    /** Free text label for the app, for example the catalog id of a model. */
    public final String tag;
    public final File dir;

    /** Null until the file name is known. */
    public volatile String name;
    public volatile State state = State.QUEUED;
    public volatile String message = "";
    /** Total size in bytes, or -1 when the server does not say. */
    public volatile long total = -1;
    public final AtomicLong done = new AtomicLong();
    public volatile int threads;
    /** Bytes per second, updated about once a second while downloading. */
    public volatile long speed;
    public volatile long createdAt;

    // engine-only state
    volatile boolean rangeOk;
    volatile String suggestedName;
    volatile String resolvedUrl;
    volatile Stop stop = Stop.NONE;
    volatile boolean deleteFileOnCancel;
    volatile long startedAt;
    long lastDone;
    int chunkSize;
    boolean[] chunkDone; // guarded by synchronized(this)

    DownloadItem(String id, String url, String kind, String tag, File dir, int threads) {
        this.id = id;
        this.url = url;
        this.kind = kind;
        this.tag = tag == null ? "" : tag;
        this.dir = dir;
        this.threads = threads;
        this.createdAt = System.currentTimeMillis();
    }

    /** The address with any password removed, safe to show. */
    public String safeUrl() {
        return Util.hideCreds(url);
    }

    public String displayName() {
        String n = name;
        return n != null ? n : Util.lastSegment(url);
    }

    /** The finished file, or null if the name is not known yet. */
    public File file() {
        String n = name;
        return n == null ? null : new File(dir, n);
    }

    public File partFile() {
        String n = name;
        return n == null ? null : new File(dir, n + ".part");
    }

    synchronized String toText() {
        StringBuilder b = new StringBuilder();
        line(b, "v", "1");
        line(b, "id", id);
        line(b, "url", url);
        line(b, "kind", kind);
        line(b, "tag", tag);
        line(b, "dir", dir.getPath());
        line(b, "name", name == null ? "" : name);
        line(b, "state", state.name());
        line(b, "message", message == null ? "" : message);
        line(b, "total", Long.toString(total));
        line(b, "threads", Integer.toString(threads));
        line(b, "created", Long.toString(createdAt));
        line(b, "chunk", Integer.toString(chunkSize));
        StringBuilder bits = new StringBuilder();
        if (chunkDone != null) {
            for (boolean c : chunkDone) bits.append(c ? '1' : '0');
        }
        line(b, "chunks", bits.toString());
        return b.toString();
    }

    private static void line(StringBuilder b, String k, String v) {
        b.append(k).append('=').append(Util.enc(v)).append('\n');
    }

    static DownloadItem fromText(String text) {
        Map<String, String> m = new HashMap<>();
        for (String ln : text.split("\n")) {
            int eq = ln.indexOf('=');
            if (eq > 0) m.put(ln.substring(0, eq), Util.dec(ln.substring(eq + 1)));
        }
        String id = m.get("id");
        String url = m.get("url");
        String dir = m.get("dir");
        if (id == null || url == null || dir == null) return null;
        int threads = 4;
        try {
            threads = Integer.parseInt(m.get("threads"));
        } catch (Exception ignored) {
        }
        DownloadItem it = new DownloadItem(id, url, m.get("kind") == null ? "file" : m.get("kind"), m.get("tag"), new File(dir), threads);
        String name = m.get("name");
        it.name = (name == null || name.isEmpty()) ? null : name;
        try {
            it.state = State.valueOf(m.get("state"));
        } catch (Exception ignored) {
            it.state = State.PAUSED;
        }
        it.message = m.get("message") == null ? "" : m.get("message");
        try {
            it.total = Long.parseLong(m.get("total"));
        } catch (Exception ignored) {
        }
        try {
            it.createdAt = Long.parseLong(m.get("created"));
        } catch (Exception ignored) {
        }
        try {
            it.chunkSize = Integer.parseInt(m.get("chunk"));
        } catch (Exception ignored) {
        }
        String bits = m.get("chunks");
        if (bits != null && !bits.isEmpty() && it.chunkSize > 0) {
            it.chunkDone = new boolean[bits.length()];
            long sum = 0;
            for (int i = 0; i < bits.length(); i++) {
                it.chunkDone[i] = bits.charAt(i) == '1';
                if (it.chunkDone[i] && it.total > 0) {
                    long start = (long) i * it.chunkSize;
                    sum += Math.min(it.total, start + it.chunkSize) - start;
                }
            }
            it.done.set(sum);
        }
        it.lastDone = it.done.get();
        return it;
    }
}
