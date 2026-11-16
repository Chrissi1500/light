#!/usr/bin/env python3
"""
ESP32 Claude - Einrichtung am Laptop (Windows, macOS, Linux)

Flasht MicroPython auf einen per USB angeschlossenen ESP32 und installiert den
"ESP32 Claude Agent" (WLAN + Bluetooth + USB) mit deinen WLAN-Daten.

Aufruf:
    python esp32_setup.py                 # alles: flashen + Agent installieren
    python esp32_setup.py --nur-agent     # MicroPython ist schon drauf, nur Agent kopieren
    python esp32_setup.py --log           # serielle Ausgabe anzeigen (z. B. um die IP zu sehen)

Benoetigt Python 3.8+. esptool, mpremote und pyserial werden automatisch installiert.
"""
import argparse
import getpass
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ESP_DIR = os.path.join(HERE, "..", "esp32")

# Bekannte USB-Seriell-Chips auf ESP32-Boards
KNOWN_VIDS = {0x10C4: "CP210x", 0x1A86: "CH340/CH9102", 0x0403: "FTDI", 0x303A: "Espressif USB"}

# MicroPython-Board-Name und Flash-Offset je Chip
CHIPS = {
    "ESP32-S3": ("ESP32_GENERIC_S3", "0x0"),
    "ESP32-S2": ("ESP32_GENERIC_S2", "0x1000"),
    "ESP32-C3": ("ESP32_GENERIC_C3", "0x0"),
    "ESP32-C6": ("ESP32_GENERIC_C6", "0x0"),
    "ESP32": ("ESP32_GENERIC", "0x1000"),
}


def run(cmd, check=True, capture=False):
    print("  $", " ".join(cmd))
    if capture:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if check and r.returncode != 0:
            print(r.stdout)
            sys.exit("Befehl fehlgeschlagen.")
        return r.stdout
    r = subprocess.run(cmd)
    if check and r.returncode != 0:
        sys.exit("Befehl fehlgeschlagen.")
    return ""


def ensure_tools():
    try:
        import esptool  # noqa: F401
        import serial  # noqa: F401
        import mpremote  # noqa: F401
    except ImportError:
        print("Installiere esptool, mpremote, pyserial ...")
        run([sys.executable, "-m", "pip", "install", "--upgrade", "esptool", "mpremote", "pyserial"])


def find_port(given):
    if given:
        return given
    from serial.tools import list_ports
    ports = list(list_ports.comports())
    cands = [p for p in ports if p.vid in KNOWN_VIDS]
    if len(cands) >= 1 and (len(cands) == 1 or not sys.stdin.isatty()):
        p = cands[0]
        print("ESP32 gefunden an %s (%s)" % (p.device, KNOWN_VIDS[p.vid]))
        return p.device
    if not ports:
        sys.exit("Kein serieller Port gefunden. USB-Kabel (Datenkabel!) pruefen bzw. Treiber "
                 "(CP210x/CH340) installieren.")
    print("Serielle Ports:")
    for i, p in enumerate(ports):
        print("  [%d] %s  %s" % (i, p.device, p.description))
    i = input("Nummer des ESP32-Ports: ").strip()
    return ports[int(i)].device


def detect_chip(port):
    out = run([sys.executable, "-m", "esptool", "--port", port, "chip_id"], check=False, capture=True)
    m = re.search(r"(?:Chip is|Chip type:|Detecting chip type\.\.\.)\s*(ESP32(?:-[A-Z0-9]+)?)", out)
    if not m:
        print(out)
        sys.exit("Chip nicht erkannt. Tipp: BOOT-Taste gedrueckt halten, kurz RESET/EN druecken, "
                 "BOOT loslassen und erneut versuchen.")
    chip = m.group(1)
    for key in CHIPS:
        if chip.startswith(key):
            return key
    return "ESP32"


def download_firmware(board):
    page_url = "https://micropython.org/download/%s/" % board
    print("Suche aktuelle MicroPython-Firmware auf", page_url)
    html = urllib.request.urlopen(page_url, timeout=30).read().decode("utf-8", "replace")
    links = re.findall(r'href="(/resources/firmware/%s-\d{8}-v[\d.]+\.bin)"' % board, html)
    if not links:
        sys.exit("Keine Firmware gefunden. Bitte manuell von %s laden und mit --firmware angeben." % page_url)
    url = "https://micropython.org" + links[0]
    path = os.path.join(tempfile.gettempdir(), os.path.basename(url))
    if not os.path.exists(path):
        print("Lade", url)
        urllib.request.urlretrieve(url, path)
    return path


def flash(port, chip, firmware):
    board, offset = CHIPS[chip]
    fw = firmware or download_firmware(board)
    print("\n== Loesche Flash und schreibe MicroPython (%s) ==" % chip)
    run([sys.executable, "-m", "esptool", "--port", port, "erase_flash"])
    run([sys.executable, "-m", "esptool", "--port", port, "--baud", "460800", "write_flash", offset, fw])
    print("Warte auf Neustart ...")
    import time
    time.sleep(4)


def install_agent(port, a):
    print("\n== Agent installieren ==")
    if a.yes:
        ssid, pw, name = a.ssid or "", a.password or "", a.name or "ESP32-Claude"
    else:
        ssid = a.ssid if a.ssid is not None else input("WLAN-Name (leer lassen = nur Hotspot/Bluetooth): ").strip()
        pw = a.password if a.password is not None else (getpass.getpass("WLAN-Passwort: ") if ssid else "")
        name = a.name or input("Geraetename [ESP32-Claude]: ").strip() or "ESP32-Claude"
    cfg = {"ssid": ssid, "password": pw, "name": name, "ble": True, "ap_password": "esp32code"}
    cfg_path = os.path.join(tempfile.gettempdir(), "esp32_config.json")
    with open(cfg_path, "w") as f:
        json.dump(cfg, f)
    mp = [sys.executable, "-m", "mpremote", "connect", port]
    run(mp + ["cp", os.path.join(ESP_DIR, "agent.py"), ":agent.py",
              "+", "cp", os.path.join(ESP_DIR, "main.py"), ":main.py",
              "+", "cp", cfg_path, ":config.json"])
    os.remove(cfg_path)
    print("\nFertig! Der ESP32 startet neu. Die IP-Adresse erscheint gleich in der Ausgabe.")
    if not a.log_sekunden:
        print("(Beenden mit Strg+C)\n")
    show_log(port, reset=True, seconds=a.log_sekunden)


def show_log(port, reset=False, seconds=0):
    import serial
    import time
    s = serial.Serial(port, 115200, timeout=0.2)
    if reset:
        # Reset ueber RTS (EN-Pin) - funktioniert bei den meisten Dev-Boards
        s.dtr = False
        s.rts = True
        time.sleep(0.2)
        s.rts = False
    end = time.time() + seconds if seconds else None
    try:
        while end is None or time.time() < end:
            d = s.read(512)
            if d:
                sys.stdout.write(d.decode("utf-8", "replace"))
                sys.stdout.flush()
    except KeyboardInterrupt:
        pass
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description="ESP32 Claude - Einrichtung")
    ap.add_argument("--port", help="serieller Port, z. B. COM5 oder /dev/ttyUSB0")
    ap.add_argument("--firmware", help="eigene MicroPython-.bin statt Download")
    ap.add_argument("--nur-agent", action="store_true", help="MicroPython nicht neu flashen")
    ap.add_argument("--log", action="store_true", help="nur serielle Ausgabe anzeigen")
    ap.add_argument("--ssid", help="WLAN-Name (ohne Rueckfrage)")
    ap.add_argument("--password", help="WLAN-Passwort (ohne Rueckfrage)")
    ap.add_argument("--name", help="Geraetename, Standard ESP32-Claude")
    ap.add_argument("--yes", "-y", action="store_true", help="keine Rueckfragen (fuer Skripte/Claude Code)")
    ap.add_argument("--log-sekunden", type=int, default=0, help="Ausgabe nur N Sekunden zeigen, dann beenden")
    a = ap.parse_args()

    ensure_tools()
    port = find_port(a.port)
    if a.yes and not a.log_sekunden:
        a.log_sekunden = 25
    if a.log:
        show_log(port, seconds=a.log_sekunden)
        return
    if not a.nur_agent:
        chip = detect_chip(port)
        print("Erkannter Chip:", chip)
        if not a.yes and input("MicroPython jetzt flashen? Alles auf dem ESP32 wird geloescht. [j/N] ").lower() != "j":
            sys.exit("Abgebrochen.")
        flash(port, chip, a.firmware)
    install_agent(port, a)


if __name__ == "__main__":
    main()
