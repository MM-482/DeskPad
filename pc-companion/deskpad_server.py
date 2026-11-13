#!/usr/bin/env python3
"""
DeskPad PC Companion Receiver
Zero-dependency Windows (Win32 SendInput) and Linux fallback receiver for DeskPad Android APK.
Listens for:
  - UDP 48910: Auto-discovery broadcasts (DESKPAD_DISCOVER_V1)
  - UDP 48911: High-frequency pointer move (M|dx|dy), scroll (S|sx|sy), zoom (Z|dz)
  - TCP 48912: Reliable clicks, mouse hold/release, Unicode text, keyboard shortcuts, and ping
"""

import ctypes
import ctypes.wintypes as wintypes
import json
import platform
import socket
import subprocess
import sys
import threading

DISCOVERY_PORT = 48910
UDP_PORT = 48911
TCP_PORT = 48912

# Win32 SendInput structures
INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

VK_MAP = {
    "BACKSPACE": 0x08,
    "TAB": 0x09,
    "ENTER": 0x0D,
    "SHIFT": 0x10,
    "CTRL": 0x11,
    "ALT": 0x12,
    "CAPS": 0x14,
    "ESC": 0x1B,
    "SPACE": 0x20,
    "PGUP": 0x21,
    "PGDN": 0x22,
    "END": 0x23,
    "HOME": 0x24,
    "LEFT": 0x25,
    "UP": 0x26,
    "RIGHT": 0x27,
    "DOWN": 0x28,
    "DELETE": 0x2E,
    "WIN": 0x5B,
    "F1": 0x70,
    "F2": 0x71,
    "F3": 0x72,
    "F4": 0x73,
    "F5": 0x74,
    "F6": 0x75,
    "F7": 0x76,
    "F8": 0x77,
    "F9": 0x78,
    "F10": 0x79,
    "F11": 0x7A,
    "F12": 0x7B,
    "VOL_MUTE": 0xAD,
    "VOL_DOWN": 0xAE,
    "VOL_UP": 0xAF,
    "MEDIA_NEXT": 0xB0,
    "MEDIA_PREV": 0xB1,
    "MEDIA_PLAY_PAUSE": 0xB3,
}

EXTENDED_VKS = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E, 0x5B}


class Win32Injector:
    """Injects mouse and keyboard events directly via Windows user32.SendInput."""

    def __init__(self):
        ULONG_PTR = ctypes.c_size_t

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [
                ("dx", wintypes.LONG),
                ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD),
                ("dwExtraInfo", ULONG_PTR),
            ]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [
                ("wVk", wintypes.WORD),
                ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD),
                ("dwExtraInfo", ULONG_PTR),
            ]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [
                ("uMsg", wintypes.DWORD),
                ("wParamL", wintypes.WORD),
                ("wParamH", wintypes.WORD),
            ]

        class INPUT_UNION(ctypes.Union):
            _fields_ = [
                ("mi", MOUSEINPUT),
                ("ki", KEYBDINPUT),
                ("hi", HARDWAREINPUT),
            ]

        class INPUT(ctypes.Structure):
            _fields_ = [
                ("type", wintypes.DWORD),
                ("union", INPUT_UNION),
            ]

        self._INPUT = INPUT
        self._MOUSEINPUT = MOUSEINPUT
        self._KEYBDINPUT = KEYBDINPUT
        self._user32 = ctypes.WinDLL("user32", use_last_error=True)
        self._user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        self._user32.SendInput.restype = wintypes.UINT

    def _send(self, *inputs):
        arr = (self._INPUT * len(inputs))(*inputs)
        self._user32.SendInput(len(inputs), arr, ctypes.sizeof(self._INPUT))

    def move(self, dx: int, dy: int):
        inp = self._INPUT(type=INPUT_MOUSE)
        inp.union.mi = self._MOUSEINPUT(dx=dx, dy=dy, mouseData=0, dwFlags=MOUSEEVENTF_MOVE, time=0, dwExtraInfo=0)
        self._send(inp)

    def scroll(self, sx: int, sy: int):
        events = []
        if sy != 0:
            inp = self._INPUT(type=INPUT_MOUSE)
            inp.union.mi = self._MOUSEINPUT(
                dx=0, dy=0, mouseData=ctypes.c_uint32(sy * 24).value, dwFlags=MOUSEEVENTF_WHEEL, time=0, dwExtraInfo=0
            )
            events.append(inp)
        if sx != 0:
            inp = self._INPUT(type=INPUT_MOUSE)
            inp.union.mi = self._MOUSEINPUT(
                dx=0, dy=0, mouseData=ctypes.c_uint32(-sx * 24).value, dwFlags=MOUSEEVENTF_HWHEEL, time=0, dwExtraInfo=0
            )
            events.append(inp)
        if events:
            self._send(*events)

    def zoom(self, delta: int):
        if delta == 0:
            return
        self.key_down_vk(VK_MAP["CTRL"])
        self.scroll(0, delta * 3)
        self.key_up_vk(VK_MAP["CTRL"])

    def mouse_button(self, button: str, down: bool):
        flag_map = {
            ("L", True): MOUSEEVENTF_LEFTDOWN,
            ("L", False): MOUSEEVENTF_LEFTUP,
            ("R", True): MOUSEEVENTF_RIGHTDOWN,
            ("R", False): MOUSEEVENTF_RIGHTUP,
            ("M", True): MOUSEEVENTF_MIDDLEDOWN,
            ("M", False): MOUSEEVENTF_MIDDLEUP,
        }
        flag = flag_map.get((button, down))
        if not flag:
            return
        inp = self._INPUT(type=INPUT_MOUSE)
        inp.union.mi = self._MOUSEINPUT(dx=0, dy=0, mouseData=0, dwFlags=flag, time=0, dwExtraInfo=0)
        self._send(inp)

    def click(self, button: str, count: int = 1):
        for _ in range(max(1, count)):
            self.mouse_button(button, True)
            self.mouse_button(button, False)

    def key_down_vk(self, vk: int):
        flags = KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_VKS else 0
        inp = self._INPUT(type=INPUT_KEYBOARD)
        inp.union.ki = self._KEYBDINPUT(wVk=vk, wScan=0, dwFlags=flags, time=0, dwExtraInfo=0)
        self._send(inp)

    def key_up_vk(self, vk: int):
        flags = KEYEVENTF_KEYUP | (KEYEVENTF_EXTENDEDKEY if vk in EXTENDED_VKS else 0)
        inp = self._INPUT(type=INPUT_KEYBOARD)
        inp.union.ki = self._KEYBDINPUT(wVk=vk, wScan=0, dwFlags=flags, time=0, dwExtraInfo=0)
        self._send(inp)

    def send_text(self, text: str):
        utf16 = text.encode("utf-16-le")
        inputs = []
        for i in range(0, len(utf16), 2):
            code_unit = int.from_bytes(utf16[i:i + 2], "little")
            down = self._INPUT(type=INPUT_KEYBOARD)
            down.union.ki = self._KEYBDINPUT(wVk=0, wScan=code_unit, dwFlags=KEYEVENTF_UNICODE, time=0, dwExtraInfo=0)
            up = self._INPUT(type=INPUT_KEYBOARD)
            up.union.ki = self._KEYBDINPUT(
                wVk=0, wScan=code_unit, dwFlags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, time=0, dwExtraInfo=0
            )
            inputs.extend([down, up])
        if inputs:
            self._send(*inputs)

    def send_key(self, key_name: str, modifiers: list):
        mod_vks = [VK_MAP[m] for m in modifiers if m in VK_MAP]
        for vk in mod_vks:
            self.key_down_vk(vk)

        vk = self._resolve_vk(key_name)
        if vk is not None:
            self.key_down_vk(vk)
            self.key_up_vk(vk)
        elif len(key_name) == 1:
            self.send_text(key_name)

        for vk in reversed(mod_vks):
            self.key_up_vk(vk)

    @staticmethod
    def _resolve_vk(key_name: str):
        if key_name in VK_MAP:
            return VK_MAP[key_name]
        if len(key_name) == 1:
            ch = key_name.upper()
            if "A" <= ch <= "Z" or "0" <= ch <= "9":
                return ord(ch)
            oem = {
                ",": 0xBC,
                ".": 0xBE,
                "/": 0xBF,
                ";": 0xBA,
                "'": 0xDE,
                "[": 0xDB,
                "]": 0xDD,
                "\\": 0xDC,
                "-": 0xBD,
                "=": 0xBB,
                "`": 0xC0,
            }
            return oem.get(key_name)
        return None


class NoopOrXdotoolInjector:
    """Fallback injector for Linux testing or xdotool environments."""

    def __init__(self):
        self.events = []
        self._has_xdotool = platform.system() == "Linux" and bool(
            subprocess.run(["which", "xdotool"], capture_output=True).returncode == 0
        )

    def move(self, dx: int, dy: int):
        self.events.append(("move", dx, dy))
        if self._has_xdotool:
            subprocess.Popen(["xdotool", "mousemove_relative", "--", str(dx), str(dy)])

    def scroll(self, sx: int, sy: int):
        self.events.append(("scroll", sx, sy))

    def zoom(self, delta: int):
        self.events.append(("zoom", delta))

    def mouse_button(self, button: str, down: bool):
        self.events.append(("btn", button, down))

    def click(self, button: str, count: int = 1):
        self.events.append(("click", button, count))

    def send_text(self, text: str):
        self.events.append(("text", text))

    def send_key(self, key_name: str, modifiers: list):
        self.events.append(("key", key_name, list(modifiers)))


def create_injector():
    if platform.system() == "Windows":
        return Win32Injector()
    return NoopOrXdotoolInjector()


def handle_udp_packet(payload: str, injector):
    parts = payload.strip().split("|")
    if not parts:
        return
    cmd = parts[0]
    if cmd == "M" and len(parts) == 3:
        injector.move(int(parts[1]), int(parts[2]))
    elif cmd == "S" and len(parts) == 3:
        injector.scroll(int(parts[1]), int(parts[2]))
    elif cmd == "Z" and len(parts) == 2:
        injector.zoom(int(parts[1]))


def handle_tcp_command(line: str, injector):
    msg = json.loads(line)
    kind = msg.get("t")
    if kind == "ping":
        return json.dumps({"t": "pong", "ts": msg.get("ts", 0)})
    if kind == "click":
        injector.click(msg.get("b", "L"), int(msg.get("c", 1)))
    elif kind == "btn":
        injector.mouse_button(msg.get("b", "L"), bool(msg.get("d", False)))
    elif kind == "text":
        injector.send_text(msg.get("v", ""))
    elif kind == "key":
        injector.send_key(msg.get("k", ""), msg.get("m", []))
    return None


def get_local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        host_ips = socket.gethostbyname_ex(socket.gethostname())[2]
        for ip in host_ips:
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def discovery_loop(hostname: str, os_label: str):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", DISCOVERY_PORT))
    offer = f"DESKPAD_OFFER_V1|{hostname}|{os_label}|{TCP_PORT}|{UDP_PORT}".encode("utf-8")
    while True:
        data, addr = sock.recvfrom(512)
        if data.decode("utf-8", errors="ignore").strip() == "DESKPAD_DISCOVER_V1":
            sock.sendto(offer, addr)


def udp_loop(injector):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", UDP_PORT))
    while True:
        data, _ = sock.recvfrom(512)
        try:
            handle_udp_packet(data.decode("utf-8", errors="ignore"), injector)
        except Exception:
            pass


def handle_tcp_client(conn: socket.socket, addr, hostname: str, injector):
    print(f"[+] Connected: {addr[0]}:{addr[1]}")
    try:
        hello = json.dumps({"t": "hello", "name": hostname}) + "\n"
        conn.sendall(hello.encode("utf-8"))
        with conn.makefile("r", encoding="utf-8", errors="ignore") as reader:
            for line in reader:
                line = line.strip()
                if not line:
                    continue
                reply = handle_tcp_command(line, injector)
                if reply:
                    conn.sendall((reply + "\n").encode("utf-8"))
    except Exception:
        pass
    finally:
        print(f"[-] Disconnected: {addr[0]}:{addr[1]}")
        conn.close()


def main():
    hostname = socket.gethostname()
    os_label = f"{platform.system()} {platform.release()}"
    injector = create_injector()

    print("=" * 54)
    print(f"  DeskPad PC Receiver  |  Host: {hostname} ({os_label})")
    print(f"  LAN IPs : {', '.join(get_local_ips())}")
    print(f"  Ports   : Discovery {DISCOVERY_PORT} | UDP {UDP_PORT} | TCP {TCP_PORT}")
    print("  Waiting for DeskPad Android app...")
    print("=" * 54)

    threading.Thread(target=discovery_loop, args=(hostname, os_label), daemon=True).start()
    threading.Thread(target=udp_loop, args=(injector,), daemon=True).start()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", TCP_PORT))
    srv.listen(5)

    try:
        while True:
            conn, addr = srv.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=handle_tcp_client, args=(conn, addr, hostname, injector), daemon=True).start()
    except KeyboardInterrupt:
        print("\nStopping DeskPad PC Receiver.")
        sys.exit(0)


if __name__ == "__main__":
    main()
