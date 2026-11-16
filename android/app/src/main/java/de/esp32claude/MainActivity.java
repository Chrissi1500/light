package de.esp32claude;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ExecutorService espQueue = Executors.newSingleThreadExecutor();
    private BleLink ble;
    private UsbLink usb;
    private final ClaudeAgent agent = new ClaudeAgent();
    private volatile String transport = "wifi";
    private volatile String wifiHost = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);

        ble = new BleLink(this, new BleLink.Listener() {
            @Override public void onDevice(String name, String address, int rssi) {
                emit("bleDevice", obj("name", name, "address", address, "rssi", rssi));
            }
            @Override public void onState(String state, String detail) {
                emit("bleState", obj("state", state, "detail", detail));
            }
        });
        usb = new UsbLink(this);
        IntentFilter f = new IntentFilter(UsbLink.ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPerm, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbPerm, f);

        web.loadUrl("file:///android_asset/www/index.html");
    }

    private final BroadcastReceiver usbPerm = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            pool.execute(MainActivity.this::openUsb);
        }
    };

    private void openUsb() {
        String err = usb.open();
        if (err == null) emit("usbState", obj("state", "connected", "detail", usb.describe()));
        else if (!"PERMISSION".equals(err)) emit("usbState", obj("state", "error", "detail", err));
        else emit("usbState", obj("state", "permission", "detail", "Bitte USB-Zugriff erlauben"));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction()))
            emit("usbAttached", new JSONObject());
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbPerm); } catch (Exception ignored) { }
        ble.disconnect();
        usb.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (Exception ignored) { }
        return o;
    }

    void emit(String type, JSONObject data) {
        String js = "window.onNative(" + JSONObject.quote(type) + "," + JSONObject.quote(data.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    void reply(String id, JSONObject data) {
        String js = "window.onReply(" + JSONObject.quote(id) + "," + JSONObject.quote(data.toString()) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    EspLink currentLink() throws Exception {
        switch (transport) {
            case "ble":
                if (!ble.isReady()) throw new Exception("Bluetooth nicht verbunden");
                return ble;
            case "usb":
                if (!usb.isOpen()) throw new Exception("USB nicht verbunden");
                return usb;
            default:
                if (wifiHost.isEmpty()) throw new Exception("Keine ESP32-IP eingetragen");
                return new WifiLink(this, wifiHost);
        }
    }

    /** Alle ESP-Zugriffe nacheinander (USB/BLE vertragen keine parallelen Befehle). */
    EspLink serialLink(EspLink inner) {
        return new EspLink() {
            @Override public JSONObject call(JSONObject cmd, int t) throws Exception {
                return espQueue.submit(() -> inner.call(cmd, t)).get();
            }
            @Override public String describe() { return inner.describe(); }
        };
    }

    boolean ensurePerms(String... perms) {
        boolean all = true;
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
        if (!all) requestPermissions(perms, 1);
        return all;
    }

    String asset(String path) throws Exception {
        try (InputStream is = getAssets().open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        }
    }

    class Bridge {
        @JavascriptInterface
        public void setTarget(String t, String host) {
            transport = t;
            wifiHost = host == null ? "" : host;
        }

        @JavascriptInterface
        public void send(String id, String cmdJson, int timeoutMs) {
            pool.execute(() -> {
                try {
                    JSONObject r = serialLink(currentLink()).call(new JSONObject(cmdJson), timeoutMs);
                    reply(id, r);
                } catch (Exception e) {
                    reply(id, obj("ok", false, "err", String.valueOf(e.getMessage())));
                }
            });
        }

        @JavascriptInterface
        public void discover(String id) {
            pool.execute(() -> {
                try {
                    reply(id, obj("ok", true, "devices", WifiLink.discover(MainActivity.this, 1500)));
                } catch (Exception e) {
                    reply(id, obj("ok", false, "err", String.valueOf(e.getMessage())));
                }
            });
        }

        @JavascriptInterface
        public void bleScan() {
            String[] p = Build.VERSION.SDK_INT >= 31
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            if (!ensurePerms(p)) {
                emit("bleState", obj("state", "error", "detail", "Bitte Bluetooth-Berechtigung erlauben und erneut scannen"));
                return;
            }
            runOnUiThread(() -> ble.scan());
        }

        @JavascriptInterface
        public void bleConnect(String address) { runOnUiThread(() -> ble.connect(address)); }

        @JavascriptInterface
        public void bleDisconnect() { runOnUiThread(() -> ble.disconnect()); }

        @JavascriptInterface
        public void usbConnect() { pool.execute(MainActivity.this::openUsb); }

        @JavascriptInterface
        public void usbDisconnect() {
            espQueue.execute(() -> {
                usb.close();
                emit("usbState", obj("state", "disconnected", "detail", ""));
            });
        }

        /** Installiert agent.py + main.py (+ optional WLAN-Daten) per USB auf einen frischen MicroPython-ESP32. */
        @JavascriptInterface
        public void usbInstall(String id, String ssid, String password, String name) {
            espQueue.execute(() -> {
                try {
                    if (!usb.isOpen()) throw new Exception("USB nicht verbunden");
                    emit("progress", obj("text", "Pruefe MicroPython ..."));
                    String[] v = usb.exec("import sys\nprint(sys.implementation.name, sys.platform)", 5000);
                    if (!v[0].contains("micropython"))
                        throw new Exception("Auf dem ESP32 laeuft kein MicroPython. Erst MicroPython flashen (siehe Anleitung).");
                    emit("progress", obj("text", "Schreibe agent.py ..."));
                    usb.writeFile("agent.py", asset("esp32/agent.py").getBytes("UTF-8"));
                    emit("progress", obj("text", "Schreibe main.py ..."));
                    usb.writeFile("main.py", asset("esp32/main.py").getBytes("UTF-8"));
                    JSONObject cfg = obj("ssid", ssid, "password", password,
                            "name", name == null || name.isBlank() ? "ESP32-Claude" : name, "ble", true,
                            "ap_password", "esp32code");
                    emit("progress", obj("text", "Schreibe config.json ..."));
                    usb.writeFile("config.json", cfg.toString().getBytes("UTF-8"));
                    emit("progress", obj("text", "Starte ESP32 neu ..."));
                    usb.exec("import machine\nmachine.soft_reset()", 1500);
                    reply(id, obj("ok", true));
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    // soft_reset beendet das Raw-REPL ohne Antwort - das ist ok
                    if (m.contains("USB-Timeout") && m.length() < 60) reply(id, obj("ok", true));
                    else reply(id, obj("ok", false, "err", m));
                }
            });
        }

        @JavascriptInterface
        public void claudeChat(String apiKey, String model, String effort, String boardNote, String text) {
            pool.execute(() -> agent.chat(apiKey, model, effort, boardNote, text,
                    () -> serialLink(currentLink()),
                    ev -> emit("claude", ev)));
        }

        @JavascriptInterface
        public void claudeCancel() { agent.cancel(); }

        @JavascriptInterface
        public void claudeReset() { pool.execute(agent::reset); }
    }
}
