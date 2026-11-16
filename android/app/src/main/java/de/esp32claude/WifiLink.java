package de.esp32claude;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTP zum ESP32 im WLAN. Nutzt explizit das WLAN-Netz, auch wenn mobile Daten aktiv sind. */
public class WifiLink implements EspLink {
    private final Context ctx;
    private final String host;

    public WifiLink(Context ctx, String host) {
        this.ctx = ctx;
        this.host = host.trim();
    }

    static Network wifiNetwork(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    @Override
    public JSONObject call(JSONObject cmd, int timeoutMs) throws Exception {
        URL url = new URL("http://" + host + "/api");
        Network wifi = wifiNetwork(ctx);
        HttpURLConnection c = (HttpURLConnection) (wifi != null ? wifi.openConnection(url) : url.openConnection());
        c.setConnectTimeout(5000);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = cmd.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) { os.write(body); }
        try (InputStream is = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int r;
            while ((r = is.read(b)) > 0) bo.write(b, 0, r);
            return new JSONObject(bo.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    @Override
    public String describe() { return "WLAN " + host; }

    /** UDP-Broadcast "ESP32-CLAUDE?" an Port 4210, sammelt Antworten. */
    public static JSONArray discover(Context ctx, int waitMs) throws Exception {
        JSONArray out = new JSONArray();
        try (DatagramSocket s = new DatagramSocket()) {
            Network wifi = wifiNetwork(ctx);
            if (wifi != null) wifi.bindSocket(s);
            s.setBroadcast(true);
            byte[] q = "ESP32-CLAUDE?".getBytes(StandardCharsets.US_ASCII);
            for (String addr : new String[]{"255.255.255.255", "192.168.4.255"}) {
                try {
                    s.send(new DatagramPacket(q, q.length, InetAddress.getByName(addr), 4210));
                } catch (Exception ignored) { }
            }
            long end = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                s.setSoTimeout((int) Math.max(1, end - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (!o.has("ip") || o.isNull("ip")) o.put("ip", p.getAddress().getHostAddress());
                    boolean dup = false;
                    for (int i = 0; i < out.length(); i++)
                        if (out.getJSONObject(i).optString("ip").equals(o.optString("ip"))) dup = true;
                    if (!dup) out.put(o);
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        }
        return out;
    }
}
