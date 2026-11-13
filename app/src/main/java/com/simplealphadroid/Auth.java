package com.simplealphadroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Optional 4-digit PIN for the web remote (off by default). When on, every page and API call except the main page
 * and /api/login needs the cookie set by a correct PIN. Wrong PINs: 5 in a row lock logins for 30 s, doubling.
 * Changing or turning off the PIN logs every browser out (the cookie holds the PIN itself).
 */
final class Auth {
    private Auth() {}

    static final String COOKIE = "a6pin";
    private static SharedPreferences p;
    private static int wrong;
    private static long lockedUntil, lockMs = 30000;

    static synchronized void init(Context c) {
        if (p == null) p = c.getApplicationContext().getSharedPreferences("auth", Context.MODE_PRIVATE);
    }

    static boolean on() { return p != null && p.getBoolean("on", false); }
    static String pin() { return p == null ? "" : p.getString("pin", ""); }

    static synchronized void setOn(boolean on) {
        if (on && pin().isEmpty()) newPin();
        p.edit().putBoolean("on", on).apply();
        L.i("PIN " + (on ? "on" : "off"));
    }

    static synchronized String newPin() {
        String pin = String.format(java.util.Locale.US, "%04d", new SecureRandom().nextInt(10000));
        p.edit().putString("pin", pin).apply();
        return pin;
    }

    /** True if the request may pass: PIN off, or the Cookie header carries the current PIN. */
    static boolean ok(String cookieHeader) {
        if (!on()) return true;
        if (cookieHeader == null) return false;
        for (String c : cookieHeader.split(";")) {
            String t = c.trim();
            if (t.equals(COOKIE + "=" + pin())) return true;
        }
        return false;
    }

    /** "ok", "wrong" or "locked:<seconds>". */
    static synchronized String login(String pin) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return "locked:" + ((lockedUntil - now + 999) / 1000);
        if (!on() || pin().equals(pin)) { wrong = 0; lockMs = 30000; return "ok"; }
        if (++wrong >= 5) {
            wrong = 0;
            lockedUntil = now + lockMs;
            L.w("PIN: 5 wrong tries, logins locked for " + lockMs / 1000 + " s");
            lockMs = Math.min(lockMs * 2, 3600000);
            return "locked:" + lockMs / 2000;
        }
        return "wrong";
    }
}
