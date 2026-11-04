package com.mobileaistudio.app.dl;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Set;

public final class Util {
    private Util() {
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Decodes %XX in a URL path without turning '+' into a space. */
    static String decPath(String s) {
        return dec(s.replace("+", "%2B"));
    }

    /** A safe file name: no folders, no odd characters, not hidden, not too long. */
    static String sanitizeName(String raw) {
        if (raw == null) return "download";
        String s = raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || "<>:\"|?*".indexOf(c) >= 0) b.append('_');
            else b.append(c);
        }
        s = b.toString().trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && s.length() - dot <= 12) ? s.substring(dot) : "";
            s = s.substring(0, 120 - ext.length()) + ext;
        }
        return s.isEmpty() ? "download" : s;
    }

    /** name, or "name (1).ext" etc. when the file (or another running download) already uses it. */
    static String uniqueName(File dir, String name, Set<String> taken) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String cand = name;
        int n = 1;
        while (new File(dir, cand).exists() || new File(dir, cand + ".part").exists() || taken.contains(cand)) {
            cand = base + " (" + n + ")" + ext;
            n++;
        }
        return cand;
    }

    private static String stripQuotes(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Reads the file name from a Content-Disposition header, or returns null. */
    static String filenameFromContentDisposition(String cd) {
        if (cd == null) return null;
        String lower = cd.toLowerCase(Locale.ROOT);
        int i = lower.indexOf("filename*=");
        if (i >= 0) {
            String v = cd.substring(i + 10);
            int semi = v.indexOf(';');
            if (semi >= 0) v = v.substring(0, semi);
            v = v.trim();
            int q = v.indexOf("''");
            if (q >= 0) v = v.substring(q + 2);
            v = stripQuotes(v);
            if (!v.isEmpty()) return decPath(v);
        }
        i = lower.indexOf("filename=");
        if (i >= 0) {
            String v = cd.substring(i + 9);
            int semi = v.indexOf(';');
            if (semi >= 0 && !v.trim().startsWith("\"")) v = v.substring(0, semi);
            v = stripQuotes(v);
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** The last part of a URL's path, decoded. */
    static String lastSegment(String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int firstSlash = p.indexOf('/', scheme + 3);
            p = firstSlash < 0 ? "" : p.substring(firstSlash);
        }
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        String seg = slash >= 0 ? p.substring(slash + 1) : p;
        return seg.isEmpty() ? "download" : decPath(seg);
    }

    /** Removes "user:password@" from a URL so it is safe to show. */
    static String hideCreds(String url) {
        return url.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@", "$1");
    }

    static String mb(long bytes) {
        return (bytes / 1048576L) + " MB";
    }
}
