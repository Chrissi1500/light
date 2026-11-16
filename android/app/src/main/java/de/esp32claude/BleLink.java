package de.esp32claude;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bluetooth-LE-Verbindung (Nordic UART Service) zum ESP32. Zeilen mit JSON hin und her. */
@SuppressLint("MissingPermission")
public class BleLink implements EspLink {
    static final UUID SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onDevice(String name, String address, int rssi);
        void onState(String state, String detail);
    }

    private final Context ctx;
    private final Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private int mtu = 23;
    private volatile boolean ready = false;
    private String deviceName = "";
    private final Semaphore writeDone = new Semaphore(0);
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
    private ScanCallback scanCb;

    public BleLink(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
    }

    BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    public boolean isReady() { return ready; }

    public void scan() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            listener.onState("error", "Bluetooth ist aus");
            return;
        }
        stopScan();
        Set<String> seen = new HashSet<>();
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                if (name == null || !seen.add(d.getAddress())) return;
                listener.onDevice(name, d.getAddress(), r.getRssi());
            }
            @Override
            public void onScanFailed(int errorCode) {
                listener.onState("error", "Scan fehlgeschlagen (" + errorCode + ")");
            }
        };
        a.getBluetoothLeScanner().startScan(null,
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        listener.onState("scanning", "");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopScan();
            listener.onState(ready ? "connected" : "idle", "Scan beendet");
        }, 8000);
    }

    public void stopScan() {
        BluetoothAdapter a = adapter();
        if (scanCb != null && a != null && a.getBluetoothLeScanner() != null) {
            try { a.getBluetoothLeScanner().stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanCb = null;
    }

    public void connect(String address) {
        stopScan();
        disconnect();
        BluetoothDevice dev = adapter().getRemoteDevice(address);
        deviceName = dev.getName() != null ? dev.getName() : address;
        listener.onState("connecting", deviceName);
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    public void disconnect() {
        ready = false;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(517);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                g.close();
                if (g == gatt) gatt = null;
                listener.onState("disconnected", deviceName);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            mtu = status == BluetoothGatt.GATT_SUCCESS ? newMtu : 23;
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(SERVICE);
            if (s == null) {
                listener.onState("error", "Kein ESP32-Claude-Dienst gefunden (Agent installiert?)");
                g.disconnect();
                return;
            }
            rx = s.getCharacteristic(RX);
            BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            ready = true;
            listener.onState("connected", deviceName + " (MTU " + mtu + ")");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writeDone.release();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onData(value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onData(c.getValue());
        }
    };

    private synchronized void onData(byte[] v) {
        for (byte b : v) {
            if (b == '\n') {
                lines.offer(new String(inBuf.toByteArray(), StandardCharsets.UTF_8));
                inBuf.reset();
            } else {
                inBuf.write(b);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public synchronized JSONObject call(JSONObject cmd, int timeoutMs) throws Exception {
        if (!ready || gatt == null) throw new Exception("Bluetooth nicht verbunden");
        lines.clear();
        byte[] data = (cmd.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        int chunk = Math.max(20, mtu - 3);
        writeDone.drainPermits();
        for (int i = 0; i < data.length; i += chunk) {
            byte[] part = java.util.Arrays.copyOfRange(data, i, Math.min(data.length, i + chunk));
            rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rx.setValue(part);
            boolean ok = false;
            for (int t = 0; t < 20 && !ok; t++) {
                ok = gatt.writeCharacteristic(rx);
                if (!ok) Thread.sleep(25);
            }
            if (!ok) throw new Exception("Bluetooth-Schreibfehler");
            if (!writeDone.tryAcquire(5, TimeUnit.SECONDS)) throw new Exception("Bluetooth-Timeout beim Senden");
        }
        String line = lines.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (line == null) throw new Exception("Keine Antwort vom ESP32 (Bluetooth-Timeout)");
        return new JSONObject(line);
    }

    @Override
    public String describe() { return "Bluetooth " + deviceName; }
}
