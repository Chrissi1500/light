package de.esp32claude;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * USB-OTG-Verbindung Handy -> ESP32 ueber das MicroPython-"Raw REPL".
 * Funktioniert auch ohne installierten Agent (fuer die Erstinstallation).
 */
public class UsbLink implements EspLink {
    public static final String ACTION_PERMISSION = "de.esp32claude.USB_PERMISSION";
    private final Context ctx;
    private UsbSerialPort port;
    private String name = "";

    public UsbLink(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public boolean isOpen() { return port != null && port.isOpen(); }

    UsbSerialDriver findDriver() {
        UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(m);
        if (!drivers.isEmpty()) return drivers.get(0);
        for (UsbDevice d : m.getDeviceList().values()) return new CdcAcmSerialDriver(d);
        return null;
    }

    /** @return null wenn ok, sonst Fehlermeldung. "PERMISSION" = Berechtigung angefragt. */
    public synchronized String open() {
        close();
        UsbManager m = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbSerialDriver drv = findDriver();
        if (drv == null) return "Kein USB-Geraet gefunden. ESP32 per USB-OTG-Adapter/Kabel ans Handy anschliessen.";
        UsbDevice dev = drv.getDevice();
        if (!m.hasPermission(dev)) {
            Intent i = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            m.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, i, flags));
            return "PERMISSION";
        }
        UsbDeviceConnection conn = m.openDevice(dev);
        if (conn == null) return "USB-Geraet konnte nicht geoeffnet werden";
        try {
            UsbSerialPort p = drv.getPorts().get(0);
            p.open(conn);
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try { p.setDTR(false); p.setRTS(false); } catch (Exception ignored) { }
            port = p;
            name = String.format("%s (%04X:%04X)", drv.getClass().getSimpleName().replace("SerialDriver", ""),
                    dev.getVendorId(), dev.getProductId());
            return null;
        } catch (Exception e) {
            return "USB-Fehler: " + e.getMessage();
        }
    }

    public synchronized void close() {
        if (port != null) {
            try {
                port.write(new byte[]{2}, 200);      // Raw REPL verlassen
                port.write(new byte[]{4}, 200);      // Soft-Reset -> main.py startet Agent neu
            } catch (Exception ignored) { }
            try { port.close(); } catch (Exception ignored) { }
            port = null;
        }
    }

    private void write(byte[] b) throws Exception {
        for (int i = 0; i < b.length; i += 256) {
            int n = Math.min(256, b.length - i);
            byte[] part = new byte[n];
            System.arraycopy(b, i, part, 0, n);
            port.write(part, 2000);
            Thread.sleep(10);
        }
    }

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** Liest bis 'marker' auftaucht; gibt alles davor zurueck. */
    private String readUntil(String marker, long timeoutMs) throws Exception {
        byte[] mk = marker.getBytes(StandardCharsets.UTF_8);
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[1024];
        while (true) {
            byte[] have = pending.toByteArray();
            int idx = indexOf(have, mk);
            if (idx >= 0) {
                pending.reset();
                pending.write(have, idx + mk.length, have.length - idx - mk.length);
                return new String(have, 0, idx, StandardCharsets.UTF_8);
            }
            if (System.currentTimeMillis() > end) throw new Exception("USB-Timeout (keine Antwort vom ESP32)");
            int n = port.read(buf, 200);
            if (n > 0) pending.write(buf, 0, n);
        }
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i <= h.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    private void enterRaw() throws Exception {
        write(new byte[]{'\r', 3, 3});         // laufendes Programm (Agent) unterbrechen
        Thread.sleep(150);
        drain();
        write(new byte[]{'\r', 1});            // Raw REPL
        readUntil("raw REPL; CTRL-B to exit\r\n>", 3000);
    }

    private void drain() throws Exception {
        byte[] buf = new byte[1024];
        pending.reset();
        while (port.read(buf, 100) > 0) { /* weg damit */ }
    }

    /** Fuehrt Python-Code im Raw REPL aus. @return {stdout, stderr} */
    public synchronized String[] exec(String code, long timeoutMs) throws Exception {
        if (!isOpen()) throw new Exception("USB nicht verbunden");
        enterRaw();
        write(code.getBytes(StandardCharsets.UTF_8));
        write(new byte[]{4});
        readUntil("OK", 5000);   // evtl. Ausgaben einer laufenden App davor werden ignoriert
        String out = readUntil("\u0004", timeoutMs);
        String err = readUntil("\u0004", 5000);
        readUntil(">", 2000);
        return new String[]{out, err};
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public void writeFile(String path, byte[] data) throws Exception {
        exec("f=open('" + path + "','wb')\nf.close()", 5000);
        for (int i = 0; i < data.length; i += 1024) {
            int n = Math.min(1024, data.length - i);
            byte[] part = new byte[n];
            System.arraycopy(data, i, part, 0, n);
            String[] r = exec("f=open('" + path + "','ab')\nf.write(bytes.fromhex('" + hex(part) + "'))\nf.close()", 5000);
            if (r[1].trim().length() > 0) throw new Exception(r[1]);
        }
    }

    @Override
    public JSONObject call(JSONObject cmd, int timeoutMs) throws Exception {
        String h = hex(cmd.toString().getBytes(StandardCharsets.UTF_8));
        String[] r = exec("import agent\nagent.cli(bytes.fromhex('" + h + "').decode())", timeoutMs);
        int a = r[0].indexOf('\u0002'), b = r[0].lastIndexOf('\u0003');
        if (a >= 0 && b > a) return new JSONObject(r[0].substring(a + 1, b));
        if (r[1].contains("ImportError") || r[1].contains("no module named 'agent'"))
            throw new Exception("Agent ist noch nicht auf dem ESP32 installiert - im Tab 'Verbindung' auf 'Agent installieren' tippen.");
        throw new Exception("Unerwartete USB-Antwort: " + r[0] + r[1]);
    }

    @Override
    public String describe() { return "USB " + name; }
}
