"""Phone Controller - PC server with a web UI (HTML + CSS + JavaScript) and a Python backend.

  python app.py             native window (pywebview) if installed, otherwise your browser
  python app.py --browser   always use the browser
"""
import argparse
import collections
import json
import mimetypes
import os
import secrets
import sys
import tempfile
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import core
import winput

VERSION = "4.0"
APP_ID = "com.ff.controller"
CTYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
          ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png",
          ".ico": "image/x-icon", ".json": "application/json"}


class ApiError(Exception):
    pass


def ui_dir():
    return os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "ui")


# ------------------------------------------------------------------ controller
class Controller:
    def __init__(self):
        self.lock = threading.RLock()
        self.logs = collections.deque(maxlen=1500)
        self.log_id = 0
        self.errors = 0
        self.cfg = core.load_cfg(self.log)
        self.hub = None
        self.server = None
        self.keeper = None
        self.adb = None
        self.adb_custom = ""
        self.adb_ver = ""
        self.devices = []
        self.selected = None
        self._sig = None
        self.connected_serial = None
        self.port = int(self.cfg.get("port", 5555))
        self.app_connected = False
        self.rate = 0
        self._last_n = 0
        self.vigem = ""
        self.auto = True
        self.quit_event = threading.Event()
        self.last_beat = time.time() + 30
        self.on_quit = None
        threading.Thread(target=self._loop, daemon=True).start()
        self.check_adb()
        self.log("[+] Ready. Plug your phone (USB debugging ON), pick it, then press Connect.")

    # ---- logging
    def log(self, msg):
        with self.lock:
            self.log_id += 1
            if msg.startswith("[!]"):
                self.errors += 1
            self.logs.append({"id": self.log_id, "t": time.strftime("%H:%M:%S"), "m": msg})

    def logs_since(self, since):
        with self.lock:
            return [x for x in self.logs if x["id"] > since]

    # ---- background loop
    def _loop(self):
        n = 0
        while not self.quit_event.is_set():
            time.sleep(0.2)
            n += 1
            h = self.hub
            if h:
                cur = h.packets
                self.rate = (cur - self._last_n) * 5
                self._last_n = cur
            else:
                self.rate = 0
            if n % 25 == 0 and self.auto and self.adb and not self.connected_serial:
                try:
                    self._refresh_devices(True)
                except Exception as e:  # noqa: BLE001
                    self.log(f"[!] {e}")

    # ---- ADB
    def check_adb(self, custom=None):
        if custom is not None:
            self.adb_custom = custom.strip().strip('"')
        threading.Thread(target=self._check_adb, daemon=True).start()

    def _check_adb(self):
        adb = core.find_adb(self.adb_custom)
        if not adb:
            self.adb = None
            self.log("[!] adb not found. Put adb.exe in ./adb or ./platform-tools, or paste its path in the UI.")
            return
        self.adb = adb
        self.adb_ver = core.adb_version(adb)
        self.log(f"[+] {self.adb_ver}")
        self._refresh_devices(False)

    def _refresh_devices(self, silent):
        devs = core.list_devices(self.adb, info=False)
        sig = [(d["serial"], d["state"]) for d in devs]
        if silent and sig == self._sig:
            return
        self._sig = sig
        devs = core.list_devices(self.adb, info=True)
        with self.lock:
            self.devices = devs
            if not any(d["serial"] == self.selected for d in devs):
                self.selected = devs[0]["serial"] if devs else None
        if not devs:
            self.log("[!] No phone found. Plug USB, enable Developer options → USB debugging.")
        for d in devs:
            if d["state"] == "device":
                self.log(f"[+] Phone: {d['brand']} {d['model']} | Android {d['android']} | "
                         f"battery {d['battery']} | {d['serial']}")
            elif d["state"] == "unauthorized":
                self.log(f"[!] {d['serial']}: unauthorized - tap 'Allow USB debugging' on the phone.")
            else:
                self.log(f"[!] {d['serial']}: state '{d['state']}'")

    def device(self, serial=None):
        serial = serial or self.selected
        return next((d for d in self.devices if d["serial"] == serial), None)

    def select(self, serial):
        self.selected = serial

    def _need_device(self):
        d = self.device()
        if not self.adb:
            raise ApiError("adb not found")
        if not d:
            raise ApiError("No phone selected. Plug in USB and press Refresh.")
        if d["state"] != "device":
            raise ApiError(f"Phone state is '{d['state']}'. Allow USB debugging on the phone.")
        return d

    # ---- connect / disconnect
    def connect(self, port):
        d = self._need_device()
        port = int(port)
        if not 1024 <= port <= 65535:
            raise ApiError("Port must be 1024-65535")
        try:
            if self.hub is None:
                self.hub = core.Hub(self.cfg, self.log)
        except Exception as e:  # noqa: BLE001
            self.vigem = str(e)
            self.log(f"[!] Cannot create virtual gamepad: {e}")
            raise ApiError("ViGEmBus is missing. Install it: github.com/nefarius/ViGEmBus/releases")
        self.vigem = "ok"
        if self.server:
            self.server.stop()
        if self.keeper:
            self.keeper.stop()
        try:
            self.server = core.TcpServer(self.hub, port, self.log, self._on_phone)
            self.server.start()
        except OSError as e:
            self.server = None
            self.log(f"[!] Cannot listen on port {port}: {e}")
            raise ApiError(f"Port {port} is busy")
        self.keeper = core.ReverseKeeper(self.adb, d["serial"], port, self.log)
        self.keeper.start()
        self.connected_serial = d["serial"]
        self.port = port
        self.cfg["port"] = port
        core.save_cfg(self.cfg)
        self.log("[+] Server on. Open the app on your phone (or press 'Open app').")

    def _on_phone(self, ok):
        self.app_connected = ok

    def disconnect(self):
        serial, port = self.connected_serial, self.port
        if self.keeper:
            self.keeper.stop()
            self.keeper = None
        if self.server:
            self.server.stop()
            self.server = None
        if self.adb and serial:
            core.adb_run(self.adb, core.reverse_args(serial, port, remove=True))
        self.connected_serial = None
        self.app_connected = False

    # ---- phone actions
    def _adb_do(self, args, name, timeout=20):
        rc, out, err = core.adb_run(self.adb, args, timeout=timeout)
        lines = (out or err or "").strip().splitlines()
        msg = lines[-1] if lines else ("OK" if rc == 0 else "failed")
        self.log(("[+] " if rc == 0 else "[!] ") + f"{name}: {msg}")
        if rc != 0:
            raise ApiError(f"{name} failed: {msg}")
        return msg

    def open_app(self):
        d = self._need_device()
        self._adb_do(["-s", d["serial"], "shell", "monkey", "-p", APP_ID, "-c",
                      "android.intent.category.LAUNCHER", "1"], "Open app")

    def install_apk(self, name, data):
        d = self._need_device()
        path = os.path.join(tempfile.gettempdir(), "pc_upload_" + os.path.basename(name or "app.apk"))
        with open(path, "wb") as f:
            f.write(data)
        self.log(f"[~] Installing {os.path.basename(name)} ({len(data) // 1024} KB)…")
        try:
            return self._adb_do(["-s", d["serial"], "install", "-r", path], "Install", timeout=180)
        finally:
            try:
                os.remove(path)
            except OSError:
                pass

    # ---- tools
    def restart_adb(self):
        if not self.adb:
            raise ApiError("adb not found")
        self.log("[~] Restarting ADB server…")
        core.adb_run(self.adb, ["kill-server"])
        core.adb_run(self.adb, ["start-server"], timeout=20)
        self._check_adb()

    def remove_reverse(self):
        if not self.adb:
            raise ApiError("adb not found")
        self._adb_do(["reverse", "--remove-all"], "Remove tunnels")

    def check_vigem(self):
        if self.hub:
            self.vigem = "ok"
            self.log("[+] ViGEmBus OK (virtual Xbox pad is active)")
            return "ok"
        ok, err = core.check_vigem()
        self.vigem = "ok" if ok else err
        self.log("[+] ViGEmBus OK" if ok else f"[!] ViGEmBus problem: {err}")
        if not ok:
            raise ApiError("ViGEmBus problem: " + err)
        return "ok"

    def release_all(self):
        if self.hub:
            self.hub.release_all()
            self.log("[~] All keys/buttons released")

    def rumble_test(self):
        if not (self.hub and self.app_connected):
            raise ApiError("Connect the phone app first")
        self.hub.rumble_test()
        self.log("[~] Rumble test sent")

    def open_folder(self):
        try:
            os.startfile(core.app_dir())  # type: ignore[attr-defined]
        except Exception as e:  # noqa: BLE001
            raise ApiError(str(e))

    def save_log(self):
        folder = os.path.join(core.app_dir(), "logs")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, time.strftime("log-%Y%m%d-%H%M%S.txt"))
        with open(path, "w", encoding="utf-8") as f:
            for x in self.logs_since(0):
                f.write(f"{x['t']} {x['m']}\n")
        self.log(f"[+] Log saved: {path}")
        return path

    def clear_logs(self):
        with self.lock:
            self.logs.clear()
            self.errors = 0

    # ---- config
    def update_cfg(self, patch):
        def merge(a, b):
            for k, v in b.items():
                if isinstance(v, dict) and isinstance(a.get(k), dict):
                    merge(a[k], v)
                else:
                    a[k] = v
        with self.lock:
            merge(self.cfg, patch)
            core.save_cfg(self.cfg)

    # ---- state for the UI
    def state(self):
        h = self.hub
        d = (h.last if h else {}) or {}
        live = {
            "mode": (h.mode if d else ""),
            "ms": bool(h.ms_on) if h else False,
            "lx": float(d.get("lx", 0)), "ly": float(d.get("ly", 0)),
            "rx": float(d.get("rx", 0)), "ry": float(d.get("ry", 0)),
            "lt": float(d.get("lt", 0)), "rt": float(d.get("rt", 0)),
            "b": list(d.get("b", [])),
            "rate": self.rate, "rtt": (h.rtt if h else -1),
            "rumble": list(h._rumble) if h else [0, 0],
        }
        return {
            "version": VERSION,
            "adb": {"ok": bool(self.adb), "path": self.adb or self.adb_custom, "version": self.adb_ver},
            "devices": self.devices, "selected": self.selected,
            "server": {"on": bool(self.server), "port": self.port, "app": self.app_connected,
                       "serial": self.connected_serial},
            "vigem": self.vigem, "auto": self.auto, "errors": self.errors,
            "live": live,
        }

    def shutdown(self):
        self.quit_event.set()
        try:
            self.disconnect()
        except Exception:  # noqa: BLE001
            pass
        if self.hub:
            self.hub.release_all()


# ------------------------------------------------------------------ http
def make_handler(ctrl, token):
    def post_routes():
        return {
            "check_adb": lambda b: ctrl.check_adb(b.get("custom")),
            "select": lambda b: ctrl.select(b.get("serial")),
            "connect": lambda b: ctrl.connect(b.get("port", ctrl.port)),
            "disconnect": lambda b: ctrl.disconnect(),
            "open_app": lambda b: ctrl.open_app(),
            "restart_adb": lambda b: ctrl.restart_adb(),
            "remove_reverse": lambda b: ctrl.remove_reverse(),
            "check_vigem": lambda b: ctrl.check_vigem(),
            "release_all": lambda b: ctrl.release_all(),
            "rumble_test": lambda b: ctrl.rumble_test(),
            "open_folder": lambda b: ctrl.open_folder(),
            "save_log": lambda b: {"path": ctrl.save_log()},
            "clear_logs": lambda b: ctrl.clear_logs(),
            "config": lambda b: ctrl.update_cfg(b),
            "auto": lambda b: setattr(ctrl, "auto", bool(b.get("on"))),
            "quit": lambda b: (ctrl.quit_event.set(), ctrl.on_quit and ctrl.on_quit()),
        }

    routes = post_routes()

    class H(BaseHTTPRequestHandler):
        server_version = "PhoneController/" + VERSION

        def log_message(self, *a):
            pass

        def _json(self, obj, code=200):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _guard(self):
            host = self.headers.get("Host", "").split(":")[0]
            if host not in ("127.0.0.1", "localhost"):
                self._json({"error": "forbidden"}, 403)
                return False
            return True

        def _auth(self):
            if self.headers.get("X-Token") != token:
                self._json({"error": "unauthorized"}, 401)
                return False
            ctrl.last_beat = time.time()
            return True

        def do_GET(self):
            if not self._guard():
                return
            u = urlparse(self.path)
            if u.path.startswith("/api/"):
                if not self._auth():
                    return
                name = u.path[5:]
                if name == "state":
                    return self._json(ctrl.state())
                if name == "logs":
                    since = int(parse_qs(u.query).get("since", ["0"])[0])
                    return self._json({"logs": ctrl.logs_since(since), "errors": ctrl.errors})
                if name == "cfg":
                    return self._json({"cfg": ctrl.cfg, "keys": sorted(winput.SC) + [
                        "mouse:left", "mouse:right", "mouse:middle", "scroll:up", "scroll:down"]})
                return self._json({"error": "not found"}, 404)
            self._static(u.path)

        def _static(self, path):
            rel = "index.html" if path in ("/", "") else path.lstrip("/")
            base = os.path.normpath(ui_dir())
            full = os.path.normpath(os.path.join(base, rel))
            if not full.startswith(base) or not os.path.isfile(full):
                self.send_response(404)
                self.end_headers()
                return
            with open(full, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", CTYPES.get(os.path.splitext(full)[1].lower(),
                                                       mimetypes.guess_type(full)[0] or "application/octet-stream"))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if not self._guard() or not self._auth():
                return
            u = urlparse(self.path)
            name = u.path[5:] if u.path.startswith("/api/") else ""
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b""
            try:
                if name == "install":
                    fname = parse_qs(u.query).get("name", ["app.apk"])[0]
                    return self._json({"ok": True, "msg": ctrl.install_apk(fname, raw)})
                fn = routes.get(name)
                if not fn:
                    return self._json({"error": "not found"}, 404)
                body = json.loads(raw.decode("utf-8")) if raw else {}
                res = fn(body)
                out = {"ok": True}
                if isinstance(res, dict):
                    out.update(res)
                return self._json(out)
            except ApiError as e:
                return self._json({"error": str(e)}, 400)
            except (ValueError, KeyError, TypeError) as e:
                return self._json({"error": f"bad request: {e}"}, 400)
            except Exception as e:  # noqa: BLE001
                ctrl.log(f"[!] {type(e).__name__}: {e}")
                return self._json({"error": str(e)}, 500)

    return H


# ------------------------------------------------------------------ launcher
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--browser", action="store_true", help="open the UI in the default browser")
    ap.add_argument("--port", type=int, default=0, help="UI port (default: random free port)")
    args = ap.parse_args()
    if sys.platform != "win32":
        sys.exit("This app needs Windows (ViGEmBus + SendInput).")

    ctrl = Controller()
    token = secrets.token_urlsafe(16)
    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(ctrl, token))
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{httpd.server_address[1]}/?t={token}"

    if not args.browser:
        try:
            import webview
            win = webview.create_window("Phone Controller", url, width=1200, height=800,
                                        min_size=(1020, 700), background_color="#0a0e1c")
            ctrl.on_quit = lambda: win.destroy()
            webview.start(gui="edgechromium")
            ctrl.shutdown()
            return
        except Exception as e:  # noqa: BLE001
            ctrl.log(f"[~] Native window unavailable ({type(e).__name__}); using your browser")

    print("Phone Controller UI:", url)
    webbrowser.open(url)
    ctrl.last_beat = time.time() + 30
    while not ctrl.quit_event.is_set():
        time.sleep(1)
        if time.time() - ctrl.last_beat > 25:   # browser tab closed
            break
    ctrl.shutdown()


if __name__ == "__main__":
    main()
