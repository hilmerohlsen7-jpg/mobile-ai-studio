package com.mobileaistudio.app.fs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Writes an ISO 9660 disk image (with Joliet long names) from files and folders. */
public final class IsoWriter {
    private static final int SS = 2048;

    private static final class Node {
        String name, pname, jname;
        File file;
        boolean dir;
        final List<Node> kids = new ArrayList<>();
        Node parent;
        long size;
        long dataExtent;
        int pExtent, jExtent, pDirSize, jDirSize, pIndex, jIndex;
    }

    public static void write(List<File> items, File out, String label) throws IOException {
        Node root = new Node();
        root.dir = true; root.name = "";
        for (File f : items) add(root, f);
        names(root);
        String vol = label == null || label.isEmpty() ? "DISC" : label;

        List<Node> pOrder = bfs(root, false), jOrder = bfs(root, true);
        int pPt = ptSize(pOrder, false), jPt = ptSize(jOrder, true);
        int pPtSec = Math.max(1, (pPt + SS - 1) / SS), jPtSec = Math.max(1, (jPt + SS - 1) / SS);
        int cur = 19;
        int pL = cur; cur += pPtSec; int pM = cur; cur += pPtSec;
        int jL = cur; cur += jPtSec; int jM = cur; cur += jPtSec;
        for (Node d : pOrder) { d.pDirSize = dirSize(d, false); d.pExtent = cur; cur += d.pDirSize / SS; }
        for (Node d : jOrder) { d.jDirSize = dirSize(d, true); d.jExtent = cur; cur += d.jDirSize / SS; }
        List<Node> files = new ArrayList<>();
        collectFiles(root, files);
        for (Node f : files) {
            if (f.size > 0xFFFFFFFFL) throw new IOException(f.name + " is bigger than 4 GB, which a disk image cannot hold.");
            f.dataExtent = cur; cur += (int) ((f.size + SS - 1) / SS);
        }
        int total = cur;
        long now = System.currentTimeMillis();

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            os.write(new byte[16 * SS]);
            os.write(volDesc(false, vol, total, pPt, pL, pM, root, now));
            os.write(volDesc(true, vol, total, jPt, jL, jM, root, now));
            byte[] term = new byte[SS]; term[0] = (byte) 255; System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, term, 1, 5); term[6] = 1;
            os.write(term);
            os.write(padTo(pathTable(pOrder, false, true), pPtSec)); os.write(padTo(pathTable(pOrder, false, false), pPtSec));
            os.write(padTo(pathTable(jOrder, true, true), jPtSec)); os.write(padTo(pathTable(jOrder, true, false), jPtSec));
            for (Node d : pOrder) os.write(dirBytes(d, false, now));
            for (Node d : jOrder) os.write(dirBytes(d, true, now));
            byte[] buf = new byte[1 << 16];
            for (Node f : files) {
                long left = f.size;
                try (InputStream in = new FileInputStream(f.file)) {
                    while (left > 0) { int n = in.read(buf, 0, (int) Math.min(buf.length, left)); if (n < 0) break; os.write(buf, 0, n); left -= n; }
                }
                long written = f.size - left;
                long pad = ((f.size + SS - 1) / SS) * SS - written;
                if (pad > 0) os.write(new byte[(int) pad]);
            }
        }
    }

    private static void add(Node parent, File f) {
        Node n = new Node();
        n.parent = parent; n.name = f.getName(); n.file = f; n.dir = f.isDirectory();
        n.size = n.dir ? 0 : f.length();
        parent.kids.add(n);
        if (n.dir) { File[] k = f.listFiles(); if (k != null) { Arrays.sort(k); for (File c : k) add(n, c); } }
    }

    private static void collectFiles(Node d, List<Node> out) {
        for (Node k : d.kids) { if (k.dir) collectFiles(k, out); else out.add(k); }
    }

    private static void names(Node d) {
        Set<String> usedP = new HashSet<>(), usedJ = new HashSet<>();
        for (Node k : d.kids) {
            k.pname = unique(isoName(k.name, k.dir), usedP, k.dir);
            k.jname = uniqueJ(jolietName(k.name, k.dir), usedJ);
            if (k.dir) names(k);
        }
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) b.append((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        return b.toString();
    }

    private static String isoName(String name, boolean dir) {
        if (dir) { String c = clean(name); return c.length() > 8 ? c.substring(0, 8) : (c.isEmpty() ? "_" : c); }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot + 1) : "";
        String b = clean(base), e = clean(ext);
        if (b.length() > 8) b = b.substring(0, 8);
        if (e.length() > 3) e = e.substring(0, 3);
        if (b.isEmpty()) b = "_";
        return b + "." + e;
    }

    private static String unique(String n, Set<String> used, boolean dir) {
        String cand = n; int i = 1;
        while (used.contains(cand)) {
            String suffix = "~" + i++;
            if (dir) cand = n.substring(0, Math.min(n.length(), 8 - suffix.length())) + suffix;
            else { int dot = n.indexOf('.'); String b = n.substring(0, dot), e = n.substring(dot); cand = b.substring(0, Math.min(b.length(), 8 - suffix.length())) + suffix + e; }
        }
        used.add(cand);
        return cand;
    }

    private static String jolietName(String n, boolean dir) {
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) b.append("*/:;?\\".indexOf(c) >= 0 || c < 32 ? '_' : c);
        String s = b.toString();
        if (s.length() > 64) {
            int dot = dir ? -1 : s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, 64 - ext.length()) + ext;
        }
        return s.isEmpty() ? "_" : s;
    }

    private static String uniqueJ(String n, Set<String> used) {
        String cand = n; int i = 1;
        while (used.contains(cand.toLowerCase(Locale.ROOT))) {
            String suffix = "~" + i++;
            int dot = n.lastIndexOf('.');
            String b = dot > 0 ? n.substring(0, dot) : n, e = dot > 0 ? n.substring(dot) : "";
            cand = b.substring(0, Math.min(b.length(), 64 - e.length() - suffix.length())) + suffix + e;
        }
        used.add(cand.toLowerCase(Locale.ROOT));
        return cand;
    }

    private static byte[] idBytes(Node n, boolean j) {
        if (n.parent == null) return new byte[]{0};
        return j ? n.jname.getBytes(StandardCharsets.UTF_16BE) : (n.dir ? n.pname : n.pname + ";1").getBytes(StandardCharsets.US_ASCII);
    }

    private static int cmp(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) { int d = (a[i] & 255) - (b[i] & 255); if (d != 0) return d; }
        return a.length - b.length;
    }

    private static List<Node> sortedKids(Node d, boolean j) {
        List<Node> k = new ArrayList<>(d.kids);
        k.sort((x, y) -> cmp(idBytes(x, j), idBytes(y, j)));
        return k;
    }

    private static List<Node> bfs(Node root, boolean j) {
        List<Node> order = new ArrayList<>();
        order.add(root);
        for (int i = 0; i < order.size(); i++) {
            Node d = order.get(i);
            if (j) d.jIndex = i + 1; else d.pIndex = i + 1;
            for (Node k : sortedKids(d, j)) if (k.dir) order.add(k);
        }
        return order;
    }

    private static int recLen(int nameLen) { int l = 33 + nameLen; return l + (l % 2); }

    private static int dirSize(Node d, boolean j) {
        int pos = 0;
        List<Integer> lens = new ArrayList<>();
        lens.add(recLen(1)); lens.add(recLen(1));
        for (Node k : sortedKids(d, j)) lens.add(recLen(idBytes(k, j).length));
        for (int l : lens) { if (pos % SS + l > SS) pos = (pos / SS + 1) * SS; pos += l; }
        return ((pos + SS - 1) / SS) * SS;
    }

    private static int ptSize(List<Node> order, boolean j) {
        int s = 0;
        for (Node d : order) { int l = idBytes(d, j).length; s += 8 + l + (l % 2); }
        return s;
    }

    private static byte[] pathTable(List<Node> order, boolean j, boolean little) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (Node d : order) {
            byte[] id = idBytes(d, j);
            b.write(id.length); b.write(0);
            int ext = j ? d.jExtent : d.pExtent;
            int par = d.parent == null ? 1 : (j ? d.parent.jIndex : d.parent.pIndex);
            byte[] e = new byte[4], p = new byte[2];
            if (little) { le32(e, 0, ext); p[0] = (byte) par; p[1] = (byte) (par >> 8); } else { be32(e, 0, ext); p[0] = (byte) (par >> 8); p[1] = (byte) par; }
            b.write(e, 0, 4); b.write(p, 0, 2);
            b.write(id, 0, id.length);
            if (id.length % 2 == 1) b.write(0);
        }
        return b.toByteArray();
    }

    private static byte[] padTo(byte[] data, int sectors) { return Arrays.copyOf(data, sectors * SS); }

    private static void le32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static void be32(byte[] b, int o, long v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * (3 - i))); }
    private static void both32(byte[] b, int o, long v) { le32(b, o, v); be32(b, o + 4, v); }
    private static void both16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }

    private static byte[] date7(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(ms);
        return new byte[]{(byte) (c.get(Calendar.YEAR) - 1900), (byte) (c.get(Calendar.MONTH) + 1), (byte) c.get(Calendar.DAY_OF_MONTH), (byte) c.get(Calendar.HOUR_OF_DAY), (byte) c.get(Calendar.MINUTE), (byte) c.get(Calendar.SECOND), 0};
    }

    private static byte[] record(long extent, long size, boolean dir, byte[] id, long now) {
        byte[] r = new byte[recLen(id.length)];
        r[0] = (byte) r.length;
        both32(r, 2, extent); both32(r, 10, size);
        System.arraycopy(date7(now), 0, r, 18, 7);
        r[25] = (byte) (dir ? 2 : 0);
        both16(r, 28, 1);
        r[32] = (byte) id.length;
        System.arraycopy(id, 0, r, 33, id.length);
        return r;
    }

    private static byte[] dirBytes(Node d, boolean j, long now) throws IOException {
        int total = j ? d.jDirSize : d.pDirSize;
        byte[] out = new byte[total];
        int pos = 0;
        List<byte[]> recs = new ArrayList<>();
        long self = j ? d.jExtent : d.pExtent;
        Node par = d.parent == null ? d : d.parent;
        long parent = j ? par.jExtent : par.pExtent;
        recs.add(record(self, total, true, new byte[]{0}, now));
        recs.add(record(parent, j ? par.jDirSize : par.pDirSize, true, new byte[]{1}, now));
        for (Node k : sortedKids(d, j)) {
            if (k.dir) recs.add(record(j ? k.jExtent : k.pExtent, j ? k.jDirSize : k.pDirSize, true, idBytes(k, j), now));
            else recs.add(record(k.dataExtent, k.size, false, idBytes(k, j), now));
        }
        for (byte[] r : recs) {
            if (pos % SS + r.length > SS) pos = (pos / SS + 1) * SS;
            System.arraycopy(r, 0, out, pos, r.length);
            pos += r.length;
        }
        return out;
    }

    private static byte[] volDesc(boolean joliet, String vol, int total, int ptSize, int ptL, int ptM, Node root, long now) throws IOException {
        byte[] v = new byte[SS];
        v[0] = (byte) (joliet ? 2 : 1);
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII), 0, v, 1, 5);
        v[6] = 1;
        field(v, 8, 32, "", joliet); field(v, 40, 32, joliet ? vol : clean(vol), joliet);
        both32(v, 80, total);
        if (joliet) { v[88] = '%'; v[89] = '/'; v[90] = 'E'; }
        both16(v, 120, 1); both16(v, 124, 1); both16(v, 128, SS);
        both32(v, 132, ptSize);
        le32(v, 140, ptL); be32(v, 148, ptM);
        byte[] rr = record(joliet ? root.jExtent : root.pExtent, joliet ? root.jDirSize : root.pDirSize, true, new byte[]{0}, now);
        System.arraycopy(rr, 0, v, 156, 34);
        field(v, 190, 128, "", joliet); field(v, 318, 128, "", joliet); field(v, 446, 128, "", joliet); field(v, 574, 128, "", joliet);
        field(v, 702, 37, "", joliet); field(v, 739, 37, "", joliet); field(v, 776, 37, "", joliet);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(now);
        String ds = String.format(Locale.ROOT, "%04d%02d%02d%02d%02d%02d00", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        for (int off : new int[]{813, 830, 864}) { System.arraycopy(ds.getBytes(StandardCharsets.US_ASCII), 0, v, off, 16); v[off + 16] = 0; }
        for (int i = 0; i < 16; i++) v[847 + i] = '0';
        v[881] = 1;
        return v;
    }

    private static void field(byte[] v, int off, int len, String s, boolean joliet) {
        if (joliet) {
            byte[] b = s.getBytes(StandardCharsets.UTF_16BE);
            for (int i = 0; i < len; i += 2) { v[off + i] = 0; v[off + i + 1] = 0x20; }
            System.arraycopy(b, 0, v, off, Math.min(b.length, len / 2 * 2));
        } else {
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            Arrays.fill(v, off, off + len, (byte) 0x20);
            System.arraycopy(b, 0, v, off, Math.min(b.length, len));
        }
    }

    private IsoWriter() {}

    /** Small self-test used on a computer: java IsoWriter out.iso label files... */
    public static void main(String[] a) throws Exception {
        List<File> items = new ArrayList<>();
        for (int i = 2; i < a.length; i++) items.add(new File(a[i]));
        write(items, new File(a[0]), a[1]);
    }
}
