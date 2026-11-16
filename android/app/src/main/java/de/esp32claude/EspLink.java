package de.esp32claude;

import org.json.JSONObject;

/** Ein Weg zum ESP32 (WLAN, Bluetooth oder USB). Schickt einen JSON-Befehl, liefert die JSON-Antwort. */
public interface EspLink {
    JSONObject call(JSONObject cmd, int timeoutMs) throws Exception;
    String describe();
}
