# ESP32 Claude – ESP32 vom Handy aus programmieren

Eine Android-App, mit der du deinen ESP32 **per WLAN, Bluetooth oder USB-Kabel** programmierst –
selbst oder mit **Claude als Assistent**: Du beschreibst, was der ESP32 tun soll, Claude schreibt
MicroPython-Code, testet ihn direkt auf dem Gerät, liest die Ausgaben und installiert das fertige Programm.

```
 Handy (App)  ──WLAN (HTTP)──┐
              ──Bluetooth LE─┼──►  ESP32 mit MicroPython + "ESP32 Claude Agent"
              ──USB-OTG──────┘        └─ app.py  (dein Programm, läuft im Hintergrund)
```

| Ordner | Inhalt |
|---|---|
| `release/ESP32-Claude.apk` | fertige App zum Installieren |
| `android/` | Quellcode der App (Java + WebView-Oberfläche) |
| `esp32/` | Agent für den ESP32 (`agent.py`, `main.py`) |
| `laptop/esp32_setup.py` | Einrichtung am Laptop: MicroPython flashen + Agent installieren |
| `release/Fahrradlicht.apk` | **Fahrradlicht-App**: Zusatzlicht am Fahrrad per Bluetooth schalten (siehe unten) |
| `fahrradlicht/` | Quellcode der Fahrradlicht-App |
| `esp32/licht.py` | Licht-Programm für den ESP32 (spielt die App selbst auf) |

---

## Fahrradlicht-App 🚲

Eigene, einfache App für ein **Zusatzlicht** am Fahrrad – große Knöpfe, dunkles Design, verbindet sich automatisch.
Front- und Rücklicht bleiben beim Fahrrad (bzw. eBike) selbst.

- **Große Lampen-Taste**: antippen = an/aus
- **Aus / An / Blink** und ein **Helligkeitsregler** (PWM)
- **Einstellungen** (Zahnrad): Name und GPIO-Pin ändern, Relais-Modus (invertiert), Blinktempo,
  Bildschirm anlassen, Vibration. Bei Bedarf lassen sich weitere Lichter hinzufügen – dann erscheinen
  zusätzlich „Alles an / Alles aus“.

### Einrichten

1. ESP32 wie unten in Schritt 1 einrichten (MicroPython + Agent, Bluetooth ist automatisch an).
2. `release/Fahrradlicht.apk` aufs Handy laden und installieren.
3. App öffnen → **Suchen** → deinen ESP32 (Standard: *ESP32-Claude*) antippen.
4. Beim ersten Mal fragt die App: **„Licht-Programm installieren“** → antippen. Sie überträgt `licht.py`
   per Bluetooth, richtet es als `app.py` mit Autostart ein, der ESP32 startet neu und die App verbindet sich
   von selbst wieder. Ab dann startet die App direkt mit den Lichtknöpfen.

Die App merkt sich den ESP32 und verbindet sich beim Öffnen (und nach Verbindungsabbrüchen) automatisch.
Der Zustand des Lichts (an/aus, Helligkeit) bleibt auf dem ESP32 gespeichert (`licht.json`).

### Verkabelung

Das Zusatzlicht hängt standardmäßig an **GPIO 25** (in der App änderbar).

Die ESP32-Pins liefern nur 3,3 V und wenige mA – **die Lampe nie direkt anschließen**, sondern über einen
Logic-Level-MOSFET (z. B. IRLZ44N, AO3400) oder ein MOSFET-Modul:

```
 Akku + ──────────────► Lampe + 
                         Lampe − ──► MOSFET Drain
 ESP32 GPIO ──[220 Ω]──► MOSFET Gate   (+ 10 kΩ Gate → GND)
 ESP32 GND ──┬─────────► MOSFET Source
 Akku − ─────┘          (gemeinsame Masse!)
```

Relais-Module, die bei LOW schalten: in den Einstellungen „Ausgänge invertieren“ anhaken (Helligkeit und
Blinken funktionieren mit Relais nur eingeschränkt). Den ESP32 kannst du z. B. über einen Step-Down-Wandler
oder eine USB-Powerbank vom Fahrradakku versorgen.

> Hinweis: Im Straßenverkehr (StVZO) gelten feste Regeln für Fahrradbeleuchtung – ein blinkendes oder blendendes
> Zusatzlicht ist dort nicht erlaubt. Blinken und volle Helligkeit sind für abseits der Straße gedacht.

### Protokoll

Die App nutzt den vorhandenen Agent-Befehl `run` über Bluetooth:
`{"cmd":"run","code":"import licht\nlicht.cmd('{\"a\":\"toggle\",\"id\":\"extra\"}')"}` –
Aktionen: `get`, `set`, `toggle`, `all`, `config` (siehe Kopf von `esp32/licht.py`).

---

## 1. ESP32 einmalig am Laptop einrichten (USB-C)

Du brauchst Python 3 (Windows: von python.org, beim Installieren „Add to PATH“ anhaken).

```bash
git clone https://github.com/chrissi1500/light.git
```

```bash
cd light/laptop
```

```bash
python esp32_setup.py
```

Das Skript
1. installiert `esptool`, `mpremote`, `pyserial`,
2. findet den ESP32 am USB-Port und erkennt den Chip (ESP32, -S2, -S3, -C3, -C6),
3. lädt die aktuelle MicroPython-Firmware und flasht sie (**löscht alles auf dem ESP32**),
4. fragt nach WLAN-Name/-Passwort und Gerätename und kopiert den Agent drauf,
5. zeigt die Ausgabe des ESP32 – dort steht die **IP-Adresse** (z. B. `WLAN verbunden, IP: 192.168.178.42`).

Probleme?
- *Kein Port gefunden*: Datenkabel statt Ladekabel nehmen; Treiber für CP210x bzw. CH340 installieren.
- *Chip nicht erkannt*: BOOT-Taste halten, kurz EN/RESET drücken, BOOT loslassen, nochmal starten.
- MicroPython ist schon drauf: `python esp32_setup.py --nur-agent`
- Nur Ausgabe/IP ansehen: `python esp32_setup.py --log`

> Alternative ohne Laptop-Skript: MicroPython flashen (z. B. mit Thonny) und dann in der App unter
> **Verbinden → USB → „Agent installieren“** den Agent direkt vom Handy per OTG-Kabel aufspielen.

## 2. App aufs Handy

1. `release/ESP32-Claude.apk` aufs Handy laden (z. B. über GitHub im Handy-Browser öffnen → „Download raw file“).
2. Antippen → Android fragt nach „Installation aus unbekannten Quellen“ → für den Browser/Dateimanager erlauben.
3. App **ESP32 Claude** öffnen.

Die APK wird bei jedem Push auch automatisch von GitHub Actions gebaut (Tab *Actions* → Artefakt).

## 3. Verbinden

| Weg | Wie |
|---|---|
| **WLAN** | Handy im selben WLAN → „Suchen“ oder IP eintragen → „Verbinden“. Ohne WLAN-Daten macht der ESP32 einen Hotspot **ESP32-Claude** (Passwort `esp32code`), IP `192.168.4.1`. |
| **Bluetooth** | „Scannen“ → **ESP32-Claude** antippen. Kein Pairing nötig. |
| **USB** | ESP32 per USB-C-/OTG-Kabel ans Handy → „USB verbinden“ → Zugriff erlauben. |

Über *Verbinden → „WLAN des ESP32 ändern“* kannst du die WLAN-Daten jederzeit z. B. per Bluetooth neu setzen.

## 4. Mit Claude programmieren

1. API-Key holen: <https://console.anthropic.com> → *API Keys* → *Create Key*
   (die API wird nach Verbrauch abgerechnet, getrennt vom Claude-Abo).
2. In der App: Tab **Claude** → *Einstellungen* → Key einfügen, Modell wählen, optional Board/Verkabelung
   beschreiben (z. B. „DevKit V1, LED an GPIO 2, DHT22 an GPIO 4“).
3. Schreiben, was der ESP32 tun soll, z. B.
   - „Lass die LED im Sekundentakt blinken“
   - „Miss alle 10 s die Temperatur mit dem DHT22 und gib sie aus“
   - „Mach einen kleinen Webserver auf Port 8080, der die LED schaltet“

Claude hat Werkzeuge, um auf dem ESP32 Code auszuführen, Dateien zu lesen/schreiben, Logs zu lesen und
das Programm dauerhaft als `app.py` zu installieren. Jeder Schritt ist im Chat aufklappbar.

Modelle: **Opus 5.5** (Standard, beste Qualität), **Sonnet 5.5** (schneller/günstiger), **Haiku 5.5** (am günstigsten).
Bei Opus/Sonnet ist der serverseitige Fallback aktiv: Lehnt das Modell eine Anfrage aus Sicherheitsgründen ab,
beantwortet sie automatisch ein passendes anderes Modell.

## 5. Selbst programmieren

- **Code**: Editor mit Beispielen. *▶ Ausführen* testet sofort, *⬆ Als App installieren* speichert als `app.py`
  und startet neu, *⏹ App stoppen* deaktiviert sie (Notbremse).
- **Dateien**: Dateien auf dem ESP32 öffnen/löschen.
- **Log**: Live-Ausgabe von `print()` deines Programms.

Regeln für `app.py`: kein eigenes WLAN-Setup, nicht Port 80/4210 und kein Bluetooth verwenden – das macht der Agent.
`main.py`, `agent.py` und `config.json` nicht überschreiben.

## Protokoll (für eigene Tools)

Der Agent nimmt JSON-Befehle per `POST http://<ip>/api`, über Bluetooth (Nordic UART, eine JSON-Zeile pro Befehl)
oder per USB (`agent.cli('<json>')` im Raw-REPL) an:

```json
{"cmd":"run","code":"print(1+1)","timeout":10}
```

Befehle: `info`, `run`, `ls`, `read`, `write`, `rm`, `logs`, `deploy`, `stop`, `start`, `reset`, `wifi`, `config`.
Gerätesuche: UDP-Broadcast `ESP32-CLAUDE?` an Port 4210.

## Sicherheit

- Der Agent hat **kein Passwort**: Wer im selben WLAN ist oder in Bluetooth-Reichweite, kann Code ausführen.
  Für Bastelprojekte zu Hause okay – nicht in öffentlichen Netzen betreiben.
- Der API-Key liegt nur lokal in der App.
- Die APK ist mit einem Schlüssel signiert, der im Repo liegt (`android/app/esp32claude.keystore`), damit Updates
  sich über die alte Version installieren lassen. Für eine öffentliche Verbreitung einen eigenen Schlüssel nehmen.

## App selbst bauen

```bash
cd android && ./gradlew assembleRelease
```

Ergebnis: `android/app/build/outputs/apk/release/app-release.apk` (benötigt JDK 17+ und Android SDK 35).
