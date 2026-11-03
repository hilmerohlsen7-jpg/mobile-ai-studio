package com.mobileaistudio.app.dl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;

/** Runs one download from start to finish (or until it is paused, re-queued or cancelled). */
final class Job implements Runnable {
    enum Outcome { DONE, STOPPED, ERROR }

    static final class Stopped extends RuntimeException {
        Stopped() {
            super("stopped", null, false, false);
        }
    }

    private static final int MAX_TRIES = 4;
    private static final long MIN_FREE_BYTES = 20L * 1048576L;

    private final DownloadItem it;
    private final DownloadManager mgr;
    private volatile boolean aborted;

    Job(DownloadItem it, DownloadManager mgr) {
        this.it = it;
        this.mgr = mgr;
    }

    void checkStop() {
        if (aborted || it.stop != DownloadItem.Stop.NONE) throw new Stopped();
    }

    @Override
    public void run() {
        Outcome out;
        try {
            execute();
            out = Outcome.DONE;
        } catch (Stopped s) {
            out = it.stop == DownloadItem.Stop.NONE ? Outcome.ERROR : Outcome.STOPPED;
            if (out == Outcome.ERROR) it.message = "The download stopped unexpectedly.";
        } catch (Throwable t) {
            it.message = friendly(t);
            out = Outcome.ERROR;
        }
        mgr.jobFinished(it, out);
    }

    static String friendly(Throwable t) {
        if (t instanceof UnknownHostException) return "Cannot find the server. Check the address and your internet connection.";
        if (t instanceof SocketTimeoutException) return "The connection timed out. Try again.";
        if (t instanceof ConnectException) return "Could not connect to the server.";
        if (t instanceof SSLException) return "The secure connection failed.";
        if (t instanceof java.net.MalformedURLException) return "That address is not valid.";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return t.getClass().getSimpleName();
        return m;
    }

    private void execute() throws IOException {
        if (!it.dir.exists() && !it.dir.mkdirs()) throw new IOException("Could not create the download folder.");
        boolean ftp = it.url.regionMatches(true, 0, "ftp://", 0, 6);

        long oldTotal = it.total;
        if (ftp) FtpFetch.probe(it);
        else HttpFetch.probe(it);
        checkStop();
        if (oldTotal > 0 && it.total != oldTotal) {
            // the file on the server changed: what we saved no longer fits
            synchronized (it) {
                it.chunkDone = null;
            }
            it.done.set(0);
            File old = it.partFile();
            if (old != null) old.delete();
        }

        if (it.name == null) {
            it.name = mgr.claimName(it, Util.sanitizeName(it.suggestedName));
            mgr.persist(it);
        }
        File part = it.partFile();
        File dest = it.file();

        long remaining = it.total > 0 ? it.total - it.done.get() : 0;
        if (it.total > 0 && it.dir.getUsableSpace() < remaining + MIN_FREE_BYTES) {
            throw new IOException("Not enough free storage. About " + Util.mb(remaining + MIN_FREE_BYTES - it.dir.getUsableSpace()) + " more is needed.");
        }

        if (ftp) {
            long off = part.exists() ? part.length() : 0;
            if (it.total > 0 && off > it.total) off = 0;
            if (off == 0 && part.exists()) part.delete();
            it.done.set(off);
            FtpFetch.download(it, part, off, this);
        } else if (it.rangeOk && it.total > 0) {
            chunked(part);
        } else {
            plain(part);
        }
        checkStop();

        if (it.total > 0 && part.length() != it.total) {
            throw new IOException("The file is incomplete (" + part.length() + " of " + it.total + " bytes). Try again.");
        }
        String bad = mgr.verify(it, part);
        if (bad != null) {
            part.delete();
            it.done.set(0);
            synchronized (it) {
                it.chunkDone = null;
            }
            throw new IOException(bad);
        }
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) throw new IOException("Could not save the finished file.");
        it.done.set(dest.length());
        it.total = dest.length();
    }

    /** Server without range support, or unknown size: one connection from the start, no resume. */
    private void plain(File part) throws IOException {
        it.done.set(0);
        HttpFetch.Opened op = HttpFetch.open(it, -1, -1);
        FileOutputStream fo = null;
        try {
            InputStream in = op.conn.getInputStream();
            fo = new FileOutputStream(part, false);
            byte[] buf = new byte[32768];
            while (true) {
                checkStop();
                int n = in.read(buf);
                if (n < 0) break;
                fo.write(buf, 0, n);
                it.done.addAndGet(n);
            }
        } finally {
            if (fo != null) fo.close();
            op.conn.disconnect();
        }
    }

    // ---------- chunked download with several connections ----------

    private final Object lock = new Object();
    private boolean[] inflight;
    private int cursor;

    private int takeChunk() {
        synchronized (lock) {
            int n = it.chunkDone.length;
            for (int i = 0; i < n; i++) {
                int c = (cursor + i) % n;
                if (!it.chunkDone[c] && !inflight[c]) {
                    inflight[c] = true;
                    cursor = c + 1;
                    return c;
                }
            }
            return -1;
        }
    }

    private static int pickChunkSize(long total) {
        if (total <= 8L * 1048576L) return (int) Math.max(262144L, total / 8 + 1);
        return 4 * 1048576;
    }

    private void chunked(File part) throws IOException {
        long total = it.total;
        int cs = it.chunkSize > 0 ? it.chunkSize : pickChunkSize(total);
        int n = (int) ((total + cs - 1) / cs);
        synchronized (it) {
            if (it.chunkDone == null || it.chunkDone.length != n || it.chunkSize != cs || !part.exists()) {
                it.chunkSize = cs;
                it.chunkDone = new boolean[n];
                it.done.set(0);
            }
        }
        inflight = new boolean[n];

        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        try {
            if (raf.length() != total) raf.setLength(total);
            final FileChannel ch = raf.getChannel();

            long doneBytes = 0;
            int left = 0;
            synchronized (it) {
                for (int i = 0; i < n; i++) {
                    if (it.chunkDone[i]) doneBytes += chunkLen(i, cs, total);
                    else left++;
                }
            }
            it.done.set(doneBytes);

            int workers = Math.max(1, Math.min(it.threads, left));
            if (left > 0) {
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                final CountDownLatch latch = new CountDownLatch(workers);
                ExecutorService ws = Executors.newFixedThreadPool(workers);
                final int csFinal = cs;
                for (int w = 0; w < workers; w++) {
                    ws.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                while (true) {
                                    checkStop();
                                    int c = takeChunk();
                                    if (c < 0) break;
                                    fetchChunk(c, csFinal, ch);
                                }
                            } catch (Stopped ignored) {
                                // paused, re-queued or cancelled: the main thread sorts it out
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                                aborted = true;
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    aborted = true;
                    Thread.currentThread().interrupt();
                } finally {
                    ws.shutdownNow();
                }
                Throwable f = failure.get();
                if (f != null) {
                    if (f instanceof IOException) throw (IOException) f;
                    throw new IOException(friendly(f));
                }
                if (it.stop != DownloadItem.Stop.NONE) throw new Stopped();
            }
            ch.force(false);
        } finally {
            raf.close();
        }
        synchronized (it) {
            for (boolean b : it.chunkDone) {
                if (!b) throw new IOException("Some parts of the file are missing. Try again.");
            }
        }
    }

    private static long chunkLen(int c, int cs, long total) {
        long start = (long) c * cs;
        return Math.min(total, start + cs) - start;
    }

    private void fetchChunk(int c, int cs, FileChannel ch) throws IOException {
        long start = (long) c * cs;
        long end = Math.min(it.total, start + cs) - 1;
        long pos = start;      // next byte to fetch; bytes before it are already on disk
        long got = 0;          // bytes counted into it.done for this chunk (rolled back if we stop)
        IOException last = null;
        int failures = 0;
        while (failures < MAX_TRIES) {
            try {
                checkStop();
            } catch (Stopped s) {
                it.done.addAndGet(-got);
                release(c);
                throw s;
            }
            HttpURLConnection conn = null;
            boolean clean = false;
            long before = pos;
            try {
                HttpFetch.Opened op = HttpFetch.open(it, pos, end);
                conn = op.conn;
                if (!op.finalUrl.equals(it.url)) it.resolvedUrl = op.finalUrl;
                if (op.code != 206) throw new HttpFetch.HttpError(416, "The server stopped supporting partial downloads.");
                String cr = conn.getHeaderField("Content-Range");
                if (cr == null || !cr.contains("bytes " + pos + "-")) {
                    throw new IOException("The server sent the wrong part of the file.");
                }
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[32768];
                while (pos <= end) {
                    checkStop();
                    int n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1));
                    if (n < 0) break;
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
                    long p = pos;
                    while (bb.hasRemaining()) p += ch.write(bb, p);
                    pos += n;
                    got += n;
                    it.done.addAndGet(n);
                }
                if (pos <= end) throw new IOException("The connection closed too early.");
                clean = true;
                synchronized (it) {
                    it.chunkDone[c] = true;
                }
                release(c);
                in.close(); // fully read: lets the connection be reused
                return;
            } catch (Stopped s) {
                it.done.addAndGet(-got);
                release(c);
                throw s;
            } catch (HttpFetch.HttpError e) {
                last = e;
                if (e.fatal()) {
                    it.done.addAndGet(-got);
                    release(c);
                    throw e;
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (!clean && conn != null) conn.disconnect();
            }
            if (pos > before) failures = 0; // it made progress before breaking, so keep trying
            else failures++;
            try {
                Thread.sleep(400L << Math.min(failures, 4));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                it.done.addAndGet(-got);
                release(c);
                throw new Stopped();
            }
        }
        it.done.addAndGet(-got);
        release(c);
        throw last != null ? last : new IOException("Download failed.");
    }

    private void release(int c) {
        synchronized (lock) {
            inflight[c] = false;
        }
    }
}
