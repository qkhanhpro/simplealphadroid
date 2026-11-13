package com.simplealphadroid;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Numbers for the page's Diagnostics section and the phone-1 screen's address list. */
public final class Diag {
    private Diag() {}

    // live-view stream, updated by Web.stream every ~2 s (last active client)
    public static volatile double streamFps, streamMbps, streamAgeMs;
    public static volatile long streamAt;
    public static volatile String streamQuality = "";
    // USB transfers
    public static volatile double lastShotMBps;
    public static volatile String lastShotName = "";
    public static volatile long syncT0;

    /** One reachable IPv4 address of phone 1. */
    public static final class Addr {
        public final String ip, iface, kind;
        Addr(String ip, String iface, String kind) { this.ip = ip; this.iface = iface; this.kind = kind; }
    }

    private static Context app;
    static void init(Context c) { app = c.getApplicationContext(); }

    /**
     * IPv4 addresses the second device could use, labelled and best first: hotspot (the app's own Wi-Fi or the phone's
     * hotspot), Wi-Fi, VPN, other. Wi-Fi client, VPN and mobile-data interfaces come from ConnectivityManager (same on
     * every brand); any other wireless / USB-tether interface that is up is phone 1 serving a network = hotspot.
     * Interface names only as a fallback (they differ by brand: ap0, swlan0, softap0, wlan1, ...).
     */
    public static List<Addr> addresses() {
        java.util.Map<String, String> known = new java.util.HashMap<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (nc == null || lp == null || lp.getInterfaceName() == null) continue;
                // A Wi-Fi network phone 1 joined has a default route via a router; one phone 1 serves (the app's
                // local-only hotspot) has none. The OPPO reports its ap0 hotspot as TRANSPORT_WIFI (2026-11-13).
                boolean viaRouter = false;
                for (android.net.RouteInfo r : lp.getRoutes()) if (r.isDefaultRoute() && r.hasGateway()) viaRouter = true;
                String k = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? (viaRouter ? "wifi" : "hotspot")
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                        : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile" : null;
                if (k != null) known.put(lp.getInterfaceName(), k);
            }
        } catch (Exception e) { L.e("addresses: ConnectivityManager", e); }
        List<Addr> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                String kind = known.get(ni.getName());
                if (kind == null) {
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("dummy")
                            || n.startsWith("ipsec") || n.startsWith("clat") || n.startsWith("vgate") || n.startsWith("p2p"))
                        continue; // mobile data, IPv6 helpers, OPPO virtual gateway (172.30.x): unreachable
                    kind = n.startsWith("tun") || n.startsWith("tailscale") || n.startsWith("wg") ? "vpn"
                            : n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") ? "hotspot"
                            : n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb") || n.startsWith("bt-pan") ? "tether"
                            : "other";
                }
                if (kind.equals("mobile")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(new Addr(a.getHostAddress(), ni.getName(), kind));
                }
            }
        } catch (Exception e) { L.e("addresses", e); }
        out.sort((x, y) -> rank(x.kind) - rank(y.kind));
        return out;
    }

    private static int rank(String k) {
        switch (k) { case "hotspot": return 0; case "wifi": return 1; case "ethernet": return 2; case "tether": return 3; case "vpn": return 4; default: return 5; }
    }

    /** Human label for an address kind. */
    static String label(String k) {
        switch (k) {
            case "hotspot": return Lang.t("Hotspot (phone 1's own Wi-Fi)", "Điểm phát sóng (Wi-Fi của điện thoại chính)");
            case "wifi": return Lang.t("Same Wi-Fi network", "Cùng mạng Wi-Fi");
            case "ethernet": return "Ethernet";
            case "tether": return Lang.t("USB/Bluetooth tethering", "Chia sẻ mạng qua USB/Bluetooth");
            case "vpn": return Lang.t("VPN (e.g. Tailscale; the second device must be on it too)",
                    "VPN (vd. Tailscale; thiết bị thứ hai cũng phải dùng VPN đó)");
            default: return Lang.t("Other network", "Mạng khác");
        }
    }

    static String json(Context ctx, SonyCam cam) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"t\":").append(System.currentTimeMillis());

        // phone 1 Wi-Fi as Android sees it (no SSID: that needs the location permission)
        sb.append(",\"wifi\":{");
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
            // no getNetworkId(): Android returns -1 without the location permission (seen on the OPPO, Android 17)
            boolean on = wm.isWifiEnabled() && wi != null && wi.getRssi() > -127 && wi.getLinkSpeed() > 0;
            sb.append("\"connected\":").append(on);
            if (on) {
                int f = wi.getFrequency();
                sb.append(",\"rssi\":").append(wi.getRssi()).append(",\"linkMbps\":").append(wi.getLinkSpeed())
                        .append(",\"txMbps\":").append(wi.getTxLinkSpeedMbps()).append(",\"rxMbps\":").append(wi.getRxLinkSpeedMbps())
                        .append(",\"freqMHz\":").append(f).append(",\"band\":")
                        .append(SonyCam.js(f >= 5925 ? "6 GHz" : f >= 4900 ? "5 GHz" : f > 0 ? "2.4 GHz" : "?"));
            }
        } catch (Exception e) { sb.append("\"error\":").append(SonyCam.js(String.valueOf(e))); }
        sb.append("},\"addrs\":[");
        int k = 0;
        for (Addr a : addresses()) {
            if (k++ > 0) sb.append(',');
            sb.append("{\"ip\":").append(SonyCam.js(a.ip)).append(",\"iface\":").append(SonyCam.js(a.iface))
                    .append(",\"kind\":").append(SonyCam.js(a.kind)).append('}');
        }
        sb.append(']');

        // phone 1 battery, temperature, storage, memory
        sb.append(",\"phone\":{");
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                sb.append("\"battery\":").append(lvl < 0 ? -1 : lvl * 100 / Math.max(1, sc))
                        .append(",\"charging\":").append(b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                        .append(",\"tempC\":").append(b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0).append(',');
            }
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            Runtime rt = Runtime.getRuntime();
            sb.append("\"freeBytes\":").append(fs.getAvailableBytes()).append(",\"totalBytes\":").append(fs.getTotalBytes())
                    .append(",\"heapMB\":").append((rt.totalMemory() - rt.freeMemory()) / 1048576)
                    .append(",\"heapMaxMB\":").append(rt.maxMemory() / 1048576)
                    .append(",\"folder\":").append(SonyCam.js(Shots.get(ctx).folderLabel()));
        } catch (Exception e) { sb.append("\"error\":").append(SonyCam.js(String.valueOf(e))); }
        sb.append('}');

        // live view and USB
        long now = System.currentTimeMillis();
        boolean streamFresh = now - streamAt < 5000;
        sb.append(String.format(Locale.US, ",\"lv\":{\"cameraFps\":%.1f,\"viewers\":%d,\"sentFps\":%.1f,\"sentMbps\":%.2f,\"frameAgeMs\":%.0f,\"quality\":%s}",
                cam.fps, cam.streamClients.get(), streamFresh ? streamFps : 0, streamFresh ? streamMbps : 0,
                streamFresh ? streamAgeMs : 0, SonyCam.js(streamQuality)));
        double syncMBps = cam.syncBytes > 0 && syncT0 > 0 ? cam.syncBytes / 1048576.0 / Math.max(0.001, (now - syncT0) / 1000.0) : 0;
        if (!"syncing".equals(cam.syncState) && cam.syncBytes > 0) syncMBps = -1; // finished: rate shown in the log
        sb.append(String.format(Locale.US, ",\"usb\":{\"connected\":%b,\"mode\":%s,\"status\":%s,\"failures\":%d,\"lastShotMBps\":%.1f,\"lastShot\":%s,\"syncMBps\":%.1f}",
                cam.connected, SonyCam.js(!cam.connected ? "none" : cam.mtpMode ? "MTP" : "PC Remote"), SonyCam.js(cam.status),
                Ptp.failures, lastShotMBps, SonyCam.js(lastShotName), syncMBps));
        sb.append(",\"log\":{\"errors\":").append(L.errors).append(",\"lines\":").append(L.seq()).append('}');
        return sb.append('}').toString();
    }
}
