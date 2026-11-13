package com.simplealphadroid;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

/**
 * The app's own Wi-Fi for the second device: Android's local-only hotspot (no internet, private to this app). Started only when
 * the user taps the button (it can disturb phone 1's own Wi-Fi). Android picks the name, password and band (often
 * 2.4 GHz) and usually changes them every start. Closed by {@link #stop} and when the app process ends.
 * If the phone's regular hotspot is on, Android refuses (ERROR_INCOMPATIBLE_MODE): use that one instead.
 */
final class Hotspot {
    private Hotspot() {}

    static volatile String state = "off";   // off | starting | on | failed
    static volatile String ssid = "", pass = "", error = "";
    static volatile boolean wpa3;
    private static WifiManager.LocalOnlyHotspotReservation res;

    static synchronized void start(Context c, Runnable onChange) {
        if (res != null || "starting".equals(state)) return;
        state = "starting"; error = "";
        onChange.run();
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    synchronized (Hotspot.class) { res = r; }
                    SoftApConfiguration cfg = r.getSoftApConfiguration();
                    @SuppressWarnings("deprecation") String s = cfg.getSsid();
                    ssid = s == null ? "" : s.replaceAll("^\"|\"$", "");
                    pass = cfg.getPassphrase() == null ? "" : cfg.getPassphrase();
                    wpa3 = cfg.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE;
                    state = "on";
                    L.i("app Wi-Fi on: '" + ssid + "' security=" + cfg.getSecurityType());
                    onChange.run();
                }
                @Override public void onStopped() {
                    synchronized (Hotspot.class) { res = null; }
                    state = "off";
                    L.i("app Wi-Fi stopped by the system");
                    onChange.run();
                }
                @Override public void onFailed(int reason) {
                    synchronized (Hotspot.class) { res = null; }
                    state = "failed";
                    error = reason == ERROR_INCOMPATIBLE_MODE
                            ? Lang.t("The phone's own hotspot (or another Wi-Fi mode) is on. Use it instead: see Other ways to connect.",
                                     "Điểm phát sóng của điện thoại (hoặc chế độ Wi-Fi khác) đang bật. Hãy dùng nó: xem Cách kết nối khác.")
                            : reason == ERROR_TETHERING_DISALLOWED
                            ? Lang.t("Hotspots are not allowed on this phone (device policy).",
                                     "Điện thoại này không cho phép phát Wi-Fi (chính sách thiết bị).")
                            : reason == ERROR_NO_CHANNEL
                            ? Lang.t("No free Wi-Fi channel. Try again in a moment.", "Không còn kênh Wi-Fi trống. Thử lại sau giây lát.")
                            : Lang.t("Android could not start the Wi-Fi (error " + reason + "). Try again, or use Other ways to connect.",
                                     "Android không bật được Wi-Fi (lỗi " + reason + "). Thử lại, hoặc dùng Cách kết nối khác.");
                    L.w("app Wi-Fi failed: reason " + reason);
                    onChange.run();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            state = "failed";
            error = Lang.t("Could not start the Wi-Fi: ", "Không bật được Wi-Fi: ") + e.getMessage();
            L.e("startLocalOnlyHotspot", e);
            onChange.run();
        }
    }

    static synchronized void stop() {
        if (res != null) { try { res.close(); } catch (Exception ignored) {} res = null; L.i("app Wi-Fi stopped"); }
        state = "off";
    }

    /** Wi-Fi QR payload that phone cameras (Android 10+, iOS 11+) join directly. */
    static String wifiQr() {
        return "WIFI:T:" + (wpa3 ? "SAE" : "WPA") + ";S:" + esc(ssid) + ";P:" + esc(pass) + ";;";
    }

    private static String esc(String s) { return s.replaceAll("([\\\\;,:\"])", "\\\\$1"); }
}
