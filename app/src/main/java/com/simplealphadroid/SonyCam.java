package com.simplealphadroid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Sony SDIO-over-USB camera session: init, props, controls, live view, events. */
public final class SonyCam {
    public static final int SONY_VID = 0x054C;
    public static final String ACTION_PERM = "com.simplealphadroid.USB_PERMISSION";

    // watched codes
    static final int[] WATCH_CTRLS = {0xD2E4, 0xD2DC, 0xD2D1, 0xD2D7, 0xD2D8, 0xD2C1, 0xD2C2};
    static final int[] WATCH_PROPS = {0xD283, 0xD284, 0xD22C, 0xD232, 0x500A, 0xD221, 0xD213};
    static final int[] STATUS_PROPS = {0xD213, 0x500A, 0xD22C, 0xD232, 0xD283, 0xD284, 0xD285, 0xD221, 0xD235,
            0xD24C, 0xD25A, 0xD26A, 0xD264, 0xD215, 0xD218, 0x5007, 0xD20D, 0xD21E, 0x5010, 0x500E, 0xD222, 0xD21D, 0xD217, 0xD24E,
            0xD21F, 0xD22D, 0xD22F, 0xD230, 0xD233, 0x5013};

    private static SonyCam inst;
    public static synchronized SonyCam get(Context c) {
        if (inst == null) inst = new SonyCam(c.getApplicationContext());
        return inst;
    }

    public static final class Prop {
        public int code, dataType, getSet, isEnable, formFlag, controlType = -1;
        public boolean isControl;
        public long def, cur, min, max, step;
        public String sdef, scur;
        public List<Long> settable = new ArrayList<>(), available = new ArrayList<>();
        String curStr() { return scur != null ? "\"" + scur + "\"" : fmtVal(cur, dataType); }
    }

    private final Context ctx;
    private final UsbManager usb;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, String> propNames = new HashMap<>();
    private final Map<Integer, String> ctrlNames = new HashMap<>();

    private volatile Ptp ptp;
    private final Object closeLock = new Object();
    private volatile UsbDeviceConnection conn;
    private volatile UsbInterface iface;
    private volatile UsbDevice dev;
    public volatile boolean connected;
    public volatile String status = "idle (camera not connected)";

    // info
    public volatile String manufacturer = "", model = "", devVersion = "", serial = "";
    public volatile int vendorExtId, extVersion = -1, extParam = -1;
    public final List<Integer> ptpOps = new ArrayList<>(), ptpEvents = new ArrayList<>(), ptpProps = new ArrayList<>();
    public final List<Integer> extProps = new ArrayList<>(), extCtrls = new ArrayList<>();
    public final Map<Integer, Prop> props = new LinkedHashMap<>();
    public volatile String propParseNote = "";
    public volatile String rawPropsHex = "";

    // live view
    private final Object lvLock = new Object();
    private byte[] lastJpeg;
    private byte[] lastHead = new byte[0];   // LV dataset bytes before the JPEG (AF info)
    private long frameNo;
    private long frameMs;   // System.currentTimeMillis() when the current frame arrived from the camera
    public volatile long lastViewerMs;
    public final AtomicInteger streamClients = new AtomicInteger();
    public volatile float fps;
    public volatile String lvNote = "";
    public volatile String lastFrameInfoHex = "";
    private Thread lvThread, evtThread;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();

    private SonyCam(Context c) {
        ctx = c;
        usb = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("props.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) propNames.put(Integer.decode(line.substring(0, sp)), line.substring(sp + 1).trim());
            }
        } catch (Exception e) { L.e("props.txt", e); }
        // D2C5/D2CC/D2CD-D0 names from libgphoto2 + alphawire + SonyAlphaUSB (2026-11-12 review)
        String[] cn = {"D2C1 S1Button", "D2C2 S2Button", "D2C3 AELButton", "D2C4 AFLButton", "D2C5 ReleaseLock(ShutterOneReset)",
                "D2C7 RequestOneShooting", "D2C8 MovieRecButton", "D2C9 FELButton", "D2CB FocusMagnifier",
                "D2CC FocusMagnifierCancel", "D2CD FocusMagnifierUp", "D2CE FocusMagnifierDown", "D2CF FocusMagnifierLeft",
                "D2D0 FocusMagnifierRight", "D2D1 NearFar", "D2D2 AFMFHold", "D2D3 CancelPixelShiftShooting",
                "D2D4 PixelShiftShootingMode", "D2D5 HFRStandby", "D2D6 HFRRecordingCancel", "D2D7 FocusStepNear",
                "D2D8 FocusStepFar", "D2D9 AWBLButton", "D2DA ProgramShift", "D2DB WhiteBalanceInitialization",
                "D2DC AFAreaPosition", "D2DD ZoomOperation", "D2E3 HighResolutionSSAdjust", "D2E4 RemoteTouchOperation",
                "D2E5 CancelRemoteTouchOperation", "D2E8 WiFiPowerOff", "D2F0 HighResSSAdjustInIntegralMultiples",
                "D2F1 FlickerScan"};
        for (String s : cn) ctrlNames.put(Integer.parseInt(s.substring(0, 4), 16), s.substring(5));

        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (ACTION_PERM.equals(intent.getAction())) {
                    boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    L.i("USB permission result: granted=" + ok + " dev=" + (d == null ? "null" : d.getDeviceName()));
                    if (ok && d != null) connectAsync(d);
                    else status = "USB permission denied";
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                    L.i("USB detached: " + (d == null ? "?" : d.getDeviceName()));
                    if (d != null && dev != null && d.getDeviceName().equals(dev.getDeviceName())) close("detached");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(rcv, f);
    }

    public String propName(int code) {
        String n = ctrlNames.get(code);
        if (n == null) n = propNames.get(code);
        return n == null ? "?" : n;
    }

    // ------------------------------------------------------------------ connect

    /** Scan device list, pick Sony / still-image device, request permission or connect. */
    public String scanAndConnect() {
        autoConnect = true;
        Map<String, UsbDevice> list = usb.getDeviceList();
        L.i("scan: " + list.size() + " USB device(s)");
        UsbDevice pick = null;
        for (UsbDevice d : list.values()) {
            StringBuilder sb = new StringBuilder("  dev ").append(d.getDeviceName())
                    .append(" vid=").append(L.h16(d.getVendorId())).append(" pid=").append(L.h16(d.getProductId()))
                    .append(" class=").append(d.getDeviceClass()).append(" ifaces=").append(d.getInterfaceCount());
            try { sb.append(" prod=").append(d.getProductName()).append(" mfr=").append(d.getManufacturerName()); } catch (Exception ignored) {}
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                sb.append(" [if").append(it.getId()).append(" cls=").append(it.getInterfaceClass())
                        .append('/').append(it.getInterfaceSubclass()).append('/').append(it.getInterfaceProtocol())
                        .append(" eps=").append(it.getEndpointCount()).append(']');
                if (it.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE && pick == null) pick = d;
            }
            L.i(sb.toString());
            if (d.getVendorId() == SONY_VID) pick = d;
        }
        if (pick == null) { status = "no camera found"; return status; }
        if (connected && dev != null && dev.getDeviceName().equals(pick.getDeviceName())) return "already connected";
        if (!usb.hasPermission(pick)) {
            Intent i = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            usb.requestPermission(pick, pi);
            status = "permission requested - accept the dialog on the phone";
            L.i(status);
            return status;
        }
        connectAsync(pick);
        return "connecting " + pick.getDeviceName();
    }

    // ---- Auto-connect ----
    // While no camera is connected, look for one every 3 s and connect (camera back from auto power-off, switched
    // between MTP and PC Remote, replugged, app started with the camera already in). USB permission is asked once
    // per device appearance, not every poll. Paused after a manual disconnect (/api/disconnect before a reinstall)
    // until the next connect request or app restart.
    public volatile boolean autoConnect = true;
    private volatile String permAskedFor = null;
    private Thread autoThread;

    public synchronized void startAutoConnect() {
        if (autoThread != null) return;
        autoThread = new Thread(() -> {
            L.i("auto-connect watcher started");
            while (true) {
                sleep(3000);
                if (!autoConnect || connected || recovering.get()) continue;
                try {
                    UsbDevice d = findCamera();
                    if (d == null) { permAskedFor = null; continue; }
                    if (usb.hasPermission(d)) {
                        L.i("auto-connect: camera found " + d.getDeviceName());
                        connectBlocking(d);
                    } else if (!d.getDeviceName().equals(permAskedFor)) {
                        permAskedFor = d.getDeviceName();
                        L.i("auto-connect: asking USB permission for " + d.getDeviceName());
                        Intent i = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
                        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                        status = "permission requested - accept the dialog on the phone";
                    }
                } catch (Throwable t) { L.e("auto-connect", t); }
            }
        }, "auto-connect");
        autoThread.setDaemon(true);
        autoThread.start();
    }

    /** First Sony / still-image device, quietly (no log). */
    private UsbDevice findCamera() {
        UsbDevice pick = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == SONY_VID) return d;
            for (int i = 0; i < d.getInterfaceCount(); i++)
                if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE && pick == null) pick = d;
        }
        return pick;
    }

    private void connectBlocking(UsbDevice d) {
        java.util.concurrent.Future<?> f = worker.submit(() -> {
            try { connect(d); }
            catch (Throwable t) { L.e("connect failed", t); status = "connect failed: " + t; }
        });
        try { f.get(); } catch (Exception ignored) {}
    }

    public void connectAsync(UsbDevice d) {
        worker.execute(() -> {
            try { connect(d); }
            catch (Throwable t) { L.e("connect failed", t); status = "connect failed: " + t; }
        });
    }

    private synchronized void connect(UsbDevice d) throws IOException {
        // the USB-attach intent and the auto-connect watcher can both fire for one plug-in: don't reopen a live link
        if (connected && dev != null && dev.getDeviceName().equals(d.getDeviceName())) { L.i("connect: already connected"); return; }
        if (connected) close("reconnect");
        status = "opening " + d.getDeviceName();
        L.i("=== connect " + d.getDeviceName() + " vid=" + L.h16(d.getVendorId()) + " pid=" + L.h16(d.getProductId()));
        UsbInterface found = null;
        UsbEndpoint bin = null, bout = null, bintr = null;
        for (int i = 0; i < d.getInterfaceCount() && found == null; i++) {
            UsbInterface it = d.getInterface(i);
            UsbEndpoint a = null, b = null, c = null;
            for (int e = 0; e < it.getEndpointCount(); e++) {
                UsbEndpoint ep = it.getEndpoint(e);
                L.i("  if" + it.getId() + " ep " + L.h16(ep.getAddress()) + " type=" + ep.getType() + " dir="
                        + (ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT") + " mps=" + ep.getMaxPacketSize()
                        + " interval=" + ep.getInterval());
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) a = ep; else b = ep;
                } else if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && ep.getDirection() == UsbConstants.USB_DIR_IN) c = ep;
            }
            boolean still = it.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE;
            if (a != null && b != null && (still || d.getInterfaceCount() == 1 || i == d.getInterfaceCount() - 1)) {
                if (!still) L.w("  no class-6 interface; using if" + it.getId() + " class=" + it.getInterfaceClass());
                found = it; bin = a; bout = b; bintr = c;
            }
        }
        if (found == null) throw new IOException("no interface with bulk in/out");
        UsbDeviceConnection cn = usb.openDevice(d);
        if (cn == null) throw new IOException("openDevice returned null (permission?)");
        if (!cn.claimInterface(found, true)) { cn.close(); throw new IOException("claimInterface failed"); }
        L.i("claimed if" + found.getId() + " (force). serial=" + safeSerial(cn));
        Ptp np = new Ptp(cn, bin, bout, bintr);
        np.onFailure = () -> { if (np.consecutiveFailures >= RECOVER_AFTER) recoverAsync(np); };
        synchronized (closeLock) { conn = cn; iface = found; dev = d; ptp = np; lastDev = d; }
        seenObjectAdded = false;
        synchronized (props) { props.clear(); } // no stale values from a previous session (MTP mode sends none)
        objectAddedUsed = objectAddedSeq.get();
        ptp.classStatus(found.getId());
        connected = true;
        KeepAlive.setCameraActive(ctx, true);
        startEvents();
        status = "init";
        runInit(d.getProductId());
        if (!mtpMode) startLiveView();
    }

    /** Camera in USB Connection = MTP (card access, no remote control). PC Remote is pid 0x0CAA, MTP 0x0CA9. */
    public volatile boolean mtpMode;

    private static String safeSerial(UsbDeviceConnection c) { try { return c.getSerial(); } catch (Exception e) { return "?"; } }

    private void runInit(int pid) {
        try {
            mtpMode = false;
            Ptp.Result r = ptp.transact(0x1002, new long[]{1}, null, true);
            if (r.code == 0x201E) L.w("session already open - continuing");
            else if (!r.ok()) L.w("OpenSession not OK, continuing anyway");
            if (pid != 0x0CAA) {
                // Not the PC Remote id: ask what it supports before sending Sony SDIO ops. In MTP mode SDIO_Connect
                // gets no answer and leaves the USB link stuck (seen 2026-11-11), so skip the remote-control init.
                r = ptp.transact(0x1001, null, null, true);
                if (r.ok() && r.data != null) parseDeviceInfo(r.data);
                if (!ptpOps.contains(0x9201)) {
                    mtpMode = true;
                    status = "connected (MTP: card access, no remote control): " + model;
                    L.i("=== init done: " + status);
                    return;
                }
            }

            sdioConnect(1);
            sdioConnect(2);
            r = ptp.transact(0x9202, new long[]{300}, null, true);
            extParam = 300;
            if (!r.ok() || r.data == null) {
                L.w("GetExtDeviceInfo(300) failed, retry with 200 (0xC8)");
                r = ptp.transact(0x9202, new long[]{200}, null, true);
                extParam = 200;
            }
            if (r.ok() && r.data != null) parseExtDeviceInfo(r.data);
            else L.w("GetExtDeviceInfo failed both ways");
            sdioConnect(3);

            r = ptp.transact(0x1001, null, null, true);
            if (r.ok() && r.data != null) parseDeviceInfo(r.data);

            loadAllProps(0);
            logWatched();
            status = "connected: " + model;
            L.i("=== init done: " + status);
            // An AF/MF Hold left down by a previous session survives the USB disconnect and keeps the
            // camera stuck in MF (measured). Release it so a fresh session starts in the body's own focus mode.
            bbf = false; afOnDown = false; burstHeld = false;
            try { L.i("clear stale AF/MF hold -> " + L.h16(control(AFMF_HOLD, 1, 2).code)); } catch (Throwable t) { L.e("clear hold", t); }
        } catch (Throwable t) {
            L.e("init sequence aborted", t);
            status = "init failed (see log); you can still try commands";
            // A camera left stuck by an earlier session fails right at OpenSession (bulk OUT -1). Reset + reopen
            // (rate-limited; no-op while a recovery is already running, e.g. when this init is part of one).
            if (t instanceof IOException) { Ptp p = ptp; if (p != null) recoverAsync(p); }
        }
    }

    private void sdioConnect(int phase) throws IOException {
        Ptp.Result r = ptp.transact(0x9201, new long[]{phase, 0, 0}, null, true);
        if (!r.ok()) L.w("SDIO_Connect phase " + phase + " -> " + L.h16(r.code));
    }

    /** Not synchronized with connect(): may be called from the main thread while init runs. */
    public void close(String why) {
        L.i("close: " + why);
        connected = false;
        burstHeld = false; // else the drain stays paused after a reconnect
        KeepAlive.setCameraActive(ctx, false);
        status = "disconnected (" + why + ")";
        UsbDeviceConnection c; UsbInterface i;
        synchronized (closeLock) { c = conn; i = iface; conn = null; iface = null; dev = null; ptp = null; }
        try { if (c != null && i != null) c.releaseInterface(i); } catch (Exception ignored) {}
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    public String disconnect() {
        autoConnect = false; // stay off until the next connect request (e.g. before a reinstall)
        Ptp p = ptp;
        if (p != null) { try { p.transact(0x1003, null, null, true); } catch (Exception e) { L.e("CloseSession", e); } }
        close("user");
        return "closed";
    }

    // ---- Automatic recovery ----
    // A failed transfer could leave the link stuck (bulk OUT -1 / timeouts until a replug, or until /api/reset +
    // disconnect + connect by hand). After RECOVER_AFTER transport failures in a row: clear the endpoint halts and send
    // the PTP class Device Reset (as alphawire / tethr / darkgrade do), check the link with GetDeviceInfo, and if it is
    // still dead close and reopen the device with the full init. At most one recovery at a time, 3 per minute.
    private static final int RECOVER_AFTER = 3;
    private volatile UsbDevice lastDev;
    private final AtomicBoolean recovering = new AtomicBoolean();
    private final long[] recoverTimes = new long[3];
    public volatile int recoveries;
    public volatile String lastRecovery = "";

    private void recoverAsyncForced(Ptp p) { recoverAsync(p, true); }
    private void recoverAsync(Ptp p) { recoverAsync(p, false); }

    private void recoverAsync(Ptp p, boolean manual) {
        if (!connected || ptp != p || !recovering.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        synchronized (recoverTimes) {
            if (!manual && now - recoverTimes[0] < 60000) {
                L.w("recovery skipped: 3 already in the last minute");
                recovering.set(false);
                return;
            }
            System.arraycopy(recoverTimes, 1, recoverTimes, 0, recoverTimes.length - 1);
            recoverTimes[recoverTimes.length - 1] = now;
        }
        new Thread(() -> {
            try { recover(p); }
            catch (Throwable t) { L.e("recovery", t); lastRecovery = "failed: " + t; }
            finally { recovering.set(false); }
        }, "ptp-recover").start();
    }

    private void recover(Ptp p) throws IOException {
        UsbInterface i = iface;
        UsbDevice d = lastDev;
        L.w("=== RECOVER: " + p.consecutiveFailures + " USB failures in a row");
        status = "recovering USB link";
        recoveries++;
        if (i != null) {
            p.clearHalts();
            p.classReset(i.getId());
            sleep(300);
            p.classStatus(i.getId());
            // Don't stop here even if GetDeviceInfo answers: DeviceReset ends the session, and verified 2026-11-12 the
            // camera then answered GetDeviceInfo but stalled every SDIO op (bulk OUT -1). Always reopen + full init.
        }
        if (d == null || !hasDevice(d)) {
            lastRecovery = "camera gone";
            close("USB failures, camera gone");
            return;
        }
        close("recovering");
        sleep(800);
        connect(d);
        lastRecovery = connected ? "reopened + re-init" : "reopen failed";
        L.i("=== RECOVER: " + lastRecovery);
    }

    private boolean hasDevice(UsbDevice d) {
        for (UsbDevice x : usb.getDeviceList().values()) if (x.getDeviceName().equals(d.getDeviceName())) return true;
        return false;
    }

    public String reset() {
        Ptp p = ptp; UsbInterface i = iface;
        if (p == null || i == null) return "not connected";
        // DeviceReset ends the camera's session (SDIO ops stall afterwards), so reopen with the full init right away.
        recoverAsyncForced(p);
        return "reset + reconnect started (see /log)";
    }

    // ------------------------------------------------------------------ parsing

    private void parseExtDeviceInfo(byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            extVersion = b.getShort() & 0xFFFF;
            long np = b.getInt() & 0xFFFFFFFFL;
            synchronized (props) {
                extProps.clear(); extCtrls.clear();
                for (long i = 0; i < np; i++) extProps.add(b.getShort() & 0xFFFF);
                if (b.remaining() >= 4) {
                    long nc = b.getInt() & 0xFFFFFFFFL;
                    for (long i = 0; i < nc; i++) extCtrls.add(b.getShort() & 0xFFFF);
                }
            }
            L.i("ExtDeviceInfo ver=" + extVersion + " (" + (extVersion / 100.0) + ") props=" + extProps.size()
                    + " ctrls=" + extCtrls.size() + " leftover=" + b.remaining());
            logCodeList("EXT PROPS", extProps);
            logCodeList("EXT CTRLS", extCtrls);
        } catch (Exception e) {
            L.e("parseExtDeviceInfo broke; raw=" + L.hex(d, 0, d.length, 2000), e);
        }
    }

    private void logCodeList(String title, List<Integer> codes) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int c : codes) {
            sb.append(L.h16(c)).append(' ').append(propName(c)).append("; ");
            if (++k % 12 == 0) { L.i(title + ": " + sb); sb.setLength(0); }
        }
        if (sb.length() > 0) L.i(title + ": " + sb);
    }

    private void logWatched() {
        for (int c : WATCH_CTRLS) L.i(watchLine(c));
        for (int c : WATCH_PROPS) L.i(watchLine(c));
    }

    private String watchLine(int c) {
        Prop p;
        synchronized (props) { p = props.get(c); }
        return "WATCH " + L.h16(c) + " " + propName(c) + ": inExtCtrls=" + extCtrls.contains(c)
                + " inExtProps=" + extProps.contains(c) + " inPtpProps=" + ptpProps.contains(c)
                + " in9209=" + (p != null) + (p != null ? " cur=" + p.curStr() + " enable=" + p.isEnable + " getSet=" + p.getSet : "");
    }

    private static String ptpStr(ByteBuffer b) {
        int n = b.get() & 0xFF;
        if (n == 0) return "";
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = (char) (b.getShort() & 0xFFFF);
        String s = new String(c);
        int z = s.indexOf('\0');
        return z >= 0 ? s.substring(0, z) : s;
    }

    private static List<Integer> u16arr(ByteBuffer b) {
        long n = b.getInt() & 0xFFFFFFFFL;
        List<Integer> l = new ArrayList<>();
        for (long i = 0; i < n; i++) l.add(b.getShort() & 0xFFFF);
        return l;
    }

    private void parseDeviceInfo(byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int stdVer = b.getShort() & 0xFFFF;
            vendorExtId = b.getInt();
            int vendExtVer = b.getShort() & 0xFFFF;
            String vendExtDesc = ptpStr(b);
            int funcMode = b.getShort() & 0xFFFF;
            List<Integer> ops = u16arr(b), evs = u16arr(b), dps = u16arr(b);
            List<Integer> capF = u16arr(b), imgF = u16arr(b);
            manufacturer = ptpStr(b); model = ptpStr(b); devVersion = ptpStr(b); serial = ptpStr(b);
            synchronized (props) {
                ptpOps.clear(); ptpOps.addAll(ops);
                ptpEvents.clear(); ptpEvents.addAll(evs);
                ptpProps.clear(); ptpProps.addAll(dps);
            }
            L.i("DeviceInfo std=" + stdVer + " vendorExt=" + L.h32(vendorExtId) + " v" + vendExtVer + " desc='" + vendExtDesc
                    + "' funcMode=" + funcMode + " mfr='" + manufacturer + "' model='" + model + "' ver='" + devVersion
                    + "' serial='" + serial + "' capFmts=" + capF.size() + " imgFmts=" + imgF.size());
            StringBuilder sb = new StringBuilder();
            for (int o : ops) sb.append(L.h16(o)).append(' ');
            L.i("PTP OPS(" + ops.size() + "): " + sb);
            sb.setLength(0);
            for (int o : evs) sb.append(L.h16(o)).append(' ');
            L.i("PTP EVENTS(" + evs.size() + "): " + sb);
            sb.setLength(0);
            for (int o : dps) sb.append(L.h16(o)).append(' ');
            L.i("PTP PROPS(" + dps.size() + "): " + sb);
        } catch (Exception e) {
            L.e("parseDeviceInfo broke; raw=" + L.hex(d, 0, d.length, 600), e);
        }
    }

    private static int typeSize(int dt) {
        switch (dt) {
            case 1: case 2: return 1;
            case 3: case 4: return 2;
            case 5: case 6: return 4;
            case 7: case 8: return 8;
            default: return -1;
        }
    }

    private static long readVal(ByteBuffer b, int dt) {
        switch (dt) {
            case 1: return b.get();
            case 2: return b.get() & 0xFF;
            case 3: return b.getShort();
            case 4: return b.getShort() & 0xFFFF;
            case 5: return b.getInt();
            case 6: return b.getInt() & 0xFFFFFFFFL;
            case 7: case 8: return b.getLong();
            default:
                if (dt >= 0x4001 && dt <= 0x4008) { // PTP array: u32 n + elements; return count, skip elements
                    long n = b.getInt() & 0xFFFFFFFFL;
                    for (long i = 0; i < n; i++) readVal(b, dt & 0xFF);
                    return n;
                }
                throw new IllegalStateException("unknown datatype " + L.h16(dt));
        }
    }

    private static String readStr(ByteBuffer b) {
        int n = b.get() & 0xFF;
        byte[] a = new byte[n * 2];
        b.get(a);
        String s = new String(a, StandardCharsets.UTF_16LE);
        int z = s.indexOf('\0');
        return z >= 0 ? s.substring(0, z) : s;
    }

    static String fmtVal(long v, int dt) {
        int sz = typeSize(dt);
        if (sz == 1) return String.format("0x%02X(%d)", v & 0xFF, v);
        if (sz == 2) return String.format("0x%04X(%d)", v & 0xFFFF, v);
        if (sz == 4) return String.format("0x%08X(%d)", v & 0xFFFFFFFFL, v);
        return String.format("0x%X(%d)", v, v);
    }

    /** 0x9209 [mode]: 0 = all, 1 = changed only. */
    public String loadAllProps(int mode) throws IOException {
        Ptp p = ptp;
        if (p == null) return "not connected";
        if (mtpMode) return "MTP mode: no camera properties";
        boolean full = mode == 0;
        Ptp.Result r = p.transact(0x9209, new long[]{mode}, null, full);
        if (!r.ok() || r.data == null) return "0x9209 -> " + L.h16(r.code);
        byte[] d = r.data;
        if (full) rawPropsHex = L.hex(d, 0, d.length, 4096);
        // SDIO v300 (Wi-Fi app): enum = settable list + available list, controls carry a ControlDesc.
        // SDIO v200 (A6400 over USB reports 200): one enum list, controls use the plain prop layout (as gphoto2).
        boolean v3 = extVersion >= 300;
        if (parseLayout == 1) v3 = true; else if (parseLayout == 2) v3 = false;
        List<Prop> list = new ArrayList<>();
        String err = parseProps(d, v3, list);
        if (err != null) {
            List<Prop> list2 = new ArrayList<>();
            String err2 = parseProps(d, !v3, list2);
            if (err2 == null) {
                L.w("0x9209: layout " + (v3 ? "v300" : "v200") + " failed (" + err + "); " + (v3 ? "v200" : "v300") + " layout worked - using it");
                parseLayout = v3 ? 2 : 1;
                v3 = !v3; err = null; list = list2;
            } else {
                L.w("0x9209 parse failed both layouts. " + (v3 ? "v300" : "v200") + ": " + err + " || other: " + err2);
                if (list2.size() > list.size()) list = list2;
            }
        }
        int changed = 0;
        for (Prop np : list) {
            Prop old;
            synchronized (props) { old = props.put(np.code, np); }
            if (full) L.i("PROP " + describe(np));
            else if (old == null || old.cur != np.cur || old.isEnable != np.isEnable || old.getSet != np.getSet
                    || (np.scur != null && !np.scur.equals(old.scur))) {
                changed++;
                L.i("PROP CHG " + L.h16(np.code) + " " + propName(np.code) + ": "
                        + (old == null ? "new" : old.curStr() + " en=" + old.isEnable) + " -> " + np.curStr() + " en=" + np.isEnable);
            }
        }
        if (full) propParseNote = (err == null ? "ok " : "PARTIAL ") + list.size() + " entries, layout " + (v3 ? "v300" : "v200")
                + (err == null ? "" : " :: " + err);
        return full ? propParseNote : "changed " + changed;
    }

    /** 0 = auto (by ext version), 1 = v300 layout, 2 = v200 layout (sticky after a successful fallback). */
    private volatile int parseLayout = 0;

    /** Returns null on success, else an error description (list holds what parsed before the break). */
    private String parseProps(byte[] d, boolean v3, List<Prop> out) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long count = -1;
        int startPos = 0, code = -1;
        try {
            count = b.getLong();
            for (long i = 0; i < count; i++) {
                startPos = b.position();
                code = b.getShort() & 0xFFFF;
                Prop np = new Prop();
                np.code = code;
                if (v3 && (code & 0xF000) == 0xD000 && code >= 0xD2C1) {
                    // ControlDesc: u16 dt | u8 controlType | u8 isEnable | 4 reserved | u8 formFlag | (form? guessed)
                    np.isControl = true;
                    np.dataType = b.getShort() & 0xFFFF;
                    np.controlType = b.get() & 0xFF;
                    np.isEnable = b.get() & 0xFF;
                    b.position(b.position() + 4);
                    np.formFlag = b.get() & 0xFF;
                    readForm(b, np, true);
                } else {
                    np.isControl = (code & 0xF000) == 0xD000 && code >= 0xD2C1;
                    np.dataType = b.getShort() & 0xFFFF;
                    np.getSet = b.get() & 0xFF;
                    np.isEnable = b.get() & 0xFF;
                    if (np.dataType == 0xFFFF) { np.sdef = readStr(b); np.scur = readStr(b); }
                    else { np.def = readVal(b, np.dataType); np.cur = readVal(b, np.dataType); }
                    np.formFlag = b.get() & 0xFF;
                    readForm(b, np, v3);
                }
                out.add(np);
            }
            if (b.remaining() > 0) L.w("0x9209 leftover " + b.remaining() + " bytes: " + L.hex(d, b.position(), b.remaining(), 64));
            return null;
        } catch (Exception e) {
            return "broke at entry " + out.size() + "/" + count + " code=" + L.h16(code) + " offset=" + startPos
                    + " (" + e + "); bytes from entry: " + L.hex(d, startPos, Math.max(0, d.length - startPos), 96);
        }
    }

    private static void readForm(ByteBuffer b, Prop np, boolean twoLists) {
        if (np.formFlag == 1) {
            np.min = readVal(b, np.dataType); np.max = readVal(b, np.dataType); np.step = readVal(b, np.dataType);
        } else if (np.formFlag == 2) {
            int n = b.getShort() & 0xFFFF;
            for (int k = 0; k < n; k++) np.settable.add(readVal(b, np.dataType));
            if (twoLists) {
                int m = b.getShort() & 0xFFFF;
                for (int k = 0; k < m; k++) np.available.add(readVal(b, np.dataType));
            }
        } else if (np.formFlag != 0) {
            throw new IllegalStateException("unknown formFlag " + np.formFlag);
        }
    }

    String describe(Prop p) {
        StringBuilder sb = new StringBuilder();
        sb.append(L.h16(p.code)).append(' ').append(propName(p.code));
        if (p.isControl) sb.append(" [CTRL type=").append(p.controlType).append(']');
        sb.append(" dt=").append(L.h16(p.dataType)).append(" gs=").append(p.getSet).append(" en=").append(p.isEnable);
        if (p.scur != null) sb.append(" cur=\"").append(p.scur).append("\" def=\"").append(p.sdef).append('"');
        else if (p.controlType < 0) sb.append(" cur=").append(fmtVal(p.cur, p.dataType)).append(" def=").append(fmtVal(p.def, p.dataType));
        if (p.formFlag == 1) sb.append(" range=").append(p.min).append("..").append(p.max).append(" step ").append(p.step);
        if (p.formFlag == 2) {
            sb.append(" enum=").append(hexList(p.settable)); if (!p.available.isEmpty()) sb.append(" avail=").append(hexList(p.available));
        }
        return sb.toString();
    }

    private static String hexList(List<Long> l) {
        StringBuilder sb = new StringBuilder("[");
        int k = 0;
        for (long v : l) {
            if (k++ > 0) sb.append(',');
            if (k > 60) { sb.append("..+").append(l.size() - 60); break; }
            sb.append(Long.toHexString(v));
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ commands

    private Ptp need() throws IOException {
        Ptp p = ptp;
        if (p == null || !connected) throw new IOException("not connected");
        return p;
    }

    public Ptp.Result control(int code, long value, int size) throws IOException {
        L.i("CTRL " + L.h16(code) + " " + propName(code) + " value=" + L.h32(value) + " size=" + size);
        return need().transact(0x9207, new long[]{code}, size > 0 ? Ptp.le(value, size) : null, true);
    }

    public int propSize(int code) {
        Prop p;
        synchronized (props) { p = props.get(code); }
        if (p == null) return 2;
        int s = typeSize(p.dataType);
        return s > 0 ? s : 2;
    }

    public Ptp.Result setProp(int code, long value, int size) throws IOException {
        if (size <= 0) size = propSize(code);
        L.i("SET " + L.h16(code) + " " + propName(code) + " value=" + L.h32(value) + " size=" + size);
        Ptp.Result r = need().transact(0x9205, new long[]{code}, Ptp.le(value, size), true);
        queueRefresh();
        return r;
    }

    public Ptp.Result touch(int x, int y) throws IOException {
        return control(0xD2E4, ((long) (x & 0xFFFF) << 16) | (y & 0xFFFF), 4);
    }

    public Ptp.Result afArea(int x, int y) throws IOException {
        return control(0xD2DC, ((long) (x & 0xFFFF) << 16) | (y & 0xFFFF), 4);
    }

    public Ptp.Result press(int code) throws IOException {
        Ptp.Result r = control(code, 2, 2);
        Ptp.Result r2 = control(code, 1, 2);
        return r.ok() ? r2 : r;
    }

    /** Current value of a prop from the last 0x9209 read, or -1 if unknown. */
    private long cur(int code) {
        synchronized (props) { Prop p = props.get(code); return p == null ? -1 : p.cur; }
    }

    /** Half-press, wait for focus, full-press. A fixed delay was too short for AF-S focus priority: the camera
     *  had no lock yet and silently didn't fire. Wait for 0xD213 Focus Indication 2/6 (focused) or 3/7 (failed), up to 2 s
     *  (slowest seen 1.3 s); with AF w/ shutter Off it never comes and the shot fires unfocused. Skip the wait in MF, where it stays 1. */
    /** One exposure step, as Imaging Edge Remote does it (USB capture 2026-11-11): 0x9207 ControlDevice on the
     *  prop code with +1 / -1. 2 bytes for EV 0x5010, shutter 0xD20D, F 0x5007; 4 bytes for ISO 0xD21E.
     *  +1 = EV up, faster shutter, higher F-number, higher ISO (Auto is the step below the lowest ISO). */
    public Ptp.Result expoStep(int code, int dir) throws IOException {
        if (code != 0x5010 && code != 0xD20D && code != 0x5007 && code != 0xD21E)
            throw new IOException("not an exposure prop: " + L.h16(code));
        int size = code == 0xD21E ? 4 : 2;
        Ptp.Result r = control(code, dir > 0 ? 1 : 0xFFFF, size);
        queueRefresh();
        return r;
    }

    /** After S1 down: wait for focus (skipped in MF), see shoot(). Returns "" in MF, else " focus=ok|FAILED|timeout in Nms".
     *  Stops early when abort says so (burst released during the wait). */
    private String waitFocus(java.util.function.BooleanSupplier abort) throws IOException {
        long fm = cur(0x500A);
        if (fm == 1 || fm == 0x8007) { sleep(60); return ""; }
        long t0 = System.currentTimeMillis(), fi = -1;
        sleep(80);
        while (System.currentTimeMillis() - t0 < 2000 && !abort.getAsBoolean()) {
            loadAllProps(1);
            fi = cur(0xD213);
            if (fi == 2 || fi == 6 || fi == 3 || fi == 7) break;
            sleep(60);
        }
        return " focus=" + (fi == 2 || fi == 6 ? "ok" : fi == 3 || fi == 7 ? "FAILED" : "timeout(" + fi + ")")
                + " in " + (System.currentTimeMillis() - t0) + "ms";
    }

    public String shoot() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("S1d=").append(L.h16(control(0xD2C1, 2, 2).code));
        sb.append(waitFocus(() -> false));
        sb.append(" S2d=").append(L.h16(control(0xD2C2, 2, 2).code));
        sleep(150);
        sb.append(" S2u=").append(L.h16(control(0xD2C2, 1, 2).code));
        sb.append(" S1u=").append(L.h16(control(0xD2C1, 1, 2).code));
        L.i("SHOOT: " + sb);
        watchForShot();
        return sb.toString();
    }

    // ---- Back-button-focus emulation ----
    // BBF mode = hold AF/MF Hold (0xD2D2) down, so the camera sits in MF: the shutter never refocuses
    // and Near/Far works. AF-ON releases the hold (camera back to its own AF-S/AF-C) and half-presses;
    // letting go of AF-ON re-engages the hold, so focus stays where AF left it.
    public volatile boolean bbf;
    private volatile boolean afOnDown;
    private static final int AFMF_HOLD = 0xD2D2;

    public synchronized String setBbf(boolean on) throws IOException {
        if (on == bbf) return "bbf already " + on;
        if (afOnDown) afOn(false);
        Ptp.Result r = control(AFMF_HOLD, on ? 2 : 1, 2);
        bbf = on;
        L.i("BBF mode " + (on ? "ON (camera held in MF)" : "OFF") + " -> " + L.h16(r.code));
        queueRefresh();
        return "bbf=" + on + " hold=" + L.h16(r.code);
    }

    /** Move the AF point (0xD232). In BBF mode the camera sits in MF, where it answers OK but ignores the move
     *  (0xD232 enable=0), so release the AF/MF hold around the set and re-hold afterwards. */
    public synchronized Ptp.Result setAfPos(int x, int y) throws IOException {
        boolean tmp = bbf && !afOnDown;
        if (tmp) { control(AFMF_HOLD, 1, 2); sleep(350); }
        try {
            return setProp(0xD232, ((long) (x & 0xFFFF) << 16) | (y & 0xFFFF), 4);
        } finally {
            if (tmp) { sleep(400); control(AFMF_HOLD, 2, 2); }
        }
    }

    public synchronized String afOn(boolean down) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (down) {
            if (afOnDown) return "af-on already down";
            afOnDown = true;
            if (bbf) { sb.append("holdUp=").append(L.h16(control(AFMF_HOLD, 1, 2).code)); sleep(350); }
            sb.append(" S1d=").append(L.h16(control(0xD2C1, 2, 2).code));
        } else {
            if (!afOnDown) return "af-on already up";
            afOnDown = false;
            sb.append("S1u=").append(L.h16(control(0xD2C1, 1, 2).code));
            if (bbf) { sleep(100); sb.append(" holdDown=").append(L.h16(control(AFMF_HOLD, 2, 2).code)); }
        }
        L.i("AF-ON " + (down ? "down" : "up") + ": " + sb);
        queueRefresh();
        return sb.toString();
    }

    /** Fire without focusing: in BBF mode the camera is already in MF; otherwise hold AF/MF just for the shot. */
    public synchronized String shutterNoAf() throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean tempHold = !bbf && !afOnDown;
        if (tempHold) { sb.append("holdDown=").append(L.h16(control(AFMF_HOLD, 2, 2).code)).append(' '); sleep(600); }
        sb.append("S1d=").append(L.h16(control(0xD2C1, 2, 2).code));
        sleep(60);
        sb.append(" S2d=").append(L.h16(control(0xD2C2, 2, 2).code));
        sleep(150);
        sb.append(" S2u=").append(L.h16(control(0xD2C2, 1, 2).code));
        if (!afOnDown) sb.append(" S1u=").append(L.h16(control(0xD2C1, 1, 2).code));
        if (tempHold) sb.append(" holdUp=").append(L.h16(control(AFMF_HOLD, 1, 2).code));
        L.i("SHUTTER(no AF) bbf=" + bbf + ": " + sb);
        watchForShot();
        return sb.toString();
    }

    // ---- Burst: hold the shutter in a continuous drive mode ----
    // Drive mode 0x5013 is settable with 0x9205 (verified 2026-11-13). Measured speeds (JPEG, ~1.9 s hold):
    // 0x8010 Hi+ 11 fps, 0x0002 Hi 8 fps, 0x8015 Mid 6 fps, 0x8012 Lo 3 fps. While S2 is held the camera fills its
    // own buffer (21 RAW in 2.2 s, 0xD215 count up to 17) and never refuses; the 2-waiting-RAW limit is single-shot only.
    // Downloads are paused while the button is held: a 0.8 s GetObject would hold the USB lock and delay S2 up
    // (~10 extra frames at Hi+). The page pings burstAlive() every 400 ms while held; if the pings stop (Wi-Fi
    // dropped, tab closed) the watchdog lets go after 1.5 s, so the camera never keeps firing on its own.
    public static final int[] CONT_DRIVES = {0x8010, 0x0002, 0x8015, 0x8012};
    public volatile boolean burstHeld;
    private volatile boolean burstStop;
    private volatile long burstDeadline;
    private boolean burstS1;
    private java.util.concurrent.ScheduledExecutorService burstDog;

    public void burstAlive() { burstDeadline = System.currentTimeMillis() + 1500; }

    public String burst(boolean down) throws IOException {
        if (!down) burstStop = true; // seen by a focus wait still running in burst(true)
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            if (down) {
                if (burstHeld) { burstAlive(); return "burst already held"; }
                burstStop = false;
                burstHeld = true;
                burstDeadline = System.currentTimeMillis() + 4000; // room for the focus wait
                startBurstDog();
                burstS1 = !afOnDown; // AF-ON held: S1 is down already, leave it to the AF button
                if (burstS1) sb.append("S1d=").append(L.h16(control(0xD2C1, 2, 2).code));
                if (!bbf && burstS1) sb.append(waitFocus(() -> burstStop));
                if (burstStop) { // let go during the focus wait: no shot
                    if (burstS1) sb.append(" S1u=").append(L.h16(control(0xD2C1, 1, 2).code));
                    burstHeld = false;
                    L.i("BURST cancelled before firing: " + sb);
                    return "cancelled " + sb;
                }
                sb.append(" S2d=").append(L.h16(control(0xD2C2, 2, 2).code));
                burstAlive();
                L.i("BURST down drive=" + L.h16((int) cur(0x5013)) + ": " + sb);
            } else {
                if (!burstHeld) return "burst not held";
                sb.append("S2u=").append(L.h16(control(0xD2C2, 1, 2).code));
                if (burstS1) sb.append(" S1u=").append(L.h16(control(0xD2C1, 1, 2).code));
                burstHeld = false;
                L.i("BURST up: " + sb);
                queueRefresh(); // downloads resume
            }
            return sb.toString();
        }
    }

    private synchronized void startBurstDog() {
        if (burstDog != null) return;
        burstDog = Executors.newSingleThreadScheduledExecutor();
        burstDog.scheduleAtFixedRate(() -> {
            if (!burstHeld || System.currentTimeMillis() < burstDeadline) return;
            L.w("BURST: no keep-alive from the page for 1.5 s, releasing the shutter");
            try { burst(false); } catch (Throwable t) { L.e("burst watchdog", t); }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    public Ptp.Result raw(int op, long[] params, byte[] out) throws IOException {
        L.i("RAW op=" + L.h16(op));
        return need().transact(op, params, out, true);
    }

    public void queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        worker.execute(() -> {
            refreshQueued.set(false);
            try { if (connected) { loadAllProps(1); drainShots(); } } catch (Throwable t) { L.e("refresh", t); }
        });
    }

    // ---- Captured-shot drain ----
    // In PC Remote mode the camera keeps every shot (also ones taken with its own shutter button) for the host,
    // flagged by 0xD215 bit15 (bits 14..0 = count). It doesn't free them by itself: with 2 RAW shots waiting it
    // refuses to focus or fire, and with Still Img. Save Dest. = PC Only (0xD222 = 1) nothing reaches the card
    // and the camera sits on "writing". Fix: download (and drop) each waiting shot via GetObjectInfo + GetObject
    // on 0xFFFFC001; GetObject releases it. The file is kept (Shots: Download/A6400 + preview JPEG). Verified 2026-11-11: 25 MB ARW in 0.9 s. GetPartialObject 0x101B
    // made the camera drop off USB, and 0x920C FreeObjectHandle isn't supported, so use the plain GetObject.
    public volatile String lastShot = "";
    public volatile int shotsDrained;

    // alphawire (pyawire download_image) waits for the ObjectAdded event 0xC201 before GetObject 0xFFFFC001 on v2
    // bodies ("otherwise the camera will reboot"); gphoto2 goes by 0xD215 alone. We drain on 0xD215 and, once the
    // camera has shown it sends 0xC201 in this session, also wait (up to 2 s) for one per shot before downloading.
    private volatile boolean seenObjectAdded;
    private final AtomicInteger objectAddedSeq = new AtomicInteger();
    private int objectAddedUsed;

    /** Download every shot the camera is holding (saved to the phone by Shots). Runs on the worker thread. */
    private void drainShots() throws IOException {
        for (int i = 0; i < 500 && connected; i++) { // a long burst can leave dozens waiting
            if (burstHeld) return; // burst() resumes the drain on release
            long fi = cur(0xD215);
            if (!shotWaiting(fi)) return; // 0xFFFF (seen after a shot that didn't fire) = no info, not a shot
            if (seenObjectAdded) {
                long end = System.currentTimeMillis() + 2000;
                while (objectAddedSeq.get() <= objectAddedUsed && System.currentTimeMillis() < end) sleep(50);
                if (objectAddedSeq.get() <= objectAddedUsed) L.w("SHOT: no ObjectAdded event within 2 s, downloading anyway");
                objectAddedUsed = Math.min(objectAddedSeq.get(), objectAddedUsed + 1);
            }
            Ptp p = need();
            Ptp.Result info = p.transact(0x1008, new long[]{0xFFFFC001L}, null, false);
            String name = "?";
            long size = -1;
            if (info.ok() && info.data != null && info.data.length >= 53) {
                size = Ptp.get32(info.data, 8);
                ByteBuffer b = ByteBuffer.wrap(info.data).order(ByteOrder.LITTLE_ENDIAN);
                b.position(52);
                name = ptpStr(b);
            }
            long t0 = System.currentTimeMillis();
            Ptp.Result r = p.transact(0x1009, new long[]{0xFFFFC001L}, null, false);
            int got = r.data == null ? 0 : r.data.length;
            shotsDrained++;
            lastShot = name;
            if (r.ok() && got > 0) Shots.get(ctx).saveAsync(name, r.data); // keep it: Download/A6400 + preview
            long ms = System.currentTimeMillis() - t0;
            if (got > 0 && ms > 0) { Diag.lastShotMBps = got / 1048576.0 / (ms / 1000.0); Diag.lastShotName = name; }
            L.i("SHOT drained " + name + " info=" + size + " got=" + got + " B in " + (System.currentTimeMillis() - t0)
                    + "ms -> " + L.h16(r.code) + " (D215 was " + L.h16((int) fi) + ")");
            if (!r.ok()) return;
            loadAllProps(1);
        }
    }

    private static boolean shotWaiting(long fi) { return fi >= 0 && fi != 0xFFFF && (fi & 0x8000) != 0; }

    // ---- Card sync (camera in USB Connection = MTP) ----
    // MTP mode (pid 0x0CA9) shows the card read-only (StorageInfo access 1, no DeleteObject), as date folders
    // ("2026-10-24") holding the files. Card names differ from PC Remote downloads ("_DSC5231.ARW" vs
    // "DSC05231.ARW"), so "already on the phone" is matched by number + type + size (Shots.key).
    public static final class CardFile { public long handle, size; public String name, captured; }
    public volatile String syncState = "idle"; // idle | scanning | syncing | done | cancelled | error: ...
    public volatile int cardPhotos = -1, cardMissing = -1, cardVideos, syncDone, syncFailed;
    public volatile long cardMissingBytes, syncBytes;
    public volatile String syncCurrent = "";
    private volatile boolean syncCancel;
    private volatile Thread syncThread;

    /** Every photo on the card (ARW / JPG / HEIF), oldest first. Videos are counted, not returned. */
    private List<CardFile> cardFiles() throws IOException {
        Ptp p = need();
        List<CardFile> out = new ArrayList<>();
        int videos = 0;
        Ptp.Result sr = p.transact(0x1004, null, null, false);
        if (!sr.ok() || sr.data == null || sr.data.length < 4) throw new IOException("GetStorageIDs -> " + L.h16(sr.code));
        int ns = (int) Ptp.get32(sr.data, 0);
        for (int si = 0; si < ns; si++) {
            long sid = Ptp.get32(sr.data, 4 + 4 * si);
            if ((sid & 0xFFFF) == 0) continue; // no logical storage (no card)
            Ptp.Result hr = p.transact(0x1007, new long[]{sid, 0, 0}, null, false);
            if (!hr.ok() || hr.data == null) throw new IOException("GetObjectHandles -> " + L.h16(hr.code));
            int nh = (int) Ptp.get32(hr.data, 0);
            for (int i = 0; i < nh; i++) {
                long h = Ptp.get32(hr.data, 4 + 4 * i);
                Ptp.Result ir = p.transact(0x1008, new long[]{h}, null, false);
                if (!ir.ok() || ir.data == null || ir.data.length < 53) continue;
                byte[] d = ir.data;
                int fmt = Ptp.get16(d, 4);
                if (fmt == 0x3001) continue; // folder
                ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                b.position(52);
                String name = ptpStr(b), captured = b.hasRemaining() ? ptpStr(b) : "";
                String n = name.toLowerCase(java.util.Locale.US);
                if (n.endsWith(".mp4") || n.endsWith(".mts") || n.endsWith(".mov")) { videos++; continue; }
                if (!(n.endsWith(".arw") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".hif") || n.endsWith(".heif"))) continue;
                CardFile f = new CardFile();
                f.handle = h; f.size = Ptp.get32(d, 8); f.name = name; f.captured = captured;
                out.add(f);
            }
        }
        cardVideos = videos;
        out.sort((a, c) -> a.captured.compareTo(c.captured));
        return out;
    }

    /** Card files not yet on the phone. Updates cardPhotos / cardMissing / cardMissingBytes. */
    private List<CardFile> cardMissing() throws IOException {
        List<CardFile> all = cardFiles();
        java.util.Set<String> have = Shots.get(ctx).originalKeys();
        List<CardFile> miss = new ArrayList<>();
        long bytes = 0;
        for (CardFile f : all) if (!have.contains(Shots.key(f.name, f.size))) { miss.add(f); bytes += f.size; }
        cardPhotos = all.size(); cardMissing = miss.size(); cardMissingBytes = bytes;
        return miss;
    }

    public synchronized String cardScan() throws IOException {
        if (!mtpMode) throw new IOException("camera is not in MTP mode (USB Connection: MTP)");
        if (syncThread != null && syncThread.isAlive()) return "sync running";
        syncState = "scanning";
        try {
            cardMissing();
            syncState = "idle";
            L.i("CARD scan: " + cardPhotos + " photos, " + cardMissing + " missing on phone (" + cardMissingBytes / 1048576 + " MB), "
                    + cardVideos + " videos skipped");
            return "ok";
        } catch (IOException e) { syncState = "error: " + e.getMessage(); throw e; }
    }

    /** Copy every card photo that isn't on the phone yet (one file in memory at a time). */
    public synchronized String cardSyncStart() throws IOException {
        if (!mtpMode) throw new IOException("camera is not in MTP mode (USB Connection: MTP)");
        if (syncThread != null && syncThread.isAlive()) return "already running";
        syncCancel = false; syncDone = 0; syncFailed = 0; syncBytes = 0; syncCurrent = "";
        syncState = "scanning";
        syncThread = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<CardFile> miss = cardMissing();
                syncState = "syncing";
                Diag.syncT0 = System.currentTimeMillis();
                L.i("CARD sync: " + miss.size() + " files, " + cardMissingBytes / 1048576 + " MB");
                for (CardFile f : miss) {
                    if (syncCancel || !connected || !mtpMode) break;
                    syncCurrent = f.name;
                    try {
                        Ptp.Result r = need().transact(0x1009, new long[]{f.handle}, null, false);
                        if (!r.ok() || r.data == null || r.data.length != f.size)
                            throw new IOException("GetObject -> " + L.h16(r.code) + " got " + (r.data == null ? 0 : r.data.length) + "/" + f.size);
                        Shots.get(ctx).saveNow(f.name, r.data);
                        syncDone++; syncBytes += f.size;
                    } catch (Throwable e) {
                        syncFailed++;
                        L.e("CARD sync " + f.name, e);
                        if (!connected) break;
                    }
                }
                syncState = syncCancel ? "cancelled" : !connected ? "error: camera disconnected" : "done";
                L.i("CARD sync " + syncState + ": " + syncDone + " copied, " + syncFailed + " failed, " + syncBytes / 1048576 + " MB in "
                        + (System.currentTimeMillis() - t0) / 1000 + " s");
                // what's left: subtract the copies (a re-read right after the writes came back stale once)
                cardMissing = Math.max(0, cardMissing - syncDone);
                cardMissingBytes = Math.max(0, cardMissingBytes - syncBytes);
            } catch (Throwable e) {
                syncState = "error: " + e.getMessage();
                L.e("CARD sync", e);
            }
            syncCurrent = "";
        }, "card-sync");
        syncThread.start();
        return "started";
    }

    public String cardSyncCancel() { syncCancel = true; return "cancelling"; }

    String cardJson() {
        return "{\"state\":" + js(syncState) + ",\"photos\":" + cardPhotos + ",\"missing\":" + cardMissing + ",\"missingBytes\":" + cardMissingBytes
                + ",\"videos\":" + cardVideos + ",\"done\":" + syncDone + ",\"failed\":" + syncFailed + ",\"bytes\":" + syncBytes
                + ",\"current\":" + js(syncCurrent) + "}";
    }

    /** After a shot the flag can rise a while later (RAW buffering): watch for it for a few seconds. */
    private void watchForShot() {
        worker.execute(() -> {
            long end = System.currentTimeMillis() + 6000;
            try {
                while (connected && System.currentTimeMillis() < end) {
                    sleep(250);
                    loadAllProps(1);
                    if (shotWaiting(cur(0xD215))) { drainShots(); return; }
                }
            } catch (Throwable t) { L.e("watchForShot", t); }
        });
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ------------------------------------------------------------------ events

    private void startEvents() {
        final Ptp p = ptp;
        if (p == null || p.intr == null) { L.w("no interrupt endpoint - events disabled"); return; }
        evtThread = new Thread(() -> {
            byte[] eb = new byte[Math.max(64, p.intr.getMaxPacketSize())];
            L.i("event thread started");
            while (connected && ptp == p) {
                try {
                    Ptp.Container c = p.readEvent(eb, 500);
                    if (c == null) continue;
                    StringBuilder sb = new StringBuilder("EVT type=").append(c.type).append(' ').append(L.h16(c.code))
                            .append(' ').append(evtName(c.code)).append(" tid=").append(c.tid);
                    for (int i = 0; i + 4 <= c.payload.length; i += 4) sb.append(" p").append(i / 4 + 1).append('=').append(L.h32(Ptp.get32(c.payload, i)));
                    L.i(sb.toString());
                    if (c.code == 0xC201) { seenObjectAdded = true; objectAddedSeq.incrementAndGet(); }
                    if (c.code == 0xC203 || c.code == 0x4006 || c.code == 0xC201) queueRefresh();
                } catch (Throwable t) {
                    L.e("event thread", t);
                    sleep(500);
                }
            }
            L.i("event thread ended");
        }, "ptp-events");
        evtThread.start();
    }

    static String evtName(int c) {
        switch (c) {
            case 0x4002: return "ObjectAdded";
            case 0x4006: return "DevicePropChanged";
            case 0x4008: return "DeviceInfoChanged";
            case 0x400D: return "CaptureComplete";
            // names from libgphoto2 ptp.h / alphawire aw-const.h / darkgrade (Sony SDIO events)
            case 0xC201: return "ObjectAdded";
            case 0xC202: return "ObjectRemoved";
            case 0xC203: return "ExtPropChanged";
            case 0xC204: return "Caution";
            case 0xC206: return "Captured";
            case 0xC207: return "FocalMarkerInfoChanged";
            case 0xC222: return "OperationResult";
            case 0xC223: return "AFStatus";
            case 0xC228: return "CautionDisplay";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ live view

    public boolean viewerActive() {
        return streamClients.get() > 0 || System.currentTimeMillis() - lastViewerMs < 3000;
    }

    private void startLiveView() {
        final Ptp p = ptp;
        lvThread = new Thread(() -> {
            long n = 0, errs = 0, notReady = 0, fpsT0 = System.currentTimeMillis(), fpsN = 0;
            L.i("live view thread started (polls only while a client is viewing)");
            while (connected && ptp == p) {
                if (!viewerActive()) { sleep(100); continue; }
                try {
                    boolean log = n < 3 || n % 300 == 0;
                    Ptp.Result r = p.transact(0x1009, new long[]{0xFFFFC002L}, null, log);
                    if (r.code == 0x200F) { // AccessDenied = next frame not ready yet (seen on A6400 USB); retry fast
                        notReady++;
                        if (notReady % 500 == 1) L.i("LV: AccessDenied (frame not ready) count=" + notReady + " - retrying fast");
                        sleep(20);
                        continue;
                    }
                    if (!r.ok() || r.data == null) {
                        errs++;
                        lvNote = "GetObject(LV) -> " + L.h16(r.code) + " " + r.codeName();
                        if (errs < 5 || errs % 50 == 0) L.w("LV: " + lvNote + " (errors=" + errs + ")");
                        sleep(errs < 5 ? 300 : 1000);
                        continue;
                    }
                    byte[] d = r.data;
                    int off = d.length >= 8 ? (int) Ptp.get32(d, 0) : -1;
                    int size = d.length >= 8 ? (int) Ptp.get32(d, 4) : -1;
                    byte[] jpg = null;
                    if (off >= 0 && size > 0 && (long) off + size <= d.length && (d[off] & 0xFF) == 0xFF && (d[off + 1] & 0xFF) == 0xD8) {
                        jpg = new byte[size];
                        System.arraycopy(d, off, jpg, 0, size);
                    } else {
                        for (int i = 0; i + 1 < d.length; i++) {
                            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8) {
                                jpg = new byte[d.length - i];
                                System.arraycopy(d, i, jpg, 0, jpg.length);
                                if (log) L.w("LV: header offset/size mismatch (off=" + off + " size=" + size + " len=" + d.length + "), JPEG found by scan at " + i);
                                break;
                            }
                        }
                    }
                    if (log) {
                        L.i("LV frame#" + n + " dataset len=" + d.length + " off=" + off + " size=" + size
                                + " head=" + L.hex(d, 0, Math.min(d.length, 48), 48));
                        if (d.length >= 16) {
                            int fio = (int) Ptp.get32(d, 8), fis = (int) Ptp.get32(d, 12);
                            if (fio > 0 && fis > 0 && (long) fio + fis <= d.length) {
                                lastFrameInfoHex = L.hex(d, fio, fis, 256);
                                L.i("LV frameInfo off=" + fio + " size=" + fis + ": " + L.hex(d, fio, fis, 96));
                            } else L.i("LV frameInfo fields: off=" + fio + " size=" + fis + " (not usable)");
                        }
                    }
                    if (jpg == null) {
                        errs++;
                        if (errs < 5 || errs % 50 == 0) L.w("LV: no JPEG in " + d.length + " bytes: " + L.hex(d, 0, d.length, 64));
                        sleep(200);
                        continue;
                    }
                    int jo = d.length - jpg.length;
                    byte[] head = new byte[Math.max(0, jo)];
                    System.arraycopy(d, 0, head, 0, head.length);
                    synchronized (lvLock) { lastJpeg = jpg; lastHead = head; frameNo++; frameMs = System.currentTimeMillis(); lvLock.notifyAll(); }
                    n++; fpsN++;
                    long now = System.currentTimeMillis();
                    if (now - fpsT0 >= 2000) { fps = fpsN * 1000f / (now - fpsT0); fpsN = 0; fpsT0 = now; lvNote = "ok"; }
                    sleep(15); // let control commands in
                } catch (Throwable t) {
                    errs++;
                    if (errs < 5 || errs % 50 == 0) L.e("LV error (errors=" + errs + ")", t);
                    sleep(500);
                }
            }
            L.i("live view thread ended");
        }, "ptp-liveview");
        lvThread.start();
    }

    public byte[] latestJpeg() { synchronized (lvLock) { return lastJpeg; } }
    public byte[] latestHead() { synchronized (lvLock) { return lastHead; } }

    /** Wait for a frame newer than `after`. Returns {frameNo} in out[0]. */
    public byte[] waitFrame(long after, long[] outNo, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (lvLock) {
            while (frameNo <= after) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                lvLock.wait(left);
            }
            outNo[0] = frameNo;
            if (outNo.length > 1) outNo[1] = frameMs;
            return frameNo > after ? lastJpeg : null;
        }
    }

    // ------------------------------------------------------------------ JSON

    static String js(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String codeArr(List<Integer> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(L.h16(l.get(i))).append('"'); }
        return sb.append(']').toString();
    }

    private String propJson(Prop p) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"code\":\"").append(L.h16(p.code)).append("\",\"name\":").append(js(propName(p.code)))
                .append(",\"control\":").append(p.isControl).append(",\"dataType\":").append(p.dataType)
                .append(",\"getSet\":").append(p.getSet).append(",\"isEnable\":").append(p.isEnable);
        if (p.scur != null) sb.append(",\"cur\":").append(js(p.scur)).append(",\"def\":").append(js(p.sdef));
        else sb.append(",\"cur\":").append(p.cur).append(",\"curHex\":\"").append(Long.toHexString(p.cur)).append("\",\"def\":").append(p.def);
        sb.append(",\"form\":").append(p.formFlag);
        if (p.formFlag == 1) sb.append(",\"min\":").append(p.min).append(",\"max\":").append(p.max).append(",\"step\":").append(p.step);
        if (p.formFlag == 2) sb.append(",\"settable\":").append(longArr(p.settable)).append(",\"available\":").append(longArr(p.available));
        return sb.append('}').toString();
    }

    private static String longArr(List<Long> l) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); sb.append(l.get(i)); }
        return sb.append(']').toString();
    }

    public String infoJson() {
        StringBuilder sb = new StringBuilder("{");
        synchronized (props) {
            sb.append("\"connected\":").append(connected).append(",\"status\":").append(js(status))
                    .append(",\"manufacturer\":").append(js(manufacturer)).append(",\"model\":").append(js(model))
                    .append(",\"version\":").append(js(devVersion)).append(",\"serial\":").append(js(serial))
                    .append(",\"vendorExtId\":\"").append(L.h32(vendorExtId)).append('"')
                    .append(",\"extVersion\":").append(extVersion).append(",\"extInfoParam\":").append(extParam)
                    .append(",\"ptpOps\":").append(codeArr(ptpOps)).append(",\"ptpEvents\":").append(codeArr(ptpEvents))
                    .append(",\"ptpProps\":").append(codeArr(ptpProps))
                    .append(",\"extProps\":").append(codeArr(extProps)).append(",\"extCtrls\":").append(codeArr(extCtrls));
            sb.append(",\"watch\":{");
            int k = 0;
            for (int[] arr : new int[][]{WATCH_CTRLS, WATCH_PROPS}) {
                for (int c : arr) {
                    if (k++ > 0) sb.append(',');
                    sb.append("\"").append(L.h16(c)).append("\":{\"name\":").append(js(propName(c)))
                            .append(",\"inExtCtrls\":").append(extCtrls.contains(c)).append(",\"inExtProps\":").append(extProps.contains(c))
                            .append(",\"in9209\":").append(props.containsKey(c)).append('}');
                }
            }
            sb.append("},\"propParse\":").append(js(propParseNote)).append(",\"lvNote\":").append(js(lvNote))
                    .append(",\"lastFrameInfoHex\":").append(js(lastFrameInfoHex));
            sb.append(",\"props\":[");
            k = 0;
            for (Prop p : props.values()) { if (k++ > 0) sb.append(','); sb.append(propJson(p)); }
            sb.append("],\"rawPropsHex\":").append(js(rawPropsHex));
        }
        return sb.append('}').toString();
    }

    public String statusJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"connected\":").append(connected).append(",\"status\":").append(js(status))
                .append(",\"model\":").append(js(model)).append(",\"fps\":").append(String.format(java.util.Locale.US, "%.1f", fps))
                .append(",\"lv\":").append(js(lvNote)).append(",\"viewers\":").append(streamClients.get())
                .append(",\"logSeq\":").append(L.seq()).append(",\"bbf\":").append(bbf).append(",\"burst\":").append(burstHeld).append(",\"mtp\":").append(mtpMode).append(",\"card\":").append(cardJson()).append(",\"lastShot\":").append(js(lastShot)).append(",\"shots\":").append(shotsDrained).append(",\"saved\":").append(Shots.get(ctx).saved).append(",\"recoveries\":").append(recoveries).append(",\"lastRecovery\":").append(js(lastRecovery)).append(",\"props\":{");
        int k = 0;
        synchronized (props) {
            for (int c : STATUS_PROPS) {
                Prop p = props.get(c);
                if (p == null) continue;
                if (k++ > 0) sb.append(',');
                sb.append("\"").append(L.h16(c)).append("\":{\"n\":").append(js(propName(c))).append(",\"v\":")
                        .append(p.scur != null ? js(p.scur) : js(fmtVal(p.cur, p.dataType))).append(",\"en\":").append(p.isEnable).append('}');
            }
        }
        return sb.append("}}").toString();
    }
}
