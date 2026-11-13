package com.simplealphadroid;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Logger: logcat tag "A6400" + in-app ring buffer. */
public final class L {
    public static final String TAG = "A6400";
    private static final int MAX = 3000;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static long seq = 0;
    /** Error lines logged since app start (diagnostics). */
    public static volatile int errors;
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private L() {}

    public static void i(String msg) { add('I', msg, null); }
    public static void w(String msg) { add('W', msg, null); }
    public static void e(String msg, Throwable t) { add('E', msg, t); }

    private static void add(char lvl, String msg, Throwable t) {
        if (t != null) msg = msg + " :: " + t;
        if (lvl == 'E') { Log.e(TAG, msg, t); errors++; }
        else if (lvl == 'W') Log.w(TAG, msg);
        else Log.i(TAG, msg);
        String line;
        synchronized (L.class) { line = fmt.format(new Date()); }
        line = line + " " + lvl + " " + msg;
        synchronized (ring) {
            ring.addLast(line);
            seq++;
            while (ring.size() > MAX) ring.removeFirst();
        }
    }

    public static long seq() { synchronized (ring) { return seq; } }

    /** Last n lines joined by '\n'. */
    public static String tail(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (ring) {
            int skip = Math.max(0, ring.size() - n), i = 0;
            for (String s : ring) { if (i++ >= skip) sb.append(s).append('\n'); }
        }
        return sb.toString();
    }

    public static String hex(byte[] b, int off, int len, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, max);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[off + i] & 0xFF));
        }
        if (len > max) sb.append(" ...(+").append(len - max).append(")");
        return sb.toString();
    }

    public static String h16(int v) { return String.format("0x%04X", v & 0xFFFF); }
    public static String h32(long v) { return String.format("0x%08X", v & 0xFFFFFFFFL); }
}
