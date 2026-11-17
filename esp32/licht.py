# Fahrradlicht fuer den ESP32 (MicroPython) - gesteuert von der App "Fahrradlicht".
#
# Laeuft als app.py unter dem ESP32-Claude-Agent:   import licht; licht.main()
# Die App schickt per Bluetooth den Agent-Befehl "run" mit  licht.cmd('<json>')
# und bekommt den kompletten Zustand als JSON-Zeile zurueck.
#
# Befehle (JSON):
#   {"a":"get"}                                  -> Zustand + Konfiguration
#   {"a":"set","id":"extra","mode":"on"}         mode: off | on | blink
#   {"a":"set","id":"extra","bright":60}         Helligkeit 5..100 %
#   {"a":"toggle","id":"extra"}                  an <-> aus
#   {"a":"all","mode":"off"}                     alle Lichter an/aus
#   {"a":"config","lights":[...],"invert":false,"blink_ms":400}
#
# Standard ist ein Zusatzlicht an GPIO 25; weitere Lichter lassen sich in der App hinzufuegen.
# Jede Leuchte haengt ueber einen MOSFET/Transistor (oder ein Relais) an einem GPIO.

import json, time, machine, _thread

CFG_FILE = "licht.json"
MODES = ("off", "on", "blink")

DEFAULT = {
    "invert": False,      # True fuer Relais-Module, die bei LOW schalten
    "blink_ms": 400,      # halbe Blinkperiode (400 ms -> ca. 1,25 Hz)
    "pwm_hz": 1000,
    "lights": [
        {"id": "extra", "name": "Zusatzlicht", "pin": 25, "kind": "light"},
    ],
}

_lock = _thread.allocate_lock()
_cfg = None
_state = {}       # id -> {"mode": "off", "bright": 100}
_out = {}         # id -> PWM- oder Pin-Objekt
_err = {}         # id -> Fehlertext (z. B. ungueltiger Pin)
_running = [False]


# ---------------------------------------------------------------- Konfiguration
def _load():
    cfg = json.loads(json.dumps(DEFAULT))
    saved = {}
    try:
        with open(CFG_FILE) as f:
            saved = json.load(f)
        for k in ("invert", "blink_ms", "pwm_hz", "lights"):
            if k in saved:
                cfg[k] = saved[k]
    except Exception:
        pass
    return cfg, saved.get("state", {})


def _save():
    try:
        st = {}
        for l in _cfg["lights"]:
            s = _state.get(l["id"], {})
            st[l["id"]] = {"mode": s.get("mode", "off"), "bright": s.get("bright", 100)}
        d = {k: _cfg[k] for k in ("invert", "blink_ms", "pwm_hz", "lights")}
        d["state"] = st
        with open(CFG_FILE, "w") as f:
            json.dump(d, f)
    except Exception as e:
        print("licht: speichern fehlgeschlagen:", e)


# ---------------------------------------------------------------- Ausgaenge
def _release():
    for o in _out.values():
        try:
            o.deinit()
        except Exception:
            pass
    _out.clear()


def _setup(saved_state=None):
    _release()
    _err.clear()
    old = dict(_state)
    _state.clear()
    for l in _cfg["lights"]:
        lid = l["id"]
        prev = (saved_state or {}).get(lid) or old.get(lid) or {}
        mode = prev.get("mode", "off")
        _state[lid] = {"mode": mode if mode in MODES else "off",
                       "bright": max(5, min(100, int(prev.get("bright", 100))))}
        try:
            p = machine.Pin(int(l["pin"]), machine.Pin.OUT)
            try:
                o = machine.PWM(p, freq=int(_cfg.get("pwm_hz", 1000)))
            except Exception:
                o = p  # Pin ohne PWM: nur an/aus
            _out[lid] = o
        except Exception as e:
            _err[lid] = "GPIO %s: %s" % (l.get("pin"), e)
    _apply()


def _write(o, level):
    # level 0..100 (%)
    if _cfg.get("invert"):
        level = 100 - level
    if isinstance(o, machine.Pin):
        o.value(1 if level >= 50 else 0)
    elif hasattr(o, "duty_u16"):
        o.duty_u16(level * 65535 // 100)
    else:
        o.duty(level * 1023 // 100)


def _apply():
    phase = (time.ticks_ms() // max(50, int(_cfg.get("blink_ms", 400)))) % 2 == 0
    for lid, o in _out.items():
        s = _state.get(lid)
        if not s:
            continue
        m = s["mode"]
        on = m == "on" or (m == "blink" and phase)
        try:
            _write(o, s["bright"] if on else 0)
        except Exception as e:
            _err[lid] = str(e)


def _loop():
    while _running[0]:
        with _lock:
            _apply()
        time.sleep_ms(20)


def _ensure():
    """Initialisiert beim ersten Aufruf (auch wenn app.py nicht laeuft)."""
    global _cfg
    if _cfg is None:
        _cfg, saved = _load()
        _setup(saved)
    if not _running[0]:
        _running[0] = True
        _thread.start_new_thread(_loop, ())


# ---------------------------------------------------------------- Befehle
def _light(lid):
    for l in _cfg["lights"]:
        if l["id"] == lid:
            return l
    raise ValueError("Unbekanntes Licht: %s" % lid)


def _status():
    lights = []
    for l in _cfg["lights"]:
        s = _state.get(l["id"], {"mode": "off", "bright": 100})
        lights.append({"id": l["id"], "name": l.get("name", l["id"]), "pin": l.get("pin"),
                       "kind": l.get("kind", "light"), "mode": s["mode"], "bright": s["bright"],
                       "err": _err.get(l["id"])})
    return {"ok": True, "lights": lights, "invert": _cfg.get("invert", False),
            "blink_ms": _cfg.get("blink_ms", 400)}


def handle(req):
    _ensure()
    a = req.get("a", "get")
    with _lock:
        if a == "get":
            return _status()
        if a == "set":
            _light(req["id"])
            s = _state[req["id"]]
            if "mode" in req:
                if req["mode"] not in MODES:
                    raise ValueError("mode muss off/on/blink sein")
                s["mode"] = req["mode"]
            if "bright" in req:
                s["bright"] = max(5, min(100, int(req["bright"])))
        elif a == "toggle":
            _light(req["id"])
            s = _state[req["id"]]
            s["mode"] = "off" if s["mode"] != "off" else "on"
        elif a == "all":
            mode = req.get("mode", "off")
            if mode not in MODES:
                raise ValueError("mode muss off/on/blink sein")
            for l in _cfg["lights"]:
                _state[l["id"]]["mode"] = mode
        elif a == "config":
            for k in ("invert", "blink_ms", "pwm_hz", "lights"):
                if k in req:
                    _cfg[k] = req[k]
            ids = set()
            for l in _cfg["lights"]:
                if not l.get("id") or l["id"] in ids:
                    raise ValueError("Jede Leuchte braucht eine eindeutige id")
                ids.add(l["id"])
            _setup()
        else:
            raise ValueError("Unbekannte Aktion: %s" % a)
        _apply()
        _save()
        return _status()


def cmd(s):
    """Wird von der App ueber den Agent-Befehl "run" aufgerufen."""
    try:
        r = handle(json.loads(s))
    except Exception as e:
        r = {"ok": False, "err": str(e)}
    print("LICHT:" + json.dumps(r))


def main():
    """Einstieg fuer app.py - startet die Lichtsteuerung beim Booten."""
    _ensure()
    print("Fahrradlicht bereit:", ", ".join("%s=GPIO%s" % (l["name"], l["pin"]) for l in _cfg["lights"]))
    while True:
        time.sleep(60)
