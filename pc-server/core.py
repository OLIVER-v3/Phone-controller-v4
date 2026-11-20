"""Core logic shared by the CLI (server.py) and the GUI (gui.py)."""
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

import winput

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

DEFAULT_CFG = {
    "port": 5555,
    "stick_key_threshold": 0.35,
    "mouse_overlay": {"mouse_sens": 900, "deadzone": 0.12},
    "profiles": {
        "minecraft": {
            "keys": {"A": "space", "B": "lshift", "X": "e", "Y": "q",
                     "LB": "scroll:up", "RB": "scroll:down",
                     "LT": "mouse:right", "RT": "mouse:left",
                     "START": "esc", "BACK": "tab",
                     "UP": "t", "DOWN": "f5", "LEFT": "f1", "RIGHT": "f3",
                     "LS": "lctrl", "RS": "f"},
            "mouse_sens": 1000, "deadzone": 0.12, "hold_rmb_on_look": False,
        },
        "roblox": {
            "keys": {"A": "space", "B": "lshift", "X": "e", "Y": "r",
                     "LB": "scroll:up", "RB": "scroll:down",
                     "LT": "mouse:right", "RT": "mouse:left",
                     "START": "esc", "BACK": "tab",
                     "UP": "slash", "DOWN": "3", "LEFT": "1", "RIGHT": "2"},
            "mouse_sens": 700, "deadzone": 0.12, "hold_rmb_on_look": True,
        },
        # Racing wheel with "Keyboard output": steering = A / D, gas = W, brake = S
        "racing": {
            "keys": {"RT": "w", "LT": "s", "A": "space", "B": "lshift", "X": "h", "Y": "c",
                     "LB": "q", "RB": "e", "UP": "l", "DOWN": "v", "LEFT": "z", "RIGHT": "x",
                     "START": "esc", "BACK": "r"},
            "deadzone": 0.12, "steer_threshold": 0.25,
        },
    },
}


# ---------------------------------------------------------------- config
def app_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def cfg_path():
    return os.path.join(app_dir(), "config.json")


def load_cfg(log=print):
    cfg = json.loads(json.dumps(DEFAULT_CFG))
    path = cfg_path()
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                user = json.load(f)
            for k, v in user.items():
                if k == "profiles" and isinstance(v, dict):
                    for name, prof in v.items():
                        cfg["profiles"].setdefault(name, {}).update(prof)
                elif isinstance(v, dict) and isinstance(cfg.get(k), dict):
                    cfg[k].update(v)
                else:
                    cfg[k] = v
            log(f"[+] Loaded {path}")
        except (OSError, ValueError) as e:
            log(f"[!] config.json ignored: {e}")
    return cfg


def save_cfg(cfg):
    with open(cfg_path(), "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    return cfg_path()


# ---------------------------------------------------------------- vgamepad (lazy)
_VG = None


def _vg():
    global _VG
    if _VG is None:
        import vgamepad as vg
        B = vg.XUSB_BUTTON
        _VG = (vg, {
            "A": B.XUSB_GAMEPAD_A, "B": B.XUSB_GAMEPAD_B, "X": B.XUSB_GAMEPAD_X, "Y": B.XUSB_GAMEPAD_Y,
            "LB": B.XUSB_GAMEPAD_LEFT_SHOULDER, "RB": B.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "START": B.XUSB_GAMEPAD_START, "BACK": B.XUSB_GAMEPAD_BACK,
            "UP": B.XUSB_GAMEPAD_DPAD_UP, "DOWN": B.XUSB_GAMEPAD_DPAD_DOWN,
            "LEFT": B.XUSB_GAMEPAD_DPAD_LEFT, "RIGHT": B.XUSB_GAMEPAD_DPAD_RIGHT,
            "LS": B.XUSB_GAMEPAD_LEFT_THUMB, "RS": B.XUSB_GAMEPAD_RIGHT_THUMB,
        })
    return _VG


def check_vigem():
    """(ok, error) - can we create a virtual Xbox pad?"""
    try:
        vg, _ = _vg()
        pad = vg.VX360Gamepad()
        del pad
        return True, ""
    except Exception as e:  # noqa: BLE001
        return False, str(e)


# ---------------------------------------------------------------- ADB
def find_adb(custom=""):
    cands = []
    if custom:
        cands.append(custom)
    d = app_dir()
    cands += [os.path.join(d, "adb", "adb.exe"), os.path.join(d, "platform-tools", "adb.exe")]
    w = shutil.which("adb")
    if w:
        cands.append(w)
    la = os.environ.get("LOCALAPPDATA")
    if la:
        cands.append(os.path.join(la, "Android", "Sdk", "platform-tools", "adb.exe"))
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return None


def adb_run(adb, args, timeout=10):
    try:
        r = subprocess.run([adb] + list(args), capture_output=True, text=True, timeout=timeout,
                           creationflags=NO_WINDOW, encoding="utf-8", errors="replace")
        return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"
    except OSError as e:
        return 1, "", str(e)


def adb_version(adb):
    _, out, _ = adb_run(adb, ["version"])
    return out.splitlines()[0] if out else "?"


def _fill_info(adb, d):
    s = d["serial"]

    def prop(k):
        return adb_run(adb, ["-s", s, "shell", "getprop", k], timeout=6)[1]

    d["brand"] = prop("ro.product.brand")
    m = prop("ro.product.model")
    if m:
        d["model"] = m
    d["android"] = prop("ro.build.version.release")
    _, out, _ = adb_run(adb, ["-s", s, "shell", "dumpsys", "battery"], timeout=6)
    mo = re.search(r"level:\s*(\d+)", out)
    d["battery"] = (mo.group(1) + "%") if mo else ""


def list_devices(adb, info=True):
    _, out, _ = adb_run(adb, ["devices", "-l"], timeout=15)
    lines = out.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("List of devices")), -1)
    devs = []
    for line in lines[start + 1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        d = {"serial": parts[0], "state": parts[1], "brand": "", "model": "", "android": "", "battery": ""}
        for p in parts[2:]:
            if p.startswith("model:"):
                d["model"] = p[6:].replace("_", " ")
        devs.append(d)
    if info:
        for d in devs:
            if d["state"] == "device":
                _fill_info(adb, d)
    return devs


def reverse_args(serial, port, remove=False):
    a = ["-s", serial] if serial else []
    if remove:
        return a + ["reverse", "--remove", f"tcp:{port}"]
    return a + ["reverse", f"tcp:{port}", f"tcp:{port}"]


class ReverseKeeper:
    """Keeps `adb reverse` alive so re-plugging the cable just works."""

    def __init__(self, adb, serial, port, log=print):
        self.adb, self.serial, self.port, self.log = adb, serial, port, log
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _run(self):
        prev = None
        while True:
            rc, out, err = adb_run(self.adb, reverse_args(self.serial, self.port), timeout=8)
            ok = rc == 0
            if ok != prev:
                self.log("[+] adb reverse OK (USB tunnel ready)" if ok
                         else f"[!] adb reverse failed: {err or out or 'no device?'}")
                prev = ok
            if self._stop.wait(3):
                break


# ---------------------------------------------------------------- Hub
class Hub:
    """Turns phone packets into a virtual Xbox pad and/or keyboard + mouse input."""

    def __init__(self, cfg, log=print):
        vg, self.PAD = _vg()
        self.cfg = cfg
        self.log = log
        self.pad = vg.VX360Gamepad()
        self.lock = threading.RLock()
        self.mode = "pad"
        self.use_pad = True         # True: virtual Xbox pad; False: keyboard + mouse
        self.ms_on = False          # gamepad mode + "mouse" toggle
        self.ms_held = set()
        self.pad_held = set()
        self.held = set()
        self.rx = self.ry = 0.0
        self.rmb_look = False
        self.packets = 0
        self.last = {}
        self.rtt = -1               # latency measured by the phone (ms)
        self.sender = None          # set by TcpServer: send(obj) -> phone
        self.tp_held = set()        # touchpad mouse buttons currently held
        self._rumble = (0.0, 0.0)
        self._rumble_cb = self._on_rumble   # keep a reference alive
        try:
            self.pad.register_notification(callback_function=self._rumble_cb)
        except Exception as e:  # noqa: BLE001
            self.log(f"[~] Rumble not available: {e}")
        threading.Thread(target=self._mouse_loop, daemon=True).start()

    # ---- helpers
    @staticmethod
    def _press(t):
        if t.startswith("scroll:"):
            winput.wheel(120 if t.endswith("up") else -120)
        elif t.startswith("mouse:"):
            winput.mouse_button(t[6:], True)
        else:
            winput.key(t, True)

    @staticmethod
    def _release(t):
        if t.startswith("scroll:"):
            return
        if t.startswith("mouse:"):
            winput.mouse_button(t[6:], False)
        else:
            winput.key(t, False)

    def _ms_release(self):
        for t in list(self.ms_held):
            self._release(t)
        self.ms_held.clear()
        self.rx = self.ry = 0.0

    def release_all(self):
        with self.lock:
            self.pad.reset()
            self.pad.update()
            self.pad_held.clear()
            for t in list(self.held):
                self._release(t)
            self.held.clear()
            self._ms_release()
            self.ms_on = False
            if self.rmb_look:
                winput.mouse_button("right", False)
                self.rmb_look = False
            for b in list(self.tp_held):
                winput.mouse_button(b, False)
            self.tp_held.clear()
            self._rumble = (0.0, 0.0)
            self.last = {}

    # ---- gamepad
    def apply_pad(self, d):
        p = self.pad
        p.left_joystick_float(float(d.get("lx", 0)), float(d.get("ly", 0)))
        p.right_joystick_float(float(d.get("rx", 0)), float(d.get("ry", 0)))
        p.left_trigger_float(float(d.get("lt", 0)))
        p.right_trigger_float(float(d.get("rt", 0)))
        now = {self.PAD[n] for n in d.get("b", []) if n in self.PAD}
        for b in now - self.pad_held:
            p.press_button(button=b)
        for b in self.pad_held - now:
            p.release_button(button=b)
        self.pad_held = now
        p.update()

    def apply_pad_mouse(self, d):
        """Gamepad mode with mouse ON: right stick = cursor, RT = left click, LT = right click."""
        dd = dict(d)
        dd.update(rx=0, ry=0, lt=0, rt=0)
        self.apply_pad(dd)
        self.rx = float(d.get("rx", 0))
        self.ry = float(d.get("ry", 0))
        want = set()
        if float(d.get("rt", 0)) > 0.5:
            want.add("mouse:left")
        if float(d.get("lt", 0)) > 0.5:
            want.add("mouse:right")
        for t in want - self.ms_held:
            self._press(t)
        for t in self.ms_held - want:
            self._release(t)
        self.ms_held = want

    # ---- keyboard + mouse
    def apply_kbm(self, d, prof):
        th = prof.get("steer_threshold", self.cfg["stick_key_threshold"])
        want = set()
        lx, ly = float(d.get("lx", 0)), float(d.get("ly", 0))
        if ly > th: want.add("w")
        if ly < -th: want.add("s")
        if lx < -th: want.add("a")
        if lx > th: want.add("d")
        names = list(d.get("b", []))
        if float(d.get("lt", 0)) > 0.5: names.append("LT")
        if float(d.get("rt", 0)) > 0.5: names.append("RT")
        keys = prof.get("keys", {})
        for n in names:
            t = keys.get(n)
            if t:
                want.add(t)
        for t in want - self.held:
            self._press(t)
        for t in self.held - want:
            self._release(t)
        self.held = want
        self.rx = float(d.get("rx", 0))
        self.ry = float(d.get("ry", 0))

    def _mouse_loop(self):
        acc_x = acc_y = 0.0
        last = time.perf_counter()
        while True:
            time.sleep(0.008)
            now = time.perf_counter()
            dt = now - last
            last = now
            with self.lock:
                if self.use_pad:
                    if not self.ms_on:
                        acc_x = acc_y = 0.0
                        continue
                    prof = self.cfg.get("mouse_overlay", {})
                else:
                    prof = self.cfg["profiles"].get(self.mode)
                    if prof is None:
                        continue
                dz = prof.get("deadzone", 0.12)
                sens = prof.get("mouse_sens", 1000)
                mag = math.hypot(self.rx, self.ry)
                moving = mag > dz
                if moving:
                    m = min(1.0, (mag - dz) / (1 - dz)) ** 2
                    acc_x += self.rx / mag * m * sens * dt
                    acc_y += -self.ry / mag * m * sens * dt
                if prof.get("hold_rmb_on_look"):
                    if moving and not self.rmb_look:
                        winput.mouse_button("right", True)
                        self.rmb_look = True
                    elif not moving and self.rmb_look:
                        winput.mouse_button("right", False)
                        self.rmb_look = False
                ix, iy = int(acc_x), int(acc_y)
                acc_x -= ix
                acc_y -= iy
                if ix or iy:
                    winput.mouse_move(ix, iy)

    # ---- phone link (rumble, pong)
    def send(self, obj):
        s = self.sender
        if s:
            s(obj)

    def _on_rumble(self, client, target, large_motor, small_motor, led_number, user_data):
        l, r = round(large_motor / 255.0, 2), round(small_motor / 255.0, 2)
        if (l, r) == self._rumble:
            return
        self._rumble = (l, r)
        self.send({"t": "rumble", "l": l, "r": r})

    def rumble_test(self):
        self.send({"t": "rumble", "l": 1.0, "r": 1.0})
        threading.Timer(0.5, lambda: self.send({"t": "rumble", "l": 0, "r": 0})).start()

    # ---- messages
    def state(self, d):
        mode = d.get("mode", "pad")
        # "racing" drives a virtual Xbox pad unless the phone asks for keyboard output (kb = 1)
        use_pad = mode == "pad" or (mode == "racing" and not int(d.get("kb", 0)))
        with self.lock:
            self.last = d
            if mode != self.mode or use_pad != self.use_pad:
                self.release_all()
                self.last = d
                self.mode = mode
                self.use_pad = use_pad
                extra = ""
                if mode == "racing":
                    extra = " (Xbox pad)" if use_pad else " (keyboard)"
                self.log(f"[~] Mode: {mode}{extra}")
            if use_pad:
                ms = bool(int(d.get("ms", 0))) and mode == "pad"
                if ms != self.ms_on:
                    self._ms_release()
                    self.ms_on = ms
                    self.log(f"[~] Mouse control: {'ON' if ms else 'OFF'}")
                if ms:
                    self.apply_pad_mouse(d)
                else:
                    self.apply_pad(d)
                    self.rx = self.ry = 0.0
            else:
                prof = self.cfg["profiles"].get(mode)
                if prof is not None:
                    self.apply_kbm(d, prof)

    def handle(self, d):
        self.packets += 1
        t = d.get("t", "s")
        if t == "s":
            self.state(d)
        elif t == "key":
            k = str(d.get("k", ""))
            if "d" in d:
                winput.key(k, bool(d["d"]))
            else:
                winput.tap(k)
        elif t == "text":
            s = str(d.get("s", ""))
            self.log(f"[~] Typing {len(s)} chars")
            winput.type_text(s)
            if d.get("enter"):
                winput.tap("enter")
        elif t == "mv":
            winput.mouse_move(int(d.get("dx", 0)), int(d.get("dy", 0)))
        elif t == "scroll":
            winput.wheel(120 * max(-5, min(5, int(d.get("d", 0)))))
        elif t == "click":
            b = str(d.get("b", "left"))
            winput.mouse_button(b, True)
            time.sleep(0.02)
            winput.mouse_button(b, False)
        elif t == "mb":
            b = str(d.get("b", "left"))
            down = bool(int(d.get("d", 0)))
            winput.mouse_button(b, down)
            (self.tp_held.add if down else self.tp_held.discard)(b)
        elif t == "ping":
            self.rtt = int(d.get("p", -1))
            self.send({"t": "pong", "ts": d.get("ts", 0)})


# ---------------------------------------------------------------- TCP server
class TcpServer:
    def __init__(self, hub, port, log=print, on_phone=None):
        self.hub, self.port, self.log = hub, port, log
        self.on_phone = on_phone or (lambda connected: None)
        self._stop = threading.Event()
        self._sock = None
        self._thread = None
        self._conn = None
        self._send_lock = threading.Lock()
        hub.sender = self.send

    def send(self, obj):
        c = self._conn
        if c is None:
            return
        try:
            data = (json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8")
            with self._send_lock:
                c.sendall(data)
        except OSError:
            pass

    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", self.port))
        s.listen(1)
        s.settimeout(0.5)
        self._sock = s
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self.log(f"[+] Listening on 127.0.0.1:{self.port}")

    def stop(self):
        self._stop.set()
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
        if self._thread:
            self._thread.join(timeout=2)
        self.hub.release_all()
        self.log("[-] Server stopped")

    def _run(self):
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            self._serve(conn)

    def _serve(self, conn):
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(0.5)
        self.log("[+] Phone connected")
        self._conn = conn
        self.send({"t": "hello", "v": 4})
        self.on_phone(True)
        buf = b""
        last = time.time()
        try:
            while not self._stop.is_set():
                try:
                    chunk = conn.recv(4096)
                except socket.timeout:
                    if time.time() - last > 3:  # phone sends a keep-alive every 0.5s
                        self.log("[!] Phone timed out")
                        break
                    continue
                if not chunk:
                    break
                last = time.time()
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if not line.strip():
                        continue
                    try:
                        self.hub.handle(json.loads(line.decode("utf-8")))
                    except ValueError:
                        continue
                    except Exception as e:  # noqa: BLE001
                        self.log(f"[!] {type(e).__name__}: {e}")
        except OSError:
            pass
        finally:
            self._conn = None
            self.hub.release_all()
            conn.close()
            self.log("[-] Phone disconnected")
            self.on_phone(False)
