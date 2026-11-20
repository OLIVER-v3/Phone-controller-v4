"""Headless server (no GUI):  python server.py [--port 5555]
For the GUI app run:       python gui.py
"""
import argparse
import sys
import time

if sys.platform != "win32":
    sys.exit("This server needs Windows (ViGEmBus + SendInput).")

import core


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int)
    args = ap.parse_args()
    cfg = core.load_cfg()
    port = args.port or cfg["port"]
    try:
        hub = core.Hub(cfg, print)
    except Exception as e:  # noqa: BLE001
        sys.exit(f"[!] Cannot create virtual gamepad: {e}\n    Install ViGEmBus: https://github.com/nefarius/ViGEmBus/releases")
    srv = core.TcpServer(hub, port, print)
    srv.start()
    adb = core.find_adb()
    keeper = None
    if adb:
        keeper = core.ReverseKeeper(adb, None, port, print)
        keeper.start()
    else:
        print(f"[!] adb not found. Put it in ./adb or ./platform-tools, or run: adb reverse tcp:{port} tcp:{port}")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        if keeper:
            keeper.stop()
        srv.stop()


if __name__ == "__main__":
    main()
