"""Windows SendInput helpers: scancode keys, unicode text, relative mouse, wheel."""
import ctypes
import time
from ctypes import wintypes

user32 = ctypes.windll.user32
ULONG_PTR = ctypes.c_size_t

KEYEVENTF_EXTENDEDKEY = 0x1
KEYEVENTF_KEYUP = 0x2
KEYEVENTF_UNICODE = 0x4
KEYEVENTF_SCANCODE = 0x8


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


def _send(*inputs):
    n = len(inputs)
    arr = (INPUT * n)(*inputs)
    user32.SendInput(n, arr, ctypes.sizeof(INPUT))


def _kb(scan, flags):
    i = INPUT(type=1)
    i.ki = KEYBDINPUT(0, scan, flags, 0, 0)
    return i


def _ms(dx=0, dy=0, data=0, flags=0):
    i = INPUT(type=0)
    i.mi = MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
    return i


# ---- scancodes (set 1) ----
SC = {}
for _row, _base in (("qwertyuiop", 0x10), ("asdfghjkl", 0x1E), ("zxcvbnm", 0x2C)):
    for _i, _ch in enumerate(_row):
        SC[_ch] = (_base + _i, False)
for _i, _ch in enumerate("1234567890"):
    SC[_ch] = (0x02 + _i, False)
for _i in range(1, 11):
    SC[f"f{_i}"] = (0x3A + _i, False)
SC["f11"] = (0x57, False)
SC["f12"] = (0x58, False)
SC.update({
    "esc": (0x01, False), "tab": (0x0F, False), "enter": (0x1C, False),
    "backspace": (0x0E, False), "space": (0x39, False),
    "lshift": (0x2A, False), "lctrl": (0x1D, False), "lalt": (0x38, False),
    "slash": (0x35, False), "grave": (0x29, False),
    "up": (0x48, True), "down": (0x50, True), "left": (0x4B, True), "right": (0x4D, True),
})


def key(name, down):
    if name not in SC:
        return
    sc, ext = SC[name]
    flags = KEYEVENTF_SCANCODE | (KEYEVENTF_EXTENDEDKEY if ext else 0) | (0 if down else KEYEVENTF_KEYUP)
    _send(_kb(sc, flags))


def tap(name, hold=0.02):
    key(name, True)
    time.sleep(hold)
    key(name, False)


def type_text(s):
    """Type any text (incl. Khmer/emoji) using unicode input events."""
    for ch in s:
        if ch == "\n":
            tap("enter")
            continue
        data = ch.encode("utf-16-le")
        for k in range(0, len(data), 2):
            unit = int.from_bytes(data[k:k + 2], "little")
            _send(_kb(unit, KEYEVENTF_UNICODE), _kb(unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        time.sleep(0.003)


# ---- mouse ----
_BTN = {"left": (0x2, 0x4), "right": (0x8, 0x10), "middle": (0x20, 0x40)}


def mouse_button(name, down):
    if name in _BTN:
        _send(_ms(flags=_BTN[name][0 if down else 1]))


def mouse_move(dx, dy):
    _send(_ms(dx, dy, 0, 0x1))


def wheel(delta):
    _send(_ms(data=delta, flags=0x800))
