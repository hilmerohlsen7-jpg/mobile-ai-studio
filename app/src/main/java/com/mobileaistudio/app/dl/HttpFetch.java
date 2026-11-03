package com.mobileaistudio.app.dl;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Base64;

/** HTTP and HTTPS requests with manual redirect handling and byte-range support. */
final class HttpFetch {
    private HttpFetch() {
    }

    static final String USER_AGENT = "MobileAIStudio/0.2";

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }

        /** True when trying again cannot help. */
        boolean fatal() {
            return code >= 400 && code < 500 && code != 408 && code != 429;
        }
    }

    static final class Opened {
        final HttpURLConnection conn;
        final String finalUrl;
        final int code;

        Opened(HttpURLConnection conn, String finalUrl, int code) {
            this.conn = conn;
            this.finalUrl = finalUrl;
            this.code = code;
        }
    }

    private static String messageFor(int code) {
        if (code == 401 || code == 403) return "The server refused access (a login or permission is needed).";
        if (code == 404 || code == 410) return "File not found on the server (" + code + ").";
        if (code == 416) return "The server could not give the requested part of the file.";
        if (code == 429) return "The server says too many requests. Try again later.";
        if (code >= 500) return "The server had a problem (" + code + "). Try again later.";
        return "The server answered " + code + ".";
    }

    /**
     * Opens the file, following redirects. start/end ask for a byte range (end may be -1 for "to the
     * end"); start -1 asks for the whole file. A saved redirect target that has expired is dropped
     * and the original address is tried again once.
     */
    static Opened open(DownloadItem it, long start, long end) throws IOException {
        String first = it.resolvedUrl != null ? it.resolvedUrl : it.url;
        try {
            return follow(it.url, first, start, end);
        } catch (HttpError e) {
            if (it.resolvedUrl != null && (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 410)) {
                it.resolvedUrl = null;
                return follow(it.url, it.url, start, end);
            }
            throw e;
        }
    }

    private static Opened follow(String originalUrl, String startUrl, long start, long end) throws IOException {
        String cur = startUrl;
        String originalHost = new URL(originalUrl).getHost();
        for (int hop = 0; hop <= 8; hop++) {
            URL url = new URL(cur);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");
            String userInfo = url.getUserInfo();
            if (userInfo != null && url.getHost().equalsIgnoreCase(originalHost)) {
                String token = Base64.getEncoder().encodeToString(Util.dec(userInfo).getBytes(Charset.forName("UTF-8")));
                c.setRequestProperty("Authorization", "Basic " + token);
            }
            if (start >= 0) c.setRequestProperty("Range", "bytes=" + start + "-" + (end >= 0 ? Long.toString(end) : ""));
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new HttpError(code, "The server sent a redirect without a new address.");
                cur = new URL(url, loc).toString();
                String low = cur.toLowerCase();
                if (!low.startsWith("http://") && !low.startsWith("https://")) {
                    throw new HttpError(code, "The server redirected to an unsupported address.");
                }
                continue;
            }
            if (code == 200 || code == 206) return new Opened(c, cur, code);
            c.disconnect();
            throw new HttpError(code, messageFor(code));
        }
        throw new IOException("Too many redirects.");
    }

    /** Learns the size, whether the server supports ranges, and a file name. */
    static void probe(DownloadItem it) throws IOException {
        Opened op;
        try {
            op = open(it, 0, 0);
        } catch (HttpError e) {
            if (e.code == 416) throw new IOException("The file on the server is empty.");
            throw e;
        }
        try {
            it.resolvedUrl = op.finalUrl.equals(it.url) ? null : op.finalUrl;
            String n = Util.filenameFromContentDisposition(op.conn.getHeaderField("Content-Disposition"));
            it.suggestedName = n != null ? n : Util.lastSegment(it.url);
            if (op.code == 206) {
                long t = -1;
                String cr = op.conn.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        String ts = cr.substring(slash + 1).trim();
                        try {
                            t = Long.parseLong(ts);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                it.total = t;
                it.rangeOk = t > 0;
            } else {
                it.total = op.conn.getContentLengthLong();
                it.rangeOk = false;
            }
        } finally {
            op.conn.disconnect();
        }
    }
}
