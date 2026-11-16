# ESP32 Claude Agent - MicroPython
# Stellt den ESP32 per WLAN (HTTP + UDP-Discovery), Bluetooth LE (Nordic UART)
# und USB (Raw-REPL) fuer die Android-App "ESP32 Claude" bereit.
#
# Protokoll: JSON-Befehl rein -> JSON-Antwort raus.
#   {"cmd":"info"} | {"cmd":"run","code":"...","timeout":10} | {"cmd":"ls","path":"/"}
#   {"cmd":"read","path":"x"} | {"cmd":"write","path":"x","data":"..."} | {"cmd":"rm","path":"x"}
#   {"cmd":"logs","since":0} | {"cmd":"deploy","code":"..."} | {"cmd":"stop"} | {"cmd":"start"}
#   {"cmd":"reset"} | {"cmd":"wifi","ssid":"..","password":".."} | {"cmd":"config","name":"..","ble":true}

import gc, os, sys, time, json, machine, _thread

VERSION = "1.0"
CONFIG_FILE = "config.json"
APP_FILE = "app.py"
APP_DISABLED = "app.disabled"
UDP_PORT = 4210

try:
    _thread.stack_size(16 * 1024)
except Exception:
    pass

# ---------------------------------------------------------------- Konfiguration
def load_config():
    cfg = {"ssid": "", "password": "", "name": "ESP32-Claude", "ble": True,
           "ap_password": "esp32code"}
    try:
        with open(CONFIG_FILE) as f:
            cfg.update(json.load(f))
    except Exception:
        pass
    return cfg

def save_config(cfg):
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f)

CFG = load_config()
STATE = {"ip": None, "mode": "offline", "app": "stopped", "app_error": None}

# ---------------------------------------------------------------- Log-Puffer
_LOG = []
_LOG_SEQ = [0]
_LOG_MAX = 200
_log_lock = _thread.allocate_lock()

def log(*args):
    line = " ".join(str(a) for a in args)
    try:
        print(line)
    except Exception:
        pass
    with _log_lock:
        _LOG_SEQ[0] += 1
        _LOG.append((_LOG_SEQ[0], line))
        if len(_LOG) > _LOG_MAX:
            del _LOG[0]

def logs_since(n):
    with _log_lock:
        return [[s, l] for (s, l) in _LOG if s > n], _LOG_SEQ[0]

def _exc_text(e):
    try:
        import io
        b = io.StringIO()
        sys.print_exception(e, b)
        return b.getvalue()
    except Exception:
        return repr(e)

# ---------------------------------------------------------------- Code ausfuehren
def _make_print(buf):
    def _p(*args, sep=" ", end="\n", **kw):
        s = sep.join(str(a) for a in args) + end
        if buf is not None:
            buf.append(s)
        log(s.rstrip("\n"))
    return _p

_JOBS = {}
_job_id = [0]

def _job(jid, code):
    job = _JOBS[jid]
    g = {"__name__": "__main__", "print": _make_print(job["out"])}
    try:
        exec(code, g)
    except BaseException as e:
        job["err"] = _exc_text(e)
        log("[Fehler]", job["err"])
    job["done"] = True
    gc.collect()

def run_code(code, timeout=10):
    _job_id[0] += 1
    jid = _job_id[0]
    _JOBS[jid] = {"out": [], "err": None, "done": False}
    for k in list(_JOBS):
        if k < jid - 5:
            del _JOBS[k]
    _thread.start_new_thread(_job, (jid, code))
    t0 = time.ticks_ms()
    while not _JOBS[jid]["done"] and time.ticks_diff(time.ticks_ms(), t0) < timeout * 1000:
        time.sleep_ms(20)
    job = _JOBS[jid]
    res = {"ok": job["err"] is None, "out": "".join(job["out"])[-6000:], "done": job["done"]}
    if job["err"]:
        res["err"] = job["err"]
    if not job["done"]:
        res["note"] = "Code laeuft noch im Hintergrund weiter (Ausgabe via logs). Stoppen nur per reset."
    return res

def _app_thread(code):
    STATE["app"] = "running"
    g = {"__name__": "__main__", "print": _make_print(None)}
    try:
        exec(code, g)
        STATE["app"] = "finished"
    except BaseException as e:
        STATE["app"] = "crashed"
        STATE["app_error"] = _exc_text(e)
        log("[App-Fehler]", STATE["app_error"])

def start_app():
    if not exists(APP_FILE) or exists(APP_DISABLED):
        return False
    with open(APP_FILE) as f:
        code = f.read()
    log("Starte app.py ...")
    _thread.start_new_thread(_app_thread, (code,))
    return True

# ---------------------------------------------------------------- Dateien
def exists(p):
    try:
        os.stat(p)
        return True
    except OSError:
        return False

def list_files(path="/"):
    out = []
    for e in os.ilistdir(path):
        name, typ = e[0], e[1]
        size = e[3] if len(e) > 3 else 0
        out.append({"name": name, "dir": typ == 0x4000, "size": size})
    return out

def _mkdirs(path):
    parts = path.strip("/").split("/")[:-1]
    cur = "" if path.startswith("/") else "."
    for p in parts:
        cur += "/" + p
        if not exists(cur):
            os.mkdir(cur)

# ---------------------------------------------------------------- Befehle
def _later(fn, ms=400):
    def _t():
        time.sleep_ms(ms)
        fn()
    _thread.start_new_thread(_t, ())

def handle(req):
    try:
        cmd = req.get("cmd")
        if cmd == "info":
            fs = os.statvfs("/")
            return {"ok": True, "name": CFG["name"], "version": VERSION,
                    "platform": sys.platform, "micropython": sys.version,
                    "machine": getattr(os.uname(), "machine", ""),
                    "ip": STATE["ip"], "mode": STATE["mode"], "app": STATE["app"],
                    "app_error": STATE["app_error"], "has_app": exists(APP_FILE),
                    "mem_free": gc.mem_free(), "fs_free": fs[0] * fs[3],
                    "freq": machine.freq()}
        if cmd == "run":
            return run_code(req.get("code", ""), req.get("timeout", 10))
        if cmd == "ls":
            return {"ok": True, "files": list_files(req.get("path", "/"))}
        if cmd == "read":
            with open(req["path"]) as f:
                return {"ok": True, "data": f.read()}
        if cmd == "write":
            _mkdirs(req["path"])
            with open(req["path"], "a" if req.get("append") else "w") as f:
                f.write(req.get("data", ""))
            return {"ok": True}
        if cmd == "rm":
            os.remove(req["path"])
            return {"ok": True}
        if cmd == "logs":
            lines, last = logs_since(req.get("since", 0))
            return {"ok": True, "lines": lines, "last": last, "app": STATE["app"]}
        if cmd == "deploy":
            with open(APP_FILE, "w") as f:
                f.write(req.get("code", ""))
            if exists(APP_DISABLED):
                os.remove(APP_DISABLED)
            _later(machine.reset)
            return {"ok": True, "note": "app.py gespeichert, ESP32 startet neu."}
        if cmd == "stop":
            with open(APP_DISABLED, "w") as f:
                f.write("1")
            _later(machine.reset)
            return {"ok": True, "note": "App deaktiviert, ESP32 startet neu."}
        if cmd == "start":
            if exists(APP_DISABLED):
                os.remove(APP_DISABLED)
            _later(machine.reset)
            return {"ok": True, "note": "App aktiviert, ESP32 startet neu."}
        if cmd == "reset":
            _later(machine.reset)
            return {"ok": True, "note": "Neustart ..."}
        if cmd == "wifi":
            CFG["ssid"] = req.get("ssid", "")
            CFG["password"] = req.get("password", "")
            save_config(CFG)
            _later(machine.reset, 800)
            return {"ok": True, "note": "WLAN gespeichert, ESP32 startet neu."}
        if cmd == "config":
            for k in ("name", "ble", "ap_password"):
                if k in req:
                    CFG[k] = req[k]
            save_config(CFG)
            return {"ok": True, "config": {k: v for k, v in CFG.items() if k != "password"}}
        return {"ok": False, "err": "Unbekannter Befehl: %s" % cmd}
    except Exception as e:
        return {"ok": False, "err": _exc_text(e)}

def cli(s):
    # Fuer USB: print(json) einer Antwort, mit Markern damit die App sie findet
    print("\x02" + json.dumps(handle(json.loads(s))) + "\x03")

# ---------------------------------------------------------------- WLAN
def wifi_up():
    import network
    try:
        network.hostname(CFG["name"].lower().replace(" ", "-"))
    except Exception:
        pass
    sta = network.WLAN(network.STA_IF)
    if CFG.get("ssid"):
        sta.active(True)
        log("Verbinde mit WLAN", CFG["ssid"], "...")
        sta.connect(CFG["ssid"], CFG.get("password", ""))
        t0 = time.ticks_ms()
        while not sta.isconnected() and time.ticks_diff(time.ticks_ms(), t0) < 15000:
            time.sleep_ms(200)
        if sta.isconnected():
            STATE["ip"] = sta.ifconfig()[0]
            STATE["mode"] = "wlan"
            log("WLAN verbunden, IP:", STATE["ip"])
            return
        log("WLAN-Verbindung fehlgeschlagen")
        sta.active(False)
    ap = network.WLAN(network.AP_IF)
    ap.active(True)
    pw = CFG.get("ap_password") or "esp32code"
    try:
        ap.config(essid=CFG["name"], password=pw, authmode=3)
    except Exception:
        ap.config(essid=CFG["name"], password=pw)
    STATE["ip"] = ap.ifconfig()[0]
    STATE["mode"] = "hotspot"
    log("Hotspot aktiv:", CFG["name"], "Passwort:", pw, "IP:", STATE["ip"])

# ---------------------------------------------------------------- Bluetooth LE (Nordic UART)
_BLE_Q = []

class BleUart:
    def __init__(self, name):
        import bluetooth
        from micropython import const
        self.bt = bluetooth
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        try:
            self.ble.config(mtu=512)
        except Exception:
            pass
        self.ble.irq(self._irq)
        UART = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        TX = (bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ)
        RX = (bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_WRITE | bluetooth.FLAG_WRITE_NO_RESPONSE)
        ((self.tx, self.rx),) = self.ble.gatts_register_services(((UART, (TX, RX)),))
        self.ble.gatts_set_buffer(self.rx, 512, True)
        self.conns = {}
        self.buf = b""
        self.name = name
        self.adv()

    def adv(self):
        n = self.name.encode()[:20]
        payload = bytes((2, 1, 6)) + bytes((len(n) + 1, 9)) + n
        self.ble.gap_advertise(100000, adv_data=payload)

    def _irq(self, event, data):
        if event == 1:  # connect
            self.conns[data[0]] = 23
        elif event == 2:  # disconnect
            self.conns.pop(data[0], None)
            self.buf = b""
            self.adv()
        elif event == 3:  # write
            conn, handle = data[0], data[1]
            if handle == self.rx:
                self.buf += self.ble.gatts_read(self.rx)
                while b"\n" in self.buf:
                    line, self.buf = self.buf.split(b"\n", 1)
                    if line.strip():
                        _BLE_Q.append((conn, line))
        elif event == 21:  # MTU exchanged
            self.conns[data[0]] = data[1]

    def send(self, conn, data):
        mtu = self.conns.get(conn, 23)
        n = max(20, mtu - 3)
        for i in range(0, len(data), n):
            try:
                self.ble.gatts_notify(conn, self.tx, data[i:i + n])
            except Exception:
                time.sleep_ms(30)
                try:
                    self.ble.gatts_notify(conn, self.tx, data[i:i + n])
                except Exception:
                    return
            time.sleep_ms(8)

# ---------------------------------------------------------------- HTTP-Server
_PAGE = """<!doctype html><meta name=viewport content="width=device-width"><title>%s</title>
<body style="font-family:sans-serif;padding:16px"><h2>%s</h2><p>ESP32 Claude Agent v%s laeuft.</p>
<p>Nutze die Android-App <b>ESP32 Claude</b> oder POST JSON an <code>/api</code>.</p></body>"""

def _http(cl):
    cl.settimeout(8)
    data = b""
    while b"\r\n\r\n" not in data:
        ch = cl.recv(1024)
        if not ch:
            return
        data += ch
    head, body = data.split(b"\r\n\r\n", 1)
    lines = head.decode().split("\r\n")
    method, path = lines[0].split(" ")[0:2]
    clen = 0
    for l in lines[1:]:
        if l.lower().startswith("content-length:"):
            clen = int(l.split(":", 1)[1])
    while len(body) < clen:
        ch = cl.recv(min(4096, clen - len(body)))
        if not ch:
            break
        body += ch
    if method == "POST" and path.startswith("/api"):
        try:
            req = json.loads(body)
            resp = json.dumps(handle(req))
        except Exception as e:
            resp = json.dumps({"ok": False, "err": _exc_text(e)})
        ctype = "application/json"
    elif method == "OPTIONS":
        resp, ctype = "", "text/plain"
    else:
        resp = _PAGE % (CFG["name"], CFG["name"], VERSION)
        ctype = "text/html"
    rb = resp.encode()
    cl.send(("HTTP/1.0 200 OK\r\nContent-Type: %s\r\nContent-Length: %d\r\n"
             "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\n"
             "Connection: close\r\n\r\n" % (ctype, len(rb))).encode())
    mv = memoryview(rb)
    for i in range(0, len(rb), 1024):
        cl.send(mv[i:i + 1024])

def _fd(s):
    try:
        return s.fileno()
    except Exception:
        return -1

def serve():
    import socket, select
    log("ESP32 Claude Agent v" + VERSION)
    try:
        wifi_up()
    except Exception as e:
        log("WLAN-Fehler:", _exc_text(e))
    ble = None
    if CFG.get("ble", True):
        try:
            ble = BleUart(CFG["name"])
            log("Bluetooth aktiv als", CFG["name"])
        except Exception as e:
            log("Bluetooth-Fehler:", _exc_text(e))
    try:
        start_app()
    except Exception as e:
        log("App-Start-Fehler:", _exc_text(e))

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 80))
    srv.listen(3)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", UDP_PORT))
    poller = select.poll()
    poller.register(srv, select.POLLIN)
    poller.register(udp, select.POLLIN)
    log("HTTP-Server auf http://%s/" % STATE["ip"])
    while True:
        for (obj, ev) in poller.poll(50):
            try:
                if obj is udp or obj == _fd(udp):
                    msg, addr = udp.recvfrom(64)
                    if msg.startswith(b"ESP32-CLAUDE?"):
                        udp.sendto(json.dumps({"name": CFG["name"], "ip": STATE["ip"],
                                               "version": VERSION}).encode(), addr)
                else:
                    cl, addr = srv.accept()
                    try:
                        _http(cl)
                    finally:
                        cl.close()
            except Exception as e:
                log("Netzwerkfehler:", repr(e))
        while _BLE_Q and ble:
            conn, line = _BLE_Q.pop(0)
            try:
                resp = handle(json.loads(line))
            except Exception as e:
                resp = {"ok": False, "err": _exc_text(e)}
            ble.send(conn, json.dumps(resp).encode() + b"\n")
        gc.collect()
