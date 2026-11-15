package com.simplealphadroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.nayuki.qrcodegen.QrCode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Phone-1 screen. Middle: "Start Wi-Fi for the second device" (the app's own local-only hotspot, only on a tap: it can disturb
 * phone 1's own Wi-Fi), then two QR codes (join the Wi-Fi, open the remote). Under it "Other ways to connect": every
 * address phone 1 already has (Wi-Fi, the phone's own hotspot, VPN), each with a QR. Below: photo folder, PIN,
 * camera setup checklist, Stop. First start walks through camera settings, battery exemption and the photo folder.
 */
public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final int REQ_FOLDER = 7, REQ_BATTERY = 8, REQ_WIFI_PERM = 9;
    private static final int BG = Color.rgb(18, 18, 18), CARD = Color.rgb(32, 32, 36), DIM = Color.rgb(150, 150, 158),
            ACC = Color.rgb(77, 163, 255), OK = Color.rgb(61, 220, 132), WARN = Color.rgb(255, 176, 32),
            STOP = Color.rgb(211, 47, 47), BTN = Color.rgb(58, 58, 64);

    private final Handler h = new Handler(Looper.getMainLooper());
    private SonyCam cam;
    private SharedPreferences prefs;
    private TextView statusV, wifiInfo, step2, urlV, otherHead, otherQrText, mainText, mainUrl;
    private ImageView qr1, qr2, otherQr, mainQr;
    private Button wifiBtn, folderBtn, pinBtn, logBtn, hotspotBtn;
    private String shownMainQr = "";
    private LinearLayout wifiOn, otherList, otherBox;
    private TextView logV;
    private boolean otherOpen, logOpen;
    private String shownQr1 = "", shownQr2 = "", otherSel = null, otherSig = "";
    private long shownSeq = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.i("MainActivity onCreate");
        prefs = getSharedPreferences("ui", MODE_PRIVATE);
        Lang.init(this);
        KeepAlive.screen = new java.lang.ref.WeakReference<>(this);
        cam = SonyCam.get(this);
        Web.start(this, PORT);
        cam.startAutoConnect();
        try { startForegroundService(new Intent(this, KeepAlive.class)); } catch (Exception e) { L.e("start KeepAlive", e); }
        buildUi();
        handleIntent(getIntent());
        h.post(tick);
        if (!prefs.getBoolean("setupDone", false)) showSetup(true);
    }

    // ------------------------------------------------------------------ layout

    private static String t(String en, String vi) { return Lang.t(en, vi); }

    /** Switch Vietnamese <-> English: rebuild the screen and the notification in the new language. */
    private void switchLang() {
        Lang.set(this, !Lang.vi);
        shownQr1 = shownQr2 = shownMainQr = otherSig = "";
        shownSeq = -1;
        logOpen = false;
        buildUi();
        try { startForegroundService(new Intent(this, KeepAlive.class)); } catch (Exception e) { L.e("refresh notification", e); }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(root);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
        });

        root.addView(tv("SimpleAlphaDroid", 22, Color.WHITE, true));
        statusV = tv("", 15, OK, false);
        root.addView(statusV);

        // --- connect the second device ---
        LinearLayout card = card();
        root.addView(card, lp(dp(14)));
        card.addView(tv(t("Connect a second device", "Kết nối thiết bị thứ hai"), 17, Color.WHITE, true));
        // default: the phone's own hotspot (password-protected, set up once, the second device rejoins by itself)
        mainText = tv("", 15, Color.WHITE, false);
        card.addView(mainText, lp(dp(8)));
        mainUrl = tv("", 24, ACC, true);
        mainUrl.setGravity(Gravity.CENTER);
        mainUrl.setTextIsSelectable(true);
        card.addView(mainUrl, lp(dp(10)));
        mainQr = qrView();
        card.addView(mainQr, qrLp());
        hotspotBtn = new Button(this);
        style(hotspotBtn, BTN);
        hotspotBtn.setAllCaps(false);
        hotspotBtn.setTextSize(18);
        hotspotBtn.setMinHeight(dp(64));
        hotspotBtn.setText(t("Open hotspot settings", "Mở cài đặt điểm phát sóng"));
        hotspotBtn.setOnClickListener(v -> openHotspotSettings());
        card.addView(hotspotBtn, lp(dp(10)));

        // the app's own Wi-Fi: built here, placed inside "Other ways" below
        wifiBtn = new Button(this);
        style(wifiBtn, BTN);
        wifiBtn.setAllCaps(false);
        wifiBtn.setTextSize(18);
        wifiBtn.setMinHeight(dp(64));
        wifiBtn.setOnClickListener(v -> toggleWifi());
        wifiInfo = tv("", 13, DIM, false);

        wifiOn = new LinearLayout(this);
        wifiOn.setOrientation(LinearLayout.VERTICAL);
        wifiOn.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView step1 = tv(t("① On the second device, scan with the camera to join the Wi-Fi",
                "① Trên thiết bị thứ hai, quét mã bằng camera để vào Wi-Fi"), 15, Color.WHITE, true);
        qr1 = qrView();
        step2 = tv(t("② Then scan this to open the remote", "② Sau đó quét mã này để mở trang điều khiển"), 15, Color.WHITE, true);
        qr2 = qrView();
        urlV = tv("", 15, ACC, false);
        urlV.setTextIsSelectable(true);
        urlV.setGravity(Gravity.CENTER);
        wifiOn.addView(step1, lp(dp(12)));
        wifiOn.addView(qr1, qrLp());
        wifiOn.addView(step2, lp(dp(14)));
        wifiOn.addView(qr2, qrLp());
        wifiOn.addView(urlV, lp(dp(6)));

        otherHead = tv("", 15, ACC, false);
        otherHead.setPadding(0, dp(16), 0, dp(4));
        otherHead.setOnClickListener(v -> { otherOpen = !otherOpen; otherSig = ""; renderOther(); });
        card.addView(otherHead);
        otherBox = new LinearLayout(this);
        otherBox.setOrientation(LinearLayout.VERTICAL);
        otherBox.addView(tv(t("Second device on the same network as phone 1 (home Wi-Fi, or a VPN like Tailscale): "
                + "open one of these. Tap one to show its QR.",
                "Thiết bị thứ hai ở cùng mạng với điện thoại chính (Wi-Fi nhà, hoặc VPN như Tailscale): "
                + "mở một trong các địa chỉ này. Chạm vào một địa chỉ để hiện mã QR."), 13, DIM, false));
        otherList = new LinearLayout(this);
        otherList.setOrientation(LinearLayout.VERTICAL);
        otherBox.addView(otherList);
        otherQr = qrView();
        otherQrText = tv("", 14, ACC, false);
        otherQrText.setGravity(Gravity.CENTER);
        otherBox.addView(otherQr, qrLp());
        otherBox.addView(otherQrText);
        otherBox.addView(tv(t("No hotspot? Let the app create a Wi-Fi", "Không có điểm phát sóng? Để ứng dụng tự tạo Wi-Fi"),
                15, Color.WHITE, true), lp(dp(18)));
        otherBox.addView(wifiBtn, lp(dp(6)));
        otherBox.addView(wifiInfo, lp(dp(4)));
        otherBox.addView(wifiOn);
        card.addView(otherBox);

        // --- settings ---
        LinearLayout set = card();
        root.addView(set, lp(dp(14)));
        set.addView(button(t("Language: English  ·  Tiếng Việt", "Ngôn ngữ: Tiếng Việt  ·  English"), v -> switchLang()), lp(0));
        folderBtn = button("", v -> pickFolder());
        set.addView(folderBtn, lp(dp(8)));
        pinBtn = button("", v -> pinDialog());
        set.addView(pinBtn, lp(dp(8)));
        set.addView(button(t("Camera setup checklist", "Danh sách cài đặt máy ảnh"), v -> showSetup(false)), lp(dp(8)));
        Button stopBtn = button(t("Stop app", "Dừng ứng dụng"), v -> new AlertDialog.Builder(this)
                .setMessage(t("Disconnect the camera, turn off the app's Wi-Fi and stop the remote? Plugging the camera in starts it again.",
                        "Ngắt kết nối máy ảnh, tắt Wi-Fi của ứng dụng và dừng điều khiển từ xa? Cắm máy ảnh vào sẽ chạy lại."))
                .setPositiveButton(t("Stop", "Dừng"), (d, w) -> KeepAlive.shutdown(this))
                .setNegativeButton(t("Cancel", "Hủy"), null).show());
        style(stopBtn, STOP); // red so it stands out
        set.addView(stopBtn, lp(dp(8)));
        logBtn = button(t("Show log", "Hiện nhật ký"), v -> {
            logOpen = !logOpen; shownSeq = -1;
            logBtn.setText(logOpen ? t("Hide log", "Ẩn nhật ký") : t("Show log", "Hiện nhật ký"));
            logV.setVisibility(logOpen ? View.VISIBLE : View.GONE);
        });
        set.addView(logBtn, lp(dp(8)));
        logV = tv("", 10, Color.rgb(200, 200, 200), false);
        logV.setTypeface(Typeface.MONOSPACE);
        logV.setTextIsSelectable(true);
        logV.setVisibility(View.GONE);
        root.addView(logV, lp(dp(8)));

        setContentView(sv);
        renderMain();
        renderWifi();
        renderOther();
        updateFolderBtn();
        updatePinBtn();
    }

    // ------------------------------------------------------------------ app's own Wi-Fi

    private void toggleWifi() {
        if ("on".equals(Hotspot.state) || "starting".equals(Hotspot.state)) { Hotspot.stop(); renderWifi(); return; }
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle(t("Allow Wi-Fi access", "Cho phép truy cập Wi-Fi"))
                    .setMessage(Build.VERSION.SDK_INT >= 33
                            ? t("Android asks for \"Nearby devices\" before an app can create a Wi-Fi network. It is only used for the network the second device joins.",
                                "Android cần quyền \"Thiết bị ở gần\" để ứng dụng tạo mạng Wi-Fi. Quyền này chỉ dùng cho mạng mà thiết bị thứ hai kết nối vào.")
                            : t("On this Android version, creating a Wi-Fi network needs the location permission (and location turned on). The app doesn't use your location.",
                                "Trên phiên bản Android này, tạo mạng Wi-Fi cần quyền vị trí (và bật Vị trí). Ứng dụng không dùng vị trí của bạn."))
                    .setPositiveButton(t("Continue", "Tiếp tục"), (d, w) -> requestPermissions(new String[]{perm}, REQ_WIFI_PERM))
                    .setNegativeButton(t("Cancel", "Hủy"), null).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 33) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                new AlertDialog.Builder(this).setMessage(t("Turn on Location first: Android needs it to create a Wi-Fi network on this version.",
                                "Hãy bật Vị trí trước: phiên bản Android này cần nó để tạo mạng Wi-Fi."))
                        .setPositiveButton(t("Open settings", "Mở cài đặt"), (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton(t("Cancel", "Hủy"), null).show();
                return;
            }
        }
        Hotspot.start(this, () -> h.post(() -> {
            shownQr1 = "";
            renderWifi();
            renderMain();
            if ("failed".equals(Hotspot.state)) { otherOpen = true; otherSig = ""; renderOther(); }
        }));
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_WIFI_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) toggleWifi();
            else wifiInfo.setText(t("Wi-Fi permission denied. You can still use Other ways to connect.",
                    "Quyền Wi-Fi bị từ chối. Bạn vẫn có thể dùng Cách kết nối khác."));
        }
    }

    /** Address of the app's Wi-Fi: the hotspot-kind address (the phone's own hotspot can't be on at the same time). */
    private static String hotspotIp() {
        for (Diag.Addr a : Diag.addresses()) if (a.kind.equals("hotspot")) return a.ip;
        return null;
    }

    private void renderWifi() {
        String st = Hotspot.state;
        boolean on = "on".equals(st);
        wifiBtn.setText(on ? t("Stop the app's Wi-Fi", "Tắt Wi-Fi của ứng dụng")
                : "starting".equals(st) ? t("Starting Wi-Fi…", "Đang bật Wi-Fi…")
                : t("Start Wi-Fi for the second device", "Bật Wi-Fi cho thiết bị thứ hai"));
        wifiBtn.setEnabled(!"starting".equals(st));
        if (on) {
            wifiInfo.setTextColor(DIM);
            wifiInfo.setText("Wi-Fi \"" + Hotspot.ssid + "\"   " + t("password", "mật khẩu") + ": " + Hotspot.pass
                    + t("\nNo internet on this Wi-Fi: if the second device asks, choose \"Stay connected\".",
                        "\nWi-Fi này không có internet: nếu thiết bị thứ hai hỏi, chọn \"Vẫn kết nối\"."));
        } else if ("failed".equals(st)) {
            wifiInfo.setTextColor(WARN);
            wifiInfo.setText(Hotspot.error);
        } else {
            wifiInfo.setTextColor(DIM);
            wifiInfo.setText(t("No setup, but a new name and password every time (2 scans on the second device), usually 2.4 GHz, no internet. Phone 1's own Wi-Fi may disconnect while it is on.",
                    "Không cần cài đặt, nhưng mỗi lần có tên và mật khẩu mới (quét 2 mã trên thiết bị thứ hai), thường là 2.4 GHz, không có internet. Wi-Fi của điện thoại chính có thể bị ngắt khi bật."));
        }
        wifiOn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        String j = Hotspot.wifiQr();
        if (!j.equals(shownQr1)) { qr1.setImageBitmap(qr(j)); shownQr1 = j; }
        String ip = hotspotIp();
        String url = ip == null ? null : "http://" + ip + ":" + PORT + "/";
        step2.setVisibility(url == null ? View.GONE : View.VISIBLE);
        qr2.setVisibility(url == null ? View.GONE : View.VISIBLE);
        urlV.setText(url == null ? t("Waiting for the Wi-Fi address…", "Đang chờ địa chỉ Wi-Fi…") : url);
        if (url != null && !url.equals(shownQr2)) { qr2.setImageBitmap(qr(url)); shownQr2 = url; }
    }

    // ------------------------------------------------------------------ default: the phone's own hotspot

    private void openHotspotSettings() {
        // TETHER_SETTINGS is a hidden action most brands support; fall back to the network settings page
        for (Intent i : new Intent[]{new Intent("android.settings.TETHER_SETTINGS"), new Intent(Settings.ACTION_WIRELESS_SETTINGS)}) {
            try { startActivity(i); return; } catch (Exception ignored) {}
        }
    }

    private void renderMain() {
        boolean appWifi = "on".equals(Hotspot.state);
        String ip = appWifi ? null : hotspotIp(); // a hotspot address while the app's Wi-Fi is off = the phone's own hotspot
        if (ip != null) {
            String url = "http://" + ip + ":" + PORT + "/";
            mainText.setText(t("This phone's hotspot is on. On the second device: join it, then open (or scan):",
                    "Điểm phát sóng của điện thoại này đang bật. Trên thiết bị thứ hai: kết nối vào, rồi mở (hoặc quét mã):"));
            mainUrl.setText(ip + ":" + PORT);
            mainUrl.setVisibility(View.VISIBLE);
            mainQr.setVisibility(View.VISIBLE);
            if (!url.equals(shownMainQr)) { mainQr.setImageBitmap(qr(url)); shownMainQr = url; }
            hotspotBtn.setVisibility(View.GONE);
        } else {
            mainText.setText(appWifi ? t("Using the app's own Wi-Fi: see below.", "Đang dùng Wi-Fi riêng của ứng dụng: xem bên dưới.")
                    : t("Turn on this phone's hotspot. The second device joins it with your hotspot password once and reconnects "
                    + "by itself next time. Tip: set the hotspot band to 5 GHz for smoother live view.",
                    "Bật điểm phát sóng của điện thoại này. Thiết bị thứ hai nhập mật khẩu điểm phát sóng một lần, lần sau "
                    + "tự kết nối lại. Mẹo: chọn băng tần 5 GHz để xem trực tiếp mượt hơn."));
            mainUrl.setVisibility(View.GONE);
            mainQr.setVisibility(View.GONE);
            hotspotBtn.setVisibility(appWifi ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ other ways

    private void renderOther() {
        List<Diag.Addr> addrs = Diag.addresses();
        StringBuilder sig = new StringBuilder(String.valueOf(otherOpen)).append(otherSel);
        for (Diag.Addr a : addrs) sig.append(a.ip).append(a.kind);
        if (sig.toString().equals(otherSig)) return;
        otherSig = sig.toString();
        otherHead.setText((otherOpen ? "▾ " : "▸ ") + t("Other ways to connect", "Cách kết nối khác"));
        otherBox.setVisibility(otherOpen ? View.VISIBLE : View.GONE);
        otherList.removeAllViews();
        boolean selFound = false;
        addrs.removeIf(a -> a.kind.equals("hotspot")); // shown in the main card / the app's Wi-Fi section
        for (Diag.Addr a : addrs) {
            String url = "http://" + a.ip + ":" + PORT + "/";
            boolean sel = url.equals(otherSel);
            TextView t = tv(Diag.label(a.kind) + "\n" + url, 14, sel ? ACC : Color.WHITE, false);
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setStroke(dp(1), sel ? ACC : Color.rgb(60, 60, 66));
            t.setBackground(g);
            t.setOnClickListener(v -> { otherSel = url; otherSig = ""; renderOther(); });
            otherList.addView(t, lp(dp(8)));
            if (sel) selFound = true;
        }
        if (addrs.isEmpty())
            otherList.addView(tv(t("Phone 1 is on no other network right now.", "Điện thoại chính hiện không ở mạng nào khác."),
                    14, DIM, false), lp(dp(8)));
        if (!selFound) otherSel = null;
        otherQr.setVisibility(otherSel == null ? View.GONE : View.VISIBLE);
        otherQrText.setText(otherSel == null ? "" : otherSel);
        if (otherSel != null) otherQr.setImageBitmap(qr(otherSel));
    }

    // ------------------------------------------------------------------ QR

    private Bitmap qr(String text) {
        QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 3, n = q.size + 2 * border;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                px[y * n + x] = q.getModule(x - border, y - border) ? Color.BLACK : Color.WHITE;
        Bitmap small = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(small, n * 12, n * 12, false);
    }

    private ImageView qrView() {
        ImageView v = new ImageView(this);
        v.setAdjustViewBounds(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    private LinearLayout.LayoutParams qrLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(230), dp(230));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = dp(8);
        return p;
    }

    // ------------------------------------------------------------------ PIN

    private void updatePinBtn() {
        pinBtn.setText(Auth.on() ? t("PIN for the remote: ", "Mã PIN điều khiển: ") + Auth.pin() + t("  (on)", "  (bật)")
                : t("PIN for the remote: off", "Mã PIN điều khiển: tắt"));
    }

    private void pinDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(t("PIN for the remote", "Mã PIN điều khiển"));
        if (Auth.on()) {
            b.setMessage(t("The second device must enter " + Auth.pin() + " once before it can use the remote.",
                            "Thiết bị thứ hai phải nhập " + Auth.pin() + " một lần trước khi dùng điều khiển."))
                    .setPositiveButton(t("Turn off", "Tắt"), (d, w) -> { Auth.setOn(false); updatePinBtn(); })
                    .setNeutralButton(t("New PIN", "PIN mới"), (d, w) -> { Auth.newPin(); updatePinBtn(); })
                    .setNegativeButton(t("Close", "Đóng"), null);
        } else {
            b.setMessage(t("Off: anyone on the same network can open the remote. On: the remote page asks for a 4-digit PIN shown here.",
                            "Tắt: ai ở cùng mạng cũng mở được trang điều khiển. Bật: trang điều khiển hỏi mã PIN 4 số hiện ở đây."))
                    .setPositiveButton(t("Turn on", "Bật"), (d, w) -> { Auth.setOn(true); updatePinBtn(); })
                    .setNegativeButton(t("Close", "Đóng"), null);
        }
        b.show();
    }

    // ------------------------------------------------------------------ first-run setup

    /** Adds a language switch to a guide dialog: it switches EN/VI and reopens the same step in the new language. */
    private AlertDialog.Builder withLang(AlertDialog.Builder b, Runnable reopen) {
        return b.setNeutralButton(t("🌐 Tiếng Việt", "🌐 English"), (d, w) -> { switchLang(); reopen.run(); });
    }

    private void showSetup(boolean firstRun) {
        // camera menu names stay in English: that is how they read on the camera
        withLang(new AlertDialog.Builder(this), () -> showSetup(firstRun)).setTitle(t("Camera settings", "Cài đặt máy ảnh"))
                .setMessage(t("On the A6400 (MENU):\n\n", "Trên A6400 (MENU):\n\n")
                        + "• Setup › USB Connection: PC Remote\n"
                        + "• Network › Ctrl w/ Smartphone: Off\n"
                        + "• Focus › AF w/ shutter: On\n"
                        + "• Setup › PC Remote Settings › Still Img. Save Dest.: PC+Camera\n"
                        + t("• Setup › USB Power Supply: Off (if shown)\n", "• Setup › USB Power Supply: Off (nếu có)\n")
                        + t("• Focus Area: Flexible Spot (for tap-to-focus)\n\n", "• Focus Area: Flexible Spot (để chạm lấy nét)\n\n")
                        + t("Then plug the camera into this phone with the USB cable.", "Sau đó cắm máy ảnh vào điện thoại này bằng cáp USB."))
                .setPositiveButton(firstRun ? t("Next", "Tiếp") : "OK", (d, w) -> { if (firstRun) setupBattery(); })
                .setCancelable(!firstRun)
                .show();
    }

    private void setupBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) { setupFolder(); return; }
        withLang(new AlertDialog.Builder(this), this::setupBattery).setTitle(t("Keep running with the screen off", "Chạy tiếp khi tắt màn hình"))
                .setMessage(t("Android stops apps in the background to save battery. Allow this app to run unrestricted, "
                        + "or the remote stops a few minutes after phone 1's screen turns off.",
                        "Android dừng ứng dụng chạy nền để tiết kiệm pin. Hãy cho phép ứng dụng này chạy không giới hạn, "
                        + "nếu không điều khiển sẽ dừng vài phút sau khi màn hình điện thoại chính tắt."))
                .setPositiveButton(t("Allow", "Cho phép"), (d, w) -> {
                    try {
                        startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())), REQ_BATTERY);
                    } catch (Exception e) { L.e("battery exemption", e); setupFolder(); }
                })
                .setNegativeButton(t("Skip", "Bỏ qua"), (d, w) -> setupFolder())
                .setCancelable(false).show();
    }

    private void setupFolder() {
        prefs.edit().putBoolean("setupDone", true).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); // shows the Stop control
        if (Shots.get(this).tree() == null) askForFolder();
    }

    // ------------------------------------------------------------------ photo folder (SAF)

    private void updateFolderBtn() { folderBtn.setText(t("Photo folder: ", "Thư mục ảnh: ") + Shots.get(this).folderLabel()); }

    private void askForFolder() {
        withLang(new AlertDialog.Builder(this), this::askForFolder)
                .setTitle(t("Where to save photos?", "Lưu ảnh ở đâu?"))
                .setMessage(t("Every shot is copied from the camera to this phone. Pick a folder for them, e.g. create a "
                        + "folder \"A6400\" at the top of internal storage (a .nomedia file keeps it out of galleries "
                        + "and OneDrive). Until then shots go to Download/A6400.",
                        "Mỗi ảnh chụp được chép từ máy ảnh sang điện thoại này. Hãy chọn thư mục lưu, vd. tạo thư mục "
                        + "\"A6400\" ở gốc bộ nhớ trong (tệp .nomedia giữ nó khỏi thư viện ảnh và OneDrive). "
                        + "Trước khi chọn, ảnh được lưu vào Download/A6400."))
                .setPositiveButton(t("Choose folder", "Chọn thư mục"), (d, w) -> pickFolder())
                .setNegativeButton(t("Later", "Để sau"), null)
                .show();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri cur = Shots.get(this).tree();
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, cur != null
                ? DocumentsContract.buildDocumentUriUsingTree(cur, DocumentsContract.getTreeDocumentId(cur))
                : DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:A6400"));
        try { startActivityForResult(i, REQ_FOLDER); } catch (Exception e) { L.e("folder picker", e); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BATTERY) { setupFolder(); return; }
        if (req != REQ_FOLDER || res != RESULT_OK || data == null || data.getData() == null) return;
        try { Shots.get(this).setTree(data.getData()); } catch (Exception e) { L.e("set folder", e); }
        updateFolderBtn();
    }

    // ------------------------------------------------------------------ USB attach

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            L.i("launched by USB_DEVICE_ATTACHED: " + (d == null ? "null" : d.getDeviceName()));
            if (d != null) {
                UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
                if (um.hasPermission(d)) cam.connectAsync(d);
                else new Thread(() -> cam.scanAndConnect()).start();
            }
        }
    }

    // ------------------------------------------------------------------ refresh

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean viewer = cam.viewerActive();
            String camS = !cam.connected ? t("Camera: not connected (plug it in, camera on)", "Máy ảnh: chưa kết nối (cắm cáp, bật máy)")
                    : cam.mtpMode ? t("Camera: card mode (MTP)", "Máy ảnh: chế độ thẻ nhớ (MTP)") : t("Camera: connected ✓", "Máy ảnh: đã kết nối ✓");
            statusV.setText(camS + (cam.connected && !cam.mtpMode ? t("   ·   second device: ", "   ·   thiết bị thứ hai: ")
                    + (viewer ? t("watching ✓", "đang xem ✓") : t("not connected", "chưa kết nối")) : ""));
            statusV.setTextColor(cam.connected ? OK : WARN);
            String be = Web.bindError;
            if (be != null) { // the remote can't be opened at all until this clears
                statusV.append(t("\n⚠ Remote not available: " + be + ". Another app may be using it; close it (retrying).",
                        "\n⚠ Chưa mở được điều khiển từ xa: " + be + ". Có thể ứng dụng khác đang dùng cổng này; hãy đóng nó (đang thử lại)."));
                statusV.setTextColor(WARN);
            }
            renderMain();
            renderWifi();
            renderOther();
            if (logOpen) {
                long s = L.seq();
                if (s != shownSeq) { shownSeq = s; logV.setText(L.tail(120)); }
            }
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    static String wifiIp() {
        try {
            String any = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (any == null) any = a.getHostAddress();
                }
            }
            return any;
        } catch (Exception e) { return null; }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        return c;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(l);
        style(b, BTN);
        return b;
    }

    /** Our own button look, so it doesn't depend on the phone maker's theme (Samsung draws touching pills with no
     *  gap, OPPO grey boxes with a gap): rounded rectangle, ripple on tap, no shadow, white text. */
    private void style(Button b, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), g, null));
        b.setStateListAnimator(null);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
}
