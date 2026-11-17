package de.fahrradlicht;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fahrradlicht: WebView-Oberflaeche + Bluetooth-LE-Verbindung zum ESP32-Agent. */
public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** BLE vertraegt keine parallelen Befehle - alles nacheinander. */
    private final ExecutorService espQueue = Executors.newSingleThreadExecutor();
    private BleLink ble;
    private String pendingConnect = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
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
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onDestroy() {
        ble.disconnect();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (!ok) {
            emit("bleState", obj("state", "error", "detail", "Ohne Bluetooth-Berechtigung geht es leider nicht"));
            return;
        }
        if (pendingConnect != null) { String a = pendingConnect; pendingConnect = null; ble.connect(a); }
        else ble.scan();
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

    String[] blePerms() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean hasPerms(String... perms) {
        for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    class Bridge {
        @JavascriptInterface
        public void send(String id, String cmdJson, int timeoutMs) {
            pool.execute(() -> {
                try {
                    JSONObject cmd = new JSONObject(cmdJson);
                    JSONObject r = espQueue.submit(() -> ble.call(cmd, timeoutMs)).get();
                    reply(id, r);
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    reply(id, obj("ok", false, "err", String.valueOf(c.getMessage())));
                }
            });
        }

        @JavascriptInterface
        public void bleScan() {
            runOnUiThread(() -> {
                if (hasPerms(blePerms())) ble.scan();
                else { pendingConnect = null; requestPermissions(blePerms(), 1); }
            });
        }

        @JavascriptInterface
        public void bleConnect(String address) {
            runOnUiThread(() -> {
                if (hasPerms(blePerms())) ble.connect(address);
                else { pendingConnect = address; requestPermissions(blePerms(), 1); }
            });
        }

        @JavascriptInterface
        public void bleDisconnect() { runOnUiThread(() -> ble.disconnect()); }

        @JavascriptInterface
        public boolean bleReady() { return ble.isReady(); }

        /** Inhalt einer Datei aus den App-Assets (z. B. esp32/licht.py). */
        @JavascriptInterface
        public String asset(String path) {
            try (InputStream is = getAssets().open(path)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                return bo.toString("UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }
    }
}
