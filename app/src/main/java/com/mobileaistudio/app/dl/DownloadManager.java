package com.mobileaistudio.app.dl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * A queue of downloads. At most "max simultaneous" run at once; each one uses up to its own number
 * of connections (threads). Progress is saved so downloads can be paused and continued later.
 */
public final class DownloadManager {
    public interface Listener {
        /** Something changed (progress, state, list). Called from engine threads. */
        void onChanged();

        /** True when anything is downloading or waiting to start; false when everything is idle. */
        void onActive(boolean active);
    }

    public interface Check {
        /** Returns an error message if the finished file is not acceptable, otherwise null. */
        String verify(DownloadItem item, File file);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File stateDir;
    private final Listener listener;
    private final Check check;
    private final List<DownloadItem> items = new ArrayList<>();
    private final ExecutorService pool;
    private final ScheduledExecutorService ticker;
    private volatile int max = 2;
    private volatile int defaultThreads = 4;
    private boolean lastActive;
    private int ticks;

    public DownloadManager(File stateDir, Listener listener, Check check) {
        this.stateDir = stateDir;
        this.listener = listener;
        this.check = check;
        ThreadFactory daemon = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "download");
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = Executors.newCachedThreadPool(daemon);
        this.ticker = Executors.newSingleThreadScheduledExecutor(daemon);
        stateDir.mkdirs();
        load();
        ticker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable ignored) {
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // ---------- settings ----------

    public int getMax() {
        return max;
    }

    public int getDefaultThreads() {
        return defaultThreads;
    }

    public void setMax(int n) {
        max = Math.max(1, Math.min(8, n));
        schedule();
    }

    public void setDefaultThreads(int n) {
        defaultThreads = Math.max(1, Math.min(16, n));
    }

    // ---------- the list ----------

    public synchronized List<DownloadItem> list() {
        return new ArrayList<>(items);
    }

    private DownloadItem find(String id) {
        for (DownloadItem it : items) {
            if (it.id.equals(id)) return it;
        }
        return null;
    }

    /**
     * Adds a download and queues it. name may be null (it is then taken from the server).
     * Throws IllegalArgumentException with a message the person can read.
     */
    public synchronized String add(String url, String kind, String tag, File dir, String name, int threads) {
        url = url == null ? "" : url.trim();
        String low = url.toLowerCase();
        if (!(low.startsWith("http://") || low.startsWith("https://") || low.startsWith("ftp://"))) {
            throw new IllegalArgumentException("Only http, https and ftp addresses are supported here.");
        }
        if (url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0 || url.length() > 4000) {
            throw new IllegalArgumentException("That address is not valid.");
        }
        for (DownloadItem o : items) {
            if (o.url.equals(url) && o.state != DownloadItem.State.DONE) {
                throw new IllegalArgumentException("This download is already in the list.");
            }
        }
        int t = threads > 0 ? Math.min(16, threads) : defaultThreads;
        DownloadItem it = new DownloadItem(UUID.randomUUID().toString().substring(0, 8), url, kind == null ? "file" : kind, tag, dir, t);
        if (name != null && !name.isEmpty()) {
            it.name = claimNameLocked(it, Util.sanitizeName(name), true);
        }
        items.add(it);
        persist(it);
        schedule();
        changed();
        return it.id;
    }

    public synchronized void pause(String id) {
        DownloadItem it = find(id);
        if (it == null) return;
        if (it.state == DownloadItem.State.DOWNLOADING) {
            it.stop = DownloadItem.Stop.PAUSE;
        } else if (it.state == DownloadItem.State.QUEUED) {
            it.state = DownloadItem.State.PAUSED;
            persist(it);
        }
        changed();
        updateActive();
    }

    public synchronized void resume(String id) {
        DownloadItem it = find(id);
        if (it == null) return;
        if (it.state == DownloadItem.State.PAUSED || it.state == DownloadItem.State.ERROR) {
            it.state = DownloadItem.State.QUEUED;
            it.message = "";
            persist(it);
            schedule();
        } else if (it.state == DownloadItem.State.DOWNLOADING && it.stop == DownloadItem.Stop.PAUSE) {
            it.stop = DownloadItem.Stop.REQUEUE; // changed our mind before it stopped
        }
        changed();
    }

    public synchronized void pauseAll() {
        for (DownloadItem it : items) {
            if (it.state == DownloadItem.State.DOWNLOADING) it.stop = DownloadItem.Stop.PAUSE;
            else if (it.state == DownloadItem.State.QUEUED) it.state = DownloadItem.State.PAUSED;
        }
        changed();
    }

    /** Takes the download out of the list. A running download is cancelled and its partial file deleted. */
    public synchronized void remove(String id, boolean deleteFile) {
        DownloadItem it = find(id);
        if (it == null) return;
        if (it.state == DownloadItem.State.DOWNLOADING) {
            it.deleteFileOnCancel = deleteFile;
            it.stop = DownloadItem.Stop.CANCEL;
            changed();
            return;
        }
        dropFiles(it, deleteFile);
        items.remove(it);
        changed();
        updateActive();
    }

    public synchronized void setThreads(String id, int n) {
        DownloadItem it = find(id);
        if (it == null) return;
        it.threads = Math.max(1, Math.min(16, n));
        if (it.state == DownloadItem.State.DOWNLOADING && it.stop == DownloadItem.Stop.NONE) {
            it.stop = DownloadItem.Stop.REQUEUE; // restarts at once with the new number of connections
        }
        persist(it);
        changed();
    }

    /** Stops everything and saves. Call when the app is really going away. */
    public void shutdown() {
        pauseAll();
        long until = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < until) {
            boolean running = false;
            synchronized (this) {
                for (DownloadItem it : items) running |= it.state == DownloadItem.State.DOWNLOADING;
            }
            if (!running) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
        synchronized (this) {
            for (DownloadItem it : items) persist(it);
        }
        ticker.shutdownNow();
        pool.shutdownNow();
    }

    // ---------- called by Job ----------

    synchronized String claimName(DownloadItem it, String desired) {
        return claimNameLocked(it, desired, false);
    }

    private String claimNameLocked(DownloadItem it, String desired, boolean exactIfFree) {
        Set<String> taken = new HashSet<>();
        for (DownloadItem o : items) {
            if (o != it && o.name != null && o.dir.equals(it.dir)) taken.add(o.name);
        }
        if (it.kind.equals("model") && exactIfFree) {
            // models with a fixed name replace an old copy instead of getting "(1)" added
            return taken.contains(desired) ? Util.uniqueName(it.dir, desired, taken) : desired;
        }
        return Util.uniqueName(it.dir, desired, taken);
    }

    String verify(DownloadItem it, File file) {
        return check == null ? null : check.verify(it, file);
    }

    void persist(DownloadItem it) {
        try {
            File f = new File(stateDir, it.id + ".dl");
            File tmp = new File(stateDir, it.id + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            try {
                w.write(it.toText());
            } finally {
                w.close();
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Exception ignored) {
        }
    }

    synchronized void jobFinished(DownloadItem it, Job.Outcome outcome) {
        DownloadItem.Stop why = it.stop;
        it.stop = DownloadItem.Stop.NONE;
        it.speed = 0;
        boolean removed = false;
        if (outcome == Job.Outcome.DONE) {
            it.state = DownloadItem.State.DONE;
            it.message = "";
        } else if (outcome == Job.Outcome.STOPPED) {
            switch (why) {
                case PAUSE:
                    it.state = DownloadItem.State.PAUSED;
                    it.message = "";
                    break;
                case REQUEUE:
                    it.state = DownloadItem.State.QUEUED;
                    break;
                case CANCEL:
                    dropFiles(it, it.deleteFileOnCancel);
                    items.remove(it);
                    removed = true;
                    break;
                default:
                    it.state = DownloadItem.State.PAUSED;
            }
        } else {
            if (why == DownloadItem.Stop.CANCEL) {
                dropFiles(it, it.deleteFileOnCancel);
                items.remove(it);
                removed = true;
            } else {
                it.state = DownloadItem.State.ERROR;
            }
        }
        if (!removed) persist(it);
        schedule();
        changed();
        updateActive();
    }

    // ---------- scheduling ----------

    private int countDownloading() {
        int n = 0;
        for (DownloadItem it : items) {
            if (it.state == DownloadItem.State.DOWNLOADING && it.stop == DownloadItem.Stop.NONE) n++;
        }
        return n;
    }

    private synchronized void schedule() {
        int running = countDownloading();
        // Too many running (the limit was lowered): send the newest ones back to the queue.
        while (running > max) {
            DownloadItem newest = null;
            for (DownloadItem it : items) {
                if (it.state == DownloadItem.State.DOWNLOADING && it.stop == DownloadItem.Stop.NONE
                        && (newest == null || it.startedAt > newest.startedAt)) newest = it;
            }
            if (newest == null) break;
            newest.stop = DownloadItem.Stop.REQUEUE;
            running--;
        }
        for (DownloadItem it : items) {
            if (running >= max) break;
            if (it.state == DownloadItem.State.QUEUED) {
                it.state = DownloadItem.State.DOWNLOADING;
                it.message = "";
                it.stop = DownloadItem.Stop.NONE;
                it.startedAt = System.nanoTime();
                it.lastDone = it.done.get();
                running++;
                pool.execute(new Job(it, this));
            }
        }
        updateActive();
        changed();
    }

    private void changed() {
        if (listener != null) listener.onChanged();
    }

    private void updateActive() {
        boolean active = false;
        for (DownloadItem it : items) {
            if (it.state == DownloadItem.State.DOWNLOADING || it.state == DownloadItem.State.QUEUED) active = true;
        }
        if (active != lastActive) {
            lastActive = active;
            if (listener != null) listener.onActive(active);
        }
    }

    private synchronized void tick() {
        ticks++;
        boolean any = false;
        for (DownloadItem it : items) {
            if (it.state == DownloadItem.State.DOWNLOADING) {
                any = true;
                long now = it.done.get();
                long delta = Math.max(0, now - it.lastDone);
                it.lastDone = now;
                // smooth the speed so the number does not jump around
                it.speed = it.speed == 0 ? delta : (it.speed * 6 + delta * 4) / 10;
                if (ticks % 3 == 0) persist(it);
            }
        }
        if (any) changed();
    }

    // ---------- saved state ----------

    private void dropFiles(DownloadItem it, boolean deleteFinal) {
        File part = it.partFile();
        if (part != null) part.delete();
        File dest = it.file();
        if (deleteFinal && dest != null) dest.delete();
        new File(stateDir, it.id + ".dl").delete();
    }

    private void load() {
        File[] files = stateDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().endsWith(".dl")) continue;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                } finally {
                    in.close();
                }
                DownloadItem it = DownloadItem.fromText(new String(bo.toByteArray(), UTF8));
                if (it == null) continue;
                if (it.state == DownloadItem.State.DOWNLOADING || it.state == DownloadItem.State.QUEUED) {
                    it.state = DownloadItem.State.PAUSED;
                    it.message = "Paused when the app closed.";
                }
                if (it.state == DownloadItem.State.DONE && (it.file() == null || !it.file().exists())) {
                    f.delete();
                    continue;
                }
                items.add(it);
            } catch (Exception ignored) {
            }
        }
        java.util.Collections.sort(items, new java.util.Comparator<DownloadItem>() {
            @Override
            public int compare(DownloadItem a, DownloadItem b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
    }
}
