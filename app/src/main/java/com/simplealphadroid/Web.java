package com.simplealphadroid;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal HTTP/1.0-style server: one request per connection. */
public final class Web {
    private static Web inst;
    private final Context ctx;
    private final SonyCam cam;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket ss;
    public final int port;
    static final int STREAM_SNDBUF = 16 * 1024;

    public static synchronized Web start(Context c, int port) {
        if (inst == null) { Auth.init(c); Diag.init(c); inst = new Web(c.getApplicationContext(), port); inst.run(); }
        return inst;
    }

    private Web(Context c, int port) { ctx = c; cam = SonyCam.get(c); this.port = port; }

    /** Why the server isn't listening (null while it is), shown on phone 1's screen. */
    public static volatile String bindError;

    /** Listen on the port, retrying every 3 s if it's taken (e.g. by another app using 8080: an old build under a
     *  different package held it on a test phone and the remote stayed dead until the app was restarted). */
    private void run() {
        Thread t = new Thread(() -> {
            boolean logged = false;
            while (true) {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    try { s.bind(new InetSocketAddress("0.0.0.0", port)); }
                    catch (IOException e) { s.close(); throw e; }
                    ss = s;
                    bindError = null;
                    L.i("HTTP server listening on 0.0.0.0:" + port);
                    logged = false;
                    while (true) {
                        Socket so = s.accept();
                        pool.execute(() -> handle(so));
                    }
                } catch (IOException e) {
                    bindError = "port " + port + ": " + e.getMessage();
                    if (!logged) { L.e("HTTP server can't listen, retrying every 3 s", e); logged = true; }
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "http-accept");
        t.start();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void parseForm(String s, Map<String, String> m) {
        if (s == null || s.isEmpty()) return;
        for (String kv : s.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
    }

    private void handle(Socket so) {
        try (Socket s = so) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            int clen = 0;
            String line, cookie = null;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String hn = line.substring(0, c).trim();
                if (hn.equalsIgnoreCase("content-length")) {
                    try { clen = Integer.parseInt(line.substring(c + 1).trim()); } catch (NumberFormatException ignored) {}
                } else if (hn.equalsIgnoreCase("cookie")) cookie = line.substring(c + 1).trim();
            }
            Map<String, String> q = new HashMap<>();
            String path = target;
            int qi = target.indexOf('?');
            if (qi >= 0) { path = target.substring(0, qi); parseForm(target.substring(qi + 1), q); }
            if (clen > 0 && clen < 65536) {
                byte[] body = new byte[clen];
                int got = 0;
                while (got < clen) { int n = in.read(body, got, clen - got); if (n < 0) break; got += n; }
                parseForm(new String(body, 0, got, StandardCharsets.UTF_8), q);
            }
            if (path.equals("/stream")) {
                // Keep the send queue tiny: with the default (auto-tuned, MBs) buffer a weak Wi-Fi link queues
                // seconds of frames and the viewer watches the past. sndbuf=0 keeps the OS default (for A/B tests).
                int sb = (int) num(q, "sndbuf", STREAM_SNDBUF);
                if (sb > 0) s.setSendBufferSize(sb);
                s.setTcpNoDelay(true);
                L.i("stream socket: sndbuf req=" + sb + " actual=" + s.getSendBufferSize());
            }
            if (path.equals("/api/login")) { login(q.get("pin"), out); return; }
            // optional PIN (Auth): the page itself loads without it (it asks for the PIN), everything else needs it
            if (!Auth.ok(cookie) && !path.equals("/") && !path.equals("/index.html")) {
                if (path.startsWith("/api/")) sendText(out, 401, "application/json", "{\"ok\":false,\"auth\":true,\"msg\":\"PIN required\"}");
                else sendText(out, 401, "text/plain", "PIN required: open the remote page first");
                return;
            }
            route(method, path, q, out);
        } catch (Exception e) {
            if (!(e instanceof java.net.SocketException)) L.e("HTTP handler", e);
        }
    }

    private static void login(String pin, OutputStream out) throws IOException {
        String r = Auth.login(pin == null ? "" : pin.trim());
        if (!r.equals("ok")) {
            sendText(out, 200, "application/json", "{\"ok\":false,\"msg\":" + SonyCam.js(r.startsWith("locked:")
                    ? "Too many wrong PINs. Wait " + r.substring(7) + " s." : "Wrong PIN") + "}");
            return;
        }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nSet-Cookie: " + Auth.COOKIE + "=" + Auth.pin() + "; Path=/; Max-Age=31536000; SameSite=Strict"
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 401 ? " Unauthorized" : " ERR") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Like send, but lets the browser cache it (saved shots never change). */
    private static void sendCached(OutputStream out, String type, byte[] body) throws IOException {
        String h = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: private, max-age=86400\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void sendText(OutputStream out, int code, String type, String s) throws IOException {
        send(out, code, type, s.getBytes(StandardCharsets.UTF_8));
    }

    private static long num(Map<String, String> q, String k, long def) {
        String v = q.get(k);
        if (v == null || v.trim().isEmpty()) return def;
        v = v.trim();
        boolean neg = v.startsWith("-");
        if (neg || v.startsWith("+")) v = v.substring(1);
        long r = v.toLowerCase(Locale.US).startsWith("0x") ? Long.parseLong(v.substring(2), 16) : Long.parseLong(v);
        return neg ? -r : r;
    }

    private static byte[] unhex(String s) {
        s = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static String resJson(Ptp.Result r) {
        StringBuilder sb = new StringBuilder("{\"ok\":").append(r.ok()).append(",\"code\":\"").append(L.h16(r.code))
                .append("\",\"codeName\":\"").append(r.codeName()).append("\",\"params\":[");
        for (int i = 0; i < r.params.length; i++) { if (i > 0) sb.append(','); sb.append('"').append(L.h32(r.params[i])).append('"'); }
        sb.append(']');
        if (r.data != null) sb.append(",\"dataLen\":").append(r.data.length).append(",\"data\":\"").append(L.hex(r.data, 0, r.data.length, 1024)).append('"');
        return sb.append('}').toString();
    }

    private static String msg(boolean ok, String m) {
        return "{\"ok\":" + ok + ",\"msg\":" + SonyCam.js(m) + "}";
    }

    private void route(String method, String path, Map<String, String> q, OutputStream out) throws IOException {
        switch (path) {
            case "/":
            case "/index.html":
            case "/debug":
            case "/debug.html": {
                String asset = path.startsWith("/debug") ? "debug.html" : "index.html";
                try (InputStream a = ctx.getAssets().open(asset)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192]; int n;
                    while ((n = a.read(b)) > 0) bo.write(b, 0, n);
                    send(out, 200, "text/html; charset=utf-8", bo.toByteArray());
                }
                return;
            }
            case "/log":
                sendText(out, 200, "text/plain; charset=utf-8", L.tail((int) num(q, "n", 500)));
                return;
            case "/info":
                sendText(out, 200, "application/json", cam.infoJson());
                return;
            case "/lv.jpg": {
                cam.lastViewerMs = System.currentTimeMillis();
                byte[] j = cam.latestJpeg();
                if (j == null) sendText(out, 503, "text/plain", "no frame yet");
                else send(out, 200, "image/jpeg", Transcode.get(j, -System.nanoTime(), Transcode.Q.parse(q.get("q"))));
                return;
            }
            case "/lvhead.bin":
                cam.lastViewerMs = System.currentTimeMillis();
                send(out, 200, "application/octet-stream", cam.latestHead());
                return;
            case "/stream":
                stream(out, Transcode.Q.parse(q.get("q")));
                return;
            case "/speedtest": { // Wi-Fi speed check: N MB of incompressible bytes (1..64, default 8)
                long mb = Math.max(1, Math.min(64, num(q, "mb", 8)));
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + mb * 1048576
                        + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                long t0 = System.currentTimeMillis();
                for (int i = 0; i < mb; i++) out.write(noise());
                out.flush();
                L.i("speedtest " + mb + " MB sent in " + (System.currentTimeMillis() - t0) + " ms");
                return;
            }
            case "/shot.jpg": { // medium preview JPEG of a saved shot (?base=DSC05230)
                Shots.Shot sh = Shots.get(ctx).find(q.get("base"));
                byte[] pv = sh == null ? null : Shots.get(ctx).preview(sh);
                if (pv == null) sendText(out, 404, "text/plain", "no preview");
                else sendCached(out, "image/jpeg", pv);
                return;
            }
            case "/shot": { // one file of a shot as a download (?base=DSC05230&kind=raw|jpeg|preview)
                Shots.Shot sh = Shots.get(ctx).find(q.get("base"));
                String kind = q.get("kind");
                Shots.File f = sh == null ? null : "jpeg".equals(kind) ? sh.jpeg : "preview".equals(kind) ? sh.preview : sh.raw;
                if (f == null) { sendText(out, 404, "text/plain", "no such file"); return; }
                try (InputStream in = Shots.get(ctx).open(f)) {
                    String h = "HTTP/1.1 200 OK\r\nContent-Type: " + Shots.mime(f.name) + "\r\nContent-Length: " + f.size
                            + "\r\nContent-Disposition: attachment; filename=\"" + f.name.replace("\"", "") + "\""
                            + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                    byte[] b = new byte[64 * 1024]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.flush();
                }
                return;
            }
        }
        if (!path.startsWith("/api/")) { sendText(out, 404, "text/plain", "not found"); return; }
        String cmd = path.substring(5);
        String res;
        try {
            switch (cmd) {
                case "status": res = cam.statusJson(); break;
                case "ping": res = "{\"ok\":true,\"t\":" + System.currentTimeMillis() + "}"; break;
                case "diag": res = Diag.json(ctx, cam); break;
                case "shots": {
                    StringBuilder sb = new StringBuilder("{\"ok\":true,\"shots\":[");
                    int k = 0;
                    for (Shots.Shot sh : Shots.get(ctx).list((int) num(q, "max", 500))) {
                        if (k++ > 0) sb.append(',');
                        sb.append("{\"base\":").append(SonyCam.js(sh.base)).append(",\"t\":").append(sh.time);
                        Shots.File[] fs = {sh.raw, sh.jpeg, sh.preview};
                        String[] ks = {"raw", "jpeg", "preview"};
                        for (int i = 0; i < 3; i++) if (fs[i] != null)
                            sb.append(",\"").append(ks[i]).append("\":{\"name\":").append(SonyCam.js(fs[i].name))
                                    .append(",\"size\":").append(fs[i].size).append('}');
                        sb.append('}');
                    }
                    res = sb.append("],\"folder\":").append(SonyCam.js(Shots.get(ctx).folderLabel())).append("}").toString();
                    break;
                }
                case "card": res = msg(true, cam.cardScan()); break;               // MTP mode: count missing
                case "cardsync": res = msg(true, "1".equals(q.get("cancel")) ? cam.cardSyncCancel() : cam.cardSyncStart()); break;
                case "connect": res = msg(true, cam.scanAndConnect()); break;
                case "disconnect": res = msg(true, cam.disconnect()); break;
                case "reset": res = msg(true, cam.reset()); break;
                case "refresh": res = msg(true, cam.loadAllProps((int) num(q, "mode", 0))); break;
                case "ctrl": res = resJson(cam.control((int) num(q, "code", 0), num(q, "value", 0), (int) num(q, "size", 2))); break;
                case "press": res = resJson(cam.press((int) num(q, "code", 0))); break;
                case "set": res = resJson(cam.setProp((int) num(q, "code", 0), num(q, "value", 0), (int) num(q, "size", 0))); break;
                case "touch": res = resJson(cam.touch((int) num(q, "x", 320), (int) num(q, "y", 240))); break;
                case "afarea": res = resJson(cam.afArea((int) num(q, "x", 320), (int) num(q, "y", 240))); break;
                case "afpos": res = resJson(cam.setAfPos((int) num(q, "x", 320), (int) num(q, "y", 213))); break;
                case "canceltouch": res = resJson(cam.press(0xD2E5)); break;
                case "expo": res = resJson(cam.expoStep((int) num(q, "code", 0), (int) num(q, "dir", 1))); break;
                case "nearfar": res = resJson(cam.control(0xD2D1, num(q, "v", 2) & 0xFFFF, 2)); break;
                case "step": res = resJson(cam.press("near".equals(q.get("dir")) ? 0xD2D7 : 0xD2D8)); break;
                case "s1": res = resJson(cam.control(0xD2C1, "0".equals(q.get("down")) ? 1 : 2, 2)); break;
                case "s2": res = resJson(cam.control(0xD2C2, "0".equals(q.get("down")) ? 1 : 2, 2)); break;
                case "shoot": res = msg(true, cam.shoot()); break;
                case "burst": res = msg(true, cam.burst(!"0".equals(q.get("down")))); break; // hold the shutter (continuous drive)
                case "burstalive": cam.burstAlive(); res = msg(true, cam.burstHeld ? "held" : "not held"); break;
                case "bbf": res = msg(true, cam.setBbf(!"0".equals(q.get("on")))); break;
                case "afon": res = msg(true, cam.afOn(!"0".equals(q.get("down")))); break;
                case "shutter": res = msg(true, cam.shutterNoAf()); break;
                case "raw": {
                    int op = (int) num(q, "op", 0);
                    String ps = q.get("p");
                    long[] params = new long[0];
                    if (ps != null && !ps.trim().isEmpty()) {
                        String[] a = ps.split("[,\\s]+");
                        params = new long[a.length];
                        for (int i = 0; i < a.length; i++) { Map<String, String> t = new HashMap<>(); t.put("v", a[i]); params[i] = num(t, "v", 0); }
                    }
                    byte[] data = null;
                    String hex = q.get("hex");
                    int size = (int) num(q, "size", 0);
                    if (hex != null && !hex.trim().isEmpty()) data = unhex(hex);
                    else if (size > 0) data = Ptp.le(num(q, "value", 0), size);
                    res = resJson(cam.raw(op, params, data));
                    break;
                }
                default: sendText(out, 404, "application/json", msg(false, "unknown api " + cmd)); return;
            }
        } catch (Exception e) {
            L.e("API " + cmd, e);
            res = msg(false, String.valueOf(e));
        }
        sendText(out, 200, "application/json", res);
    }

    private static byte[] noiseBuf;
    /** 1 MB of random bytes for /speedtest (random so no proxy or Wi-Fi layer can compress it). */
    private static synchronized byte[] noise() {
        if (noiseBuf == null) { noiseBuf = new byte[1048576]; new java.util.Random(6400).nextBytes(noiseBuf); }
        return noiseBuf;
    }

    private void stream(OutputStream out, Transcode.Q quality) throws IOException {
        cam.streamClients.incrementAndGet();
        L.i("MJPEG client connected (" + cam.streamClients.get() + ") quality=" + quality);
        long statT0 = System.currentTimeMillis(), statBytes = 0; int statFrames = 0;
        long dT0 = statT0, dBytes = 0, dAge = 0; int dFrames = 0; // 2 s window for Diag
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long last = 0;
            int idle = 0;
            long[] no = new long[2];
            while (true) {
                byte[] j;
                try { j = cam.waitFrame(last, no, 5000); } catch (InterruptedException e) { return; }
                if (j == null) {
                    // no frame for 5 s; after 30 s give up so dead clients don't keep live view polling forever
                    if (++idle >= 6) return;
                    continue;
                }
                idle = 0;
                last = no[0];
                j = Transcode.get(j, last, quality);
                statFrames++; statBytes += j.length;
                long now = System.currentTimeMillis();
                dFrames++; dBytes += j.length; dAge += now - no[1];
                if (now - dT0 >= 2000) { // frames that got through the (16 KB) socket buffer ~= what the browser gets
                    double s = (now - dT0) / 1000.0;
                    Diag.streamFps = dFrames / s; Diag.streamMbps = dBytes * 8 / 1e6 / s; Diag.streamAgeMs = dAge / (double) dFrames;
                    Diag.streamAt = now; Diag.streamQuality = String.valueOf(quality);
                    dT0 = now; dBytes = 0; dAge = 0; dFrames = 0;
                }
                if (now - statT0 >= 10000) {
                    L.i(String.format(java.util.Locale.US, "MJPEG %s: %.1f fps sent, %.0f KB/frame, %.1f Mbit/s",
                            quality, statFrames * 1000.0 / (now - statT0), statBytes / 1024.0 / statFrames,
                            statBytes * 8.0 / 1000.0 / (now - statT0)));
                    statT0 = now; statBytes = 0; statFrames = 0;
                }
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length
                        + "\r\nX-Ts: " + no[1] + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(j);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } finally {
            cam.streamClients.decrementAndGet();
            L.i("MJPEG client gone (" + cam.streamClients.get() + ")");
        }
    }
}
