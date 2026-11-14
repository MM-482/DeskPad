#!/usr/bin/env python3
"""DeskPad V2 authenticated TLS receiver using only Python's standard library.

Windows input uses SendInput. The non-Windows injector remains a limited testing
fallback, not a complete Linux receiver. UDP 48910 only discovers the service;
every input event travels through authenticated TLS on TCP 48912. Legacy V1
and UDP motion input are deliberately unsupported.
"""

import argparse
import base64
import ctypes
import ctypes.wintypes as wintypes
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import socket
import ssl
import stat
import subprocess
import sys
import threading
import time

DISCOVERY_PORT = 48910
TCP_PORT = 48912
DISCOVER_QUERY = b"DESKPAD_DISCOVER_V2"
MAX_FRAME_BYTES = 16384  # UTF-8 payload, excluding the terminating LF.
MAX_TEXT_UTF16_UNITS = 2048
HANDSHAKE_TIMEOUT = 5.0
IDLE_TIMEOUT = 10.0
MAX_PENDING_HANDSHAKES = 4
MODIFIERS = frozenset({"CTRL", "ALT", "SHIFT", "WIN"})

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
        try:
            self.scroll(0, delta * 3)
        finally:
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
        pressed = []
        try:
            for vk in mod_vks:
                pressed.append(vk)
                self.key_down_vk(vk)
            vk = self._resolve_vk(key_name)
            if vk is not None:
                try:
                    self.key_down_vk(vk)
                finally:
                    self.key_up_vk(vk)
            elif len(key_name) == 1:
                self.send_text(key_name)
        finally:
            for vk in reversed(pressed):
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


class ProtocolError(ValueError):
    """Invalid input; exception messages deliberately never include frame data."""


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("Duplicate field")
        result[key] = value
    return result


def _invalid_number(_):
    raise ProtocolError("Invalid number")


def parse_message(line):
    try:
        msg = json.loads(line, object_pairs_hook=_unique_object,
                         parse_constant=_invalid_number)
    except (ValueError, TypeError, RecursionError):
        raise ProtocolError("Invalid JSON frame") from None
    if type(msg) is not dict or type(msg.get("t")) is not str:
        raise ProtocolError("Expected command object")
    return msg


def _integer(msg, field, low=-32767, high=32767):
    value = msg.get(field)
    if type(value) is not int or not low <= value <= high:
        raise ProtocolError("Invalid integer field")
    return value


def validate_command(msg):
    """Validate completely before dispatching any input to an injector."""
    kind = msg.get("t")
    fields = {
        "ping": {"t", "ts"}, "move": {"t", "dx", "dy"},
        "scroll": {"t", "sx", "sy"}, "zoom": {"t", "delta"},
        "click": {"t", "b", "c"}, "btn": {"t", "b", "d"},
        "text": {"t", "v"}, "key": {"t", "k", "m"},
    }
    if kind not in fields or set(msg) != fields[kind]:
        raise ProtocolError("Unknown command or fields")
    if kind == "ping":
        _integer(msg, "ts", 0, 9223372036854775807)
    elif kind == "move":
        _integer(msg, "dx")
        _integer(msg, "dy")
    elif kind == "scroll":
        _integer(msg, "sx")
        _integer(msg, "sy")
    elif kind == "zoom":
        _integer(msg, "delta")
    elif kind in ("click", "btn"):
        if type(msg["b"]) is not str or msg["b"] not in ("L", "R", "M"):
            raise ProtocolError("Invalid button")
        if kind == "click":
            _integer(msg, "c", 1, 2)
        elif type(msg["d"]) is not bool:
            raise ProtocolError("Invalid button state")
    elif kind == "text":
        if type(msg["v"]) is not str:
            raise ProtocolError("Invalid text")
        try:
            length = len(msg["v"].encode("utf-16-le")) // 2
        except UnicodeEncodeError:
            raise ProtocolError("Invalid Unicode text") from None
        if length > MAX_TEXT_UTF16_UNITS:
            raise ProtocolError("Text too long")
    elif kind == "key":
        key, mods = msg["k"], msg["m"]
        if type(key) is not str or not (key in VK_MAP or (len(key) == 1 and 32 <= ord(key) <= 126)):
            raise ProtocolError("Invalid key")
        if (type(mods) is not list or len(mods) > 4
                or any(type(mod) is not str or mod not in MODIFIERS for mod in mods)
                or len(set(mods)) != len(mods)):
            raise ProtocolError("Invalid modifiers")


def handle_tcp_command(line, injector, held_buttons=None):
    """Dispatch one already-authenticated session frame (not a network entry point)."""
    msg = parse_message(line)
    validate_command(msg)
    kind = msg["t"]
    if kind == "ping":
        return json.dumps({"t": "pong", "ts": msg["ts"]}, separators=(",", ":"))
    if kind == "move":
        injector.move(msg["dx"], msg["dy"])
    elif kind == "scroll":
        injector.scroll(msg["sx"], msg["sy"])
    elif kind == "zoom":
        injector.zoom(msg["delta"])
    elif kind == "click":
        injector.click(msg["b"], msg["c"])
    elif kind == "btn":
        if held_buttons is not None:
            if msg["d"]:
                held_buttons.add(msg["b"])
            else:
                held_buttons.discard(msg["b"])
        injector.mouse_button(msg["b"], msg["d"])
    elif kind == "text":
        injector.send_text(msg["v"])
    elif kind == "key":
        injector.send_key(msg["k"], msg["m"])
    return None


class FrameReader:
    def __init__(self, conn):
        self.conn = conn
        self.buffer = bytearray()

    def read(self, deadline):
        while True:
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                if newline > MAX_FRAME_BYTES:
                    raise ProtocolError("Frame too long")
                frame = bytes(self.buffer[:newline])
                del self.buffer[:newline + 1]
                if not frame:
                    raise ProtocolError("Empty frame")
                try:
                    return frame.decode("utf-8", errors="strict")
                except UnicodeDecodeError:
                    raise ProtocolError("Invalid UTF-8") from None
            if len(self.buffer) > MAX_FRAME_BYTES:
                raise ProtocolError("Frame too long")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Frame deadline")
            self.conn.settimeout(remaining)
            chunk = self.conn.recv(min(4096, MAX_FRAME_BYTES + 1 - len(self.buffer)))
            if not chunk:
                if self.buffer:
                    raise ProtocolError("Incomplete frame")
                return None
            self.buffer.extend(chunk)


def encode_urlsafe(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def decode_token(value):
    if type(value) is not str or not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
        raise ProtocolError("Invalid credential")
    raw = base64.b64decode(value + "=", altchars=b"-_", validate=True)
    if len(raw) != 32 or encode_urlsafe(raw) != value:
        raise ProtocolError("Invalid credential")
    return raw


def authenticate(line, token):
    msg = parse_message(line)
    if (set(msg) != {"t", "v", "token"} or msg["t"] != "auth"
            or type(msg["v"]) is not int or msg["v"] != 2):
        raise ProtocolError("Authentication required")
    return secrets.compare_digest(decode_token(msg["token"]), token)


def pairing_code(cert_fingerprint, token):
    if len(cert_fingerprint) != 32 or len(token) != 32:
        raise ValueError("Invalid pairing material")
    return "DP2:" + encode_urlsafe(cert_fingerprint + token)


def _windows_protect(data, decrypt=False):
    class DataBlob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(DataBlob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
    function.restype = wintypes.BOOL
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    source = ctypes.create_string_buffer(data)
    blob = DataBlob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_ubyte)))
    output = DataBlob()
    if not function(ctypes.byref(blob), None, None, None, None, 1, ctypes.byref(output)):
        raise ValueError("Could not protect or read local pairing identity")
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel.LocalFree(output.pbData)


def load_or_create_token(path):
    """Persist only user-local material: DPAPI on Windows, owner-only on POSIX."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    header = b"DP2-DPAPI\0" if os.name == "nt" else b"DP2-RAW\0"
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or getattr(info, "st_file_attributes", 0) & 1024:
            raise ValueError("Pairing identity must be a regular local file")
        fd = os.open(path, flags)
    except FileNotFoundError:
        token = secrets.token_bytes(32)
        payload = header + (_windows_protect(token) if os.name == "nt" else token)
        create_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        try:
            fd = os.open(path, create_flags, 0o600)
        except FileExistsError:
            return load_or_create_token(path)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        return token
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or (os.name != "nt"
                and (info.st_uid != os.getuid() or info.st_mode & 0o077)):
            raise ValueError("Pairing identity must be readable only by its owner")
        payload = source.read(4097)
    if len(payload) > 4096 or not payload.startswith(header):
        raise ValueError("Invalid local pairing identity")
    token = payload[len(header):]
    if os.name == "nt":
        token = _windows_protect(token, decrypt=True)
    if len(token) != 32:
        raise ValueError("Invalid local pairing identity")
    return token


def load_tls_material(cert_path, key_path):
    """Load and validate local TLS credentials before opening any listener."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(str(cert_path), str(key_path))
    with open(cert_path, "r", encoding="ascii") as source:
        pem = source.read(1024 * 1024)
    match = re.search(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", pem, re.DOTALL)
    if match is None:
        raise ValueError("A PEM server certificate is required")
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(match.group())).digest()
    return context, fingerprint


def _discovery_field(value):
    return "".join(ch for ch in value if ch.isprintable() and ch != "|")[:64] or "DeskPad PC"


class SecureReceiver:
    """V2 listeners; tests can provide a fake injector and loopback-only ports."""
    def __init__(self, context, token, injector, hostname=None, bind_host="0.0.0.0",
                 tcp_port=TCP_PORT, discovery_port=DISCOVERY_PORT,
                 handshake_timeout=HANDSHAKE_TIMEOUT, idle_timeout=IDLE_TIMEOUT):
        if not isinstance(context, ssl.SSLContext) or len(token) != 32:
            raise ValueError("TLS context and pairing identity are required")
        if context.minimum_version < ssl.TLSVersion.TLSv1_2:
            raise ValueError("TLS 1.2 or newer is required")
        self.context, self.token, self.injector = context, token, injector
        self.hostname = _discovery_field(hostname or socket.gethostname())
        self.bind_host, self.tcp_port, self.discovery_port = bind_host, tcp_port, discovery_port
        self.handshake_timeout, self.idle_timeout = handshake_timeout, idle_timeout
        self._controller = threading.Lock()
        self._pending = threading.BoundedSemaphore(MAX_PENDING_HANDSHAKES)
        self._stop = threading.Event()
        self._clients_lock = threading.Lock()
        self._clients = set()
        self._workers = set()
        self._listeners = []
        self._threads = []

    def start(self):
        if self._listeners or self._stop.is_set():
            raise RuntimeError("Use a fresh receiver instance to start")
        try:
            tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._listeners.append(tcp)
            if os.name == "nt":
                tcp.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            tcp.bind((self.bind_host, self.tcp_port))
            tcp.listen(MAX_PENDING_HANDSHAKES)
            tcp.settimeout(0.2)
            self.tcp_port = tcp.getsockname()[1]
            discovery = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._listeners.append(discovery)
            if os.name == "nt":
                discovery.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            discovery.bind((self.bind_host, self.discovery_port))
            discovery.settimeout(0.2)
            self.discovery_port = discovery.getsockname()[1]
            for target, listener in ((self._accept_loop, tcp), (self._discovery_loop, discovery)):
                worker = threading.Thread(target=target, args=(listener,), daemon=True)
                self._threads.append(worker)
                worker.start()
            return self
        except BaseException:
            self.close()
            raise

    def _discovery_loop(self, sock):
        # OS is a compatibility label; non-Windows input is still only a testing fallback.
        os_label = "Windows" if platform.system() == "Windows" else "Testing"
        offer = f"DESKPAD_OFFER_V2|{self.hostname}|{os_label}|{self.tcp_port}".encode("utf-8")
        last_reply = {}
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(513)
                now = time.monotonic()
                if data != DISCOVER_QUERY or now - last_reply.get(addr[0], -1e9) < 0.25:
                    continue
                if len(last_reply) >= 256:
                    last_reply.clear()
                last_reply[addr[0]] = now
                sock.sendto(offer, addr)
            except socket.timeout:
                continue
            except OSError:
                break

    def _accept_loop(self, listener):
        while not self._stop.is_set():
            try:
                conn, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if self._stop.is_set():
                conn.close()
                break
            if not self._pending.acquire(blocking=False):
                conn.close()
                continue
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._clients_lock:
                if self._stop.is_set():
                    self._pending.release()
                    conn.close()
                    break
                self._clients.add(conn)
                worker = threading.Thread(target=self._handle_client, args=(conn,), daemon=True)
                self._workers.add(worker)
            try:
                worker.start()
            except BaseException:
                with self._clients_lock:
                    self._clients.discard(conn)
                    self._workers.discard(worker)
                self._pending.release()
                conn.close()
                raise

    def _handle_client(self, raw):
        conn, owns_controller, pending_held = raw, False, True
        held_buttons = set()
        try:
            deadline = time.monotonic() + self.handshake_timeout
            raw.settimeout(self.handshake_timeout)
            conn = self.context.wrap_socket(raw, server_side=True, do_handshake_on_connect=False)
            with self._clients_lock:
                self._clients.discard(raw)
                if self._stop.is_set():
                    return
                self._clients.add(conn)
            conn.do_handshake()
            if self._stop.is_set():
                return
            reader = FrameReader(conn)
            line = reader.read(deadline)
            if line is None or not authenticate(line, self.token):
                return
            if not self._controller.acquire(blocking=False):
                return
            owns_controller = True
            self._pending.release()
            pending_held = False
            conn.settimeout(self.idle_timeout)
            reply = json.dumps({"t": "auth_ok", "v": 2, "name": self.hostname}, separators=(",", ":"))
            conn.sendall((reply + "\n").encode("utf-8"))
            while not self._stop.is_set():
                line = reader.read(time.monotonic() + self.idle_timeout)
                if line is None or self._stop.is_set():
                    break
                reply = handle_tcp_command(line, self.injector, held_buttons)
                if reply is not None:
                    conn.sendall((reply + "\n").encode("utf-8"))
        except Exception:
            # Never log credentials or attacker-controlled payloads.
            pass
        finally:
            if owns_controller:
                try:
                    for button in sorted(held_buttons):
                        try:
                            self.injector.mouse_button(button, False)
                        except Exception:
                            pass
                finally:
                    self._controller.release()
            if pending_held:
                self._pending.release()
            conn.close()
            with self._clients_lock:
                self._clients.discard(raw)
                self._clients.discard(conn)
                self._workers.discard(threading.current_thread())

    def close(self):
        self._stop.set()
        for listener in self._listeners:
            listener.close()
        with self._clients_lock:
            clients = list(self._clients)
            workers = list(self._workers)
        for conn in clients:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            conn.close()
        for worker in self._threads + workers:
            if worker is not threading.current_thread() and worker.ident is not None:
                worker.join(timeout=self.handshake_timeout + 1)
        self._listeners.clear()


def default_token_path():
    base = Path(os.environ.get("LOCALAPPDATA", Path.home())) if os.name == "nt" else Path.home() / ".local" / "share"
    return base / "DeskPad PC" / "python-pairing-identity.bin"


def main(argv=None):
    parser = argparse.ArgumentParser(description="DeskPad V2 TLS receiver. V1/plaintext input is not supported.")
    parser.add_argument("--cert", required=True, type=Path, help="Your server's PEM certificate (never a shared key)")
    parser.add_argument("--key", required=True, type=Path, help="Private key PEM matching --cert")
    parser.add_argument("--identity-file", type=Path, default=default_token_path(), help="User-local protected token file")
    parser.add_argument("--show-pairing-code", action="store_true", help="Reveal the pairing credential in an interactive terminal")
    args = parser.parse_args(argv)
    receiver = None
    try:
        if args.show_pairing_code and not sys.stdout.isatty():
            raise ValueError("Pairing credentials may only be revealed in an interactive terminal")
        context, fingerprint = load_tls_material(args.cert, args.key)
        token = load_or_create_token(args.identity_file)
        if args.show_pairing_code:
            print("Pairing credential — enter only in your own DeskPad phone; do not share or record it:")
            print(pairing_code(fingerprint, token))
        receiver = SecureReceiver(context, token, create_injector()).start()
        print("DeskPad V2: TLS TCP 48912; discovery UDP 48910. Legacy input is disabled.")
        print("Pair using the PC's DP2 credential; discovery alone does not establish trust.")
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, ssl.SSLError) as exc:
        print("DeskPad secure receiver could not start: " + str(exc), file=sys.stderr)
        return 1
    finally:
        if receiver is not None:
            receiver.close()


if __name__ == "__main__":
    sys.exit(main())
