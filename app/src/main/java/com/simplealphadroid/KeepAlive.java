package com.simplealphadroid;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.lang.ref.WeakReference;

/**
 * Foreground service so the cached-app freezer does not stop the HTTP server / USB threads when the screen is off.
 * Owns the CPU wake lock + Wi-Fi low-latency lock, held only while the camera is connected (SonyCam calls
 * {@link #setCameraActive}): with no camera there is nothing to serve, and the phone may sleep. Plugging the camera
 * in relaunches the app (USB_DEVICE_ATTACHED), which reconnects and takes the locks again.
 * The notification's Stop action (and the Stop button on the phone-1 screen) shut the whole app down: {@link #shutdown}.
 */
public class KeepAlive extends Service {
    static final String ACTION_STOP = "com.simplealphadroid.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown(this);
            return START_NOT_STICKY;
        }
        Lang.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("probe", "SimpleAlphaDroid", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, KeepAlive.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "probe")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(Lang.t("SimpleAlphaDroid running", "SimpleAlphaDroid đang chạy"))
                // the right address depends on the network: shown in the app
                .setContentText(Lang.t("Open to connect the second device", "Mở để kết nối thiết bị thứ hai"))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, Lang.t("Stop", "Dừng"), stop).build())
                .setOngoing(true)
                .build();
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            L.i("foreground service started");
        } catch (Exception e) {
            L.e("startForeground failed", e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        setCameraActive(this, false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- locks ----
    // A foreground service alone doesn't stop doze: with the screen off the CPU slept and Wi-Fi went to power save,
    // so the remote stopped answering (2026-11-12, OPPO). ColorOS also drops wake locks of apps that aren't exempt
    // from battery optimization (see HANDOFF).
    private static PowerManager.WakeLock wake;
    private static WifiManager.WifiLock wifi;

    static synchronized void setCameraActive(Context c, boolean on) {
        try {
            Context a = c.getApplicationContext();
            if (wake == null) {
                wake = ((PowerManager) a.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a6400:server");
                wake.setReferenceCounted(false);
                wifi = ((WifiManager) a.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "a6400:server");
                wifi.setReferenceCounted(false);
            }
            if (on == wake.isHeld()) return;
            if (on) { wake.acquire(); wifi.acquire(); }
            else { wake.release(); if (wifi.isHeld()) wifi.release(); }
            L.i(on ? "camera connected: wake lock + Wi-Fi lock held" : "no camera: wake lock + Wi-Fi lock released");
        } catch (Exception e) { L.e("locks", e); }
    }

    // ---- shutdown ----
    static volatile WeakReference<Activity> screen = new WeakReference<>(null);
    private static volatile boolean stopping;

    /** Disconnect the camera cleanly, release the locks, stop the service, close the screen, end the process. */
    static void shutdown(Context c) {
        if (stopping) return;
        stopping = true;
        final Context a = c.getApplicationContext();
        L.i("=== STOP requested: shutting down");
        new Thread(() -> {
            try { SonyCam cam = SonyCam.get(a); if (cam.connected) cam.disconnect(); } catch (Throwable t) { L.e("stop: disconnect", t); }
            setCameraActive(a, false);
            Hotspot.stop();
            new Handler(Looper.getMainLooper()).post(() -> {
                try { a.stopService(new Intent(a, KeepAlive.class)); } catch (Exception ignored) {}
                Activity s = screen.get();
                if (s != null) s.finishAndRemoveTask();
                // the HTTP server and USB threads live in this process; ending it is the clean "off"
                new Handler(Looper.getMainLooper()).postDelayed(() -> System.exit(0), 400);
            });
        }, "shutdown").start();
    }
}
