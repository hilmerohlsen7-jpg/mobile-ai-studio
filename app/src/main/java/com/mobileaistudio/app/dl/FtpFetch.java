package com.mobileaistudio.app.dl;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small plain-FTP client (passive mode, resume with REST). One connection per download. */
final class FtpFetch {
    private FtpFetch() {
    }

    static final class Reply {
        final int code;
        final String text;

        Reply(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    static final class Session implements Closeable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;
        final String host;

        Session(Socket socket, String host) throws IOException {
            this.socket = socket;
            this.host = host;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            this.out = socket.getOutputStream();
        }

        /** Reads one reply, including multi-line replies ("220-" ... "220 "). */
        Reply read() throws IOException {
            String line = in.readLine();
            if (line == null) throw new IOException("The FTP server closed the connection.");
            if (line.length() < 3 || !Character.isDigit(line.charAt(0))) {
                throw new IOException("Unexpected answer from the FTP server: " + line);
            }
            int code;
            try {
                code = Integer.parseInt(line.substring(0, 3));
            } catch (NumberFormatException e) {
                throw new IOException("Unexpected answer from the FTP server: " + line);
            }
            StringBuilder text = new StringBuilder(line);
            if (line.length() > 3 && line.charAt(3) == '-') {
                String prefix = line.substring(0, 3) + " ";
                while (true) {
                    String next = in.readLine();
                    if (next == null) throw new IOException("The FTP server closed the connection.");
                    text.append('\n').append(next);
                    if (next.startsWith(prefix)) break;
                }
            }
            return new Reply(code, text.toString());
        }

        Reply cmd(String command) throws IOException {
            if (command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) throw new IOException("Bad FTP command.");
            out.write((command + "\r\n").getBytes("UTF-8"));
            out.flush();
            return read();
        }

        @Override
        public void close() {
            try {
                out.write("QUIT\r\n".getBytes("UTF-8"));
                out.flush();
            } catch (Exception ignored) {
            }
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static URI parse(String url) throws IOException {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            try {
                return new URI(url.replace(" ", "%20"));
            } catch (URISyntaxException e2) {
                throw new IOException("That FTP address is not valid.");
            }
        }
    }

    private static String pathOf(DownloadItem it) throws IOException {
        URI u = parse(it.url);
        String p = u.getPath();
        if (p == null || p.isEmpty() || p.endsWith("/")) throw new IOException("The FTP address must point to a file.");
        return p;
    }

    private static String friendly(Reply r) {
        String t = r.text.toLowerCase();
        if (t.contains("tls") || t.contains("ssl") || t.contains("auth")) {
            return "This server needs secure FTP (FTPS), which is not supported yet.";
        }
        if (r.code == 550) return "File not found on the FTP server.";
        if (r.code == 530) return "The FTP server refused the login.";
        if (r.code == 421) return "The FTP server is busy or closed the connection. Try again later.";
        return "FTP error: " + r.text.replace('\n', ' ');
    }

    static Session connect(DownloadItem it) throws IOException {
        URI u = parse(it.url);
        String host = u.getHost();
        if (host == null) throw new IOException("That FTP address is not valid.");
        int port = u.getPort() > 0 ? u.getPort() : 21;
        String user = "anonymous";
        String pass = "mobileai@localhost";
        String info = u.getUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = colon >= 0 ? info.substring(0, colon) : info;
            pass = colon >= 0 ? info.substring(colon + 1) : "";
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 20000);
        s.setSoTimeout(30000);
        Session session = new Session(s, host);
        try {
            Reply r = session.read();
            if (r.code != 220) throw new IOException(friendly(r));
            r = session.cmd("USER " + user);
            if (r.code == 331 || r.code == 332) r = session.cmd("PASS " + pass);
            if (r.code != 230 && r.code != 202) throw new IOException(friendly(r));
            r = session.cmd("TYPE I");
            if (r.code != 200) throw new IOException(friendly(r));
            return session;
        } catch (IOException e) {
            session.close();
            throw e;
        }
    }

    static void probe(DownloadItem it) throws IOException {
        String path = pathOf(it);
        Session s = connect(it);
        try {
            Reply r = s.cmd("SIZE " + path);
            if (r.code == 213) {
                try {
                    it.total = Long.parseLong(r.text.substring(4).trim());
                } catch (Exception e) {
                    it.total = -1;
                }
            } else if (r.code == 550) {
                throw new IOException(friendly(r));
            } else {
                it.total = -1; // some servers cannot report the size
            }
        } finally {
            s.close();
        }
        it.rangeOk = false; // FTP is resumed as one stream, not in chunks
        it.suggestedName = Util.lastSegment(it.url);
        it.resolvedUrl = null;
    }

    private static final Pattern PASV = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)");
    private static final Pattern EPSV = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");

    private static int passivePort(Session s) throws IOException {
        Reply r = s.cmd("PASV");
        if (r.code == 227) {
            Matcher m = PASV.matcher(r.text);
            if (m.find()) return Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
        }
        r = s.cmd("EPSV");
        if (r.code == 229) {
            Matcher m = EPSV.matcher(r.text);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        throw new IOException("The FTP server would not open a data connection.");
    }

    /** Downloads into part, continuing at offset when the server allows it. */
    static void download(DownloadItem it, File part, long offset, Job job) throws IOException {
        String path = pathOf(it);
        Session s = connect(it);
        try {
            int port = passivePort(s);
            if (offset > 0) {
                Reply r = s.cmd("REST " + offset);
                if (r.code != 350) {
                    offset = 0; // no resume support: start again
                    it.done.set(0);
                }
            }
            Socket data = new Socket();
            data.connect(new InetSocketAddress(s.host, port), 20000);
            data.setSoTimeout(30000);
            try {
                Reply r = s.cmd("RETR " + path);
                if (r.code != 150 && r.code != 125) throw new IOException(friendly(r));
                byte[] buf = new byte[32768];
                InputStream in = data.getInputStream();
                FileOutputStream fo = new FileOutputStream(part, offset > 0);
                try {
                    while (true) {
                        job.checkStop();
                        int n = in.read(buf);
                        if (n < 0) break;
                        fo.write(buf, 0, n);
                        it.done.addAndGet(n);
                    }
                } finally {
                    fo.close();
                }
            } finally {
                data.close();
            }
            Reply fin = s.read();
            if (fin.code != 226 && fin.code != 250) throw new IOException(friendly(fin));
        } finally {
            s.close();
        }
    }
}
