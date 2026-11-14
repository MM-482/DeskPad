"""Security tests: loopback TLS and recording injectors; never desktop input."""
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import stat
import subprocess
import tempfile
import threading
import time
import unittest
from unittest import mock

import deskpad_server as server


class RecordingInjector:
    def __init__(self):
        self.events = []

    def move(self, dx, dy):
        self.events.append(("move", dx, dy))

    def scroll(self, sx, sy):
        self.events.append(("scroll", sx, sy))

    def zoom(self, delta):
        self.events.append(("zoom", delta))

    def mouse_button(self, button, down):
        self.events.append(("btn", button, down))

    def click(self, button, count):
        self.events.append(("click", button, count))

    def send_text(self, text):
        self.events.append(("text", text))

    def send_key(self, key, modifiers):
        self.events.append(("key", key, modifiers))


def temporary_directory():
    return tempfile.TemporaryDirectory(prefix="deskpad-python-test-", dir=os.environ.get("DESKPAD_TEST_TEMP_DIR"))


def wait_for(predicate, timeout=2):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(0.01)
    return predicate()


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.injector = RecordingInjector()

    def test_reject_invalid_commands_before_injection(self):
        cases = [
            {"t": "move", "dx": True, "dy": 0}, {"t": "move", "dx": 1.0, "dy": 0},
            {"t": "move", "dx": 32768, "dy": 0}, {"t": "scroll", "sx": -32768, "sy": 0},
            {"t": "zoom", "delta": "1"}, {"t": "click", "b": "L", "c": 3},
            {"t": "click", "b": "L", "c": True}, {"t": "click", "b": "X", "c": 1},
            {"t": "btn", "b": "L", "d": 1}, {"t": "btn", "b": "L", "d": "false"},
            {"t": "text", "v": 123}, {"t": "text", "v": "\ud800"},
            {"t": "text", "v": "x" * 2049}, {"t": "text", "v": "🌍" * 1025},
            {"t": "key", "k": "NOT_A_KEY", "m": []}, {"t": "key", "k": "é", "m": []},
            {"t": "key", "k": "a", "m": "CTRL"}, {"t": "key", "k": "a", "m": [[]]},
            {"t": "key", "k": "a", "m": ["CTRL", "CTRL"]},
            {"t": "key", "k": "a", "m": ["CTRL", "BOGUS"]},
            {"t": "ping", "ts": True}, {"t": "move", "dx": 1},
            {"t": "move", "dx": 1, "dy": 0, "extra": 1}, {"t": "unknown"},
        ]
        for fields in cases:
            with self.subTest(kind=fields["t"]), self.assertRaises(server.ProtocolError):
                server.handle_tcp_command(json.dumps(fields), self.injector)
        self.assertFalse(self.injector.events)

    def test_valid_limits_use_utf16_units_and_known_modifiers(self):
        held = set()
        for msg in ({"t": "move", "dx": -32767, "dy": 32767}, {"t": "text", "v": "🌍" * 1024},
                    {"t": "key", "k": "F12", "m": ["CTRL", "ALT", "SHIFT", "WIN"]},
                    {"t": "btn", "b": "L", "d": True}):
            server.handle_tcp_command(json.dumps(msg), self.injector, held)
        self.assertEqual(len(self.injector.events), 4)
        self.assertEqual(held, {"L"})

    def test_duplicate_non_json_and_nonfinite_frames_rejected(self):
        for line in ('{"t":"move","dx":1,"dx":2,"dy":0}', '[]', 'null',
                     '{"t":"move","dx":NaN,"dy":0}', 'M|1|2', '{"t":1}'):
            with self.subTest(line=line), self.assertRaises(server.ProtocolError):
                server.handle_tcp_command(line, self.injector)
        self.assertFalse(self.injector.events)

    def test_canonical_pairing_bundle_and_authentication(self):
        token, fingerprint = secrets.token_bytes(32), secrets.token_bytes(32)
        code = server.pairing_code(fingerprint, token)
        self.assertEqual(len(code), 90)
        self.assertTrue(code.startswith("DP2:"))
        self.assertEqual(base64.urlsafe_b64decode(code[4:] + "=="), fingerprint + token)
        encoded = server.encode_urlsafe(token)
        self.assertTrue(server.authenticate(json.dumps({"t": "auth", "v": 2, "token": encoded}), token))
        self.assertFalse(server.authenticate(json.dumps({"t": "auth", "v": 2,
                                                       "token": server.encode_urlsafe(secrets.token_bytes(32))}), token))
        for version in (1, True, 2.0, "2"):
            with self.assertRaises(server.ProtocolError):
                server.authenticate(json.dumps({"t": "auth", "v": version, "token": encoded}), token)
        for credential in ("", encoded + "=", "A" * 42, "A" * 44, encoded + "\n", True):
            with self.assertRaises(server.ProtocolError):
                server.decode_token(credential)

    def test_legacy_udp_entry_points_are_removed(self):
        for name in ("udp_loop", "handle_udp_packet", "UDP_PORT"):
            self.assertFalse(hasattr(server, name))


class FrameTests(unittest.TestCase):
    def setUp(self):
        self.left, self.right = socket.socketpair()
        self.addCleanup(self.left.close)
        self.addCleanup(self.right.close)
        self.reader = server.FrameReader(self.right)

    def test_exact_limit_and_multiple_frames(self):
        payload = b"x" * server.MAX_FRAME_BYTES + b"\n{}\n"
        worker = threading.Thread(target=self.left.sendall, args=(payload,))
        worker.start()
        self.assertEqual(len(self.reader.read(time.monotonic() + 2)), server.MAX_FRAME_BYTES)
        self.assertEqual(self.reader.read(time.monotonic() + 2), "{}")
        worker.join(2)

    def test_oversized_frame_without_newline(self):
        self.left.sendall(b"x" * (server.MAX_FRAME_BYTES + 1))
        with self.assertRaises(server.ProtocolError):
            self.reader.read(time.monotonic() + 2)

    def test_bad_utf8_empty_and_incomplete(self):
        for payload in (b"\xff\n", b"\n", b"incomplete"):
            with self.subTest(payload=payload):
                left, right = socket.socketpair()
                try:
                    left.sendall(payload)
                    left.shutdown(socket.SHUT_WR)
                    with self.assertRaises(server.ProtocolError):
                        server.FrameReader(right).read(time.monotonic() + 1)
                finally:
                    left.close()
                    right.close()

    def test_deadline_and_clean_eof(self):
        with self.assertRaises(TimeoutError):
            self.reader.read(time.monotonic() + 0.03)
        self.left.shutdown(socket.SHUT_WR)
        self.assertIsNone(self.reader.read(time.monotonic() + 1))


class IdentityTests(unittest.TestCase):
    def test_random_identity_persists_and_corruption_fails_closed(self):
        with temporary_directory() as directory:
            path = Path(directory) / "identity.bin"
            first = server.load_or_create_token(path)
            self.assertEqual(len(first), 32)
            self.assertEqual(first, server.load_or_create_token(path))
            self.assertNotEqual(first, server.load_or_create_token(Path(directory) / "other.bin"))
            if os.name == "nt":
                self.assertNotIn(first, path.read_bytes())
                self.assertTrue(path.read_bytes().startswith(b"DP2-DPAPI\0"))
            else:
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            path.write_bytes(b"invalid or corrupted identity")
            with self.assertRaises(ValueError):
                server.load_or_create_token(path)

    @unittest.skipIf(os.name == "nt", "POSIX file permissions")
    def test_insecure_permissions_and_symlinks_rejected(self):
        with temporary_directory() as directory:
            path = Path(directory) / "identity.bin"
            server.load_or_create_token(path)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                server.load_or_create_token(path)
            alias = Path(directory) / "alias.bin"
            alias.symlink_to(path)
            with self.assertRaises(ValueError):
                server.load_or_create_token(alias)

    def test_missing_tls_material_never_creates_receiver(self):
        with mock.patch.object(server, "SecureReceiver") as receiver, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(server.main(["--cert", "missing.pem", "--key", "missing.key"]), 1)
            receiver.assert_not_called()

    def test_pairing_display_refuses_redirected_output(self):
        output = io.StringIO()
        with mock.patch.object(server, "load_tls_material") as load, contextlib.redirect_stdout(output), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(server.main(["--cert", "missing", "--key", "missing", "--show-pairing-code"]), 1)
            load.assert_not_called()
        self.assertNotIn("DP2:", output.getvalue())


class TlsReceiverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        executable = shutil.which("openssl")
        if executable is None:
            candidate = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git" / "usr" / "bin" / "openssl.exe"
            executable = str(candidate) if candidate.is_file() else None
        if executable is None:
            raise unittest.SkipTest("OpenSSL is needed only for an ephemeral TLS test certificate")
        cls.directory = temporary_directory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.cert = Path(cls.directory.name) / "cert.pem"
        cls.key = Path(cls.directory.name) / "key.pem"
        subprocess.run([executable, "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1", "-nodes",
                        "-keyout", str(cls.key), "-out", str(cls.cert), "-subj", "/CN=DeskPad Test"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        cls.context, cls.fingerprint = server.load_tls_material(cls.cert, cls.key)

    def setUp(self):
        self.token, self.injector = secrets.token_bytes(32), RecordingInjector()
        self.receiver = server.SecureReceiver(self.context, self.token, self.injector, hostname="DeskPad test",
                                             bind_host="127.0.0.1", tcp_port=0, discovery_port=0,
                                             handshake_timeout=0.5, idle_timeout=0.8).start()
        self.addCleanup(self.receiver.close)
        self.client_context = ssl.create_default_context(cafile=str(self.cert))
        self.client_context.check_hostname = False
        self.client_context.minimum_version = ssl.TLSVersion.TLSv1_2

    def client(self, authenticate=True, credential=None, pin=None):
        raw = socket.create_connection(("127.0.0.1", self.receiver.tcp_port), timeout=2)
        conn = self.client_context.wrap_socket(raw, server_hostname="DeskPad Test")
        self.addCleanup(conn.close)
        expected = self.fingerprint if pin is None else pin
        if not secrets.compare_digest(hashlib.sha256(conn.getpeercert(binary_form=True)).digest(), expected):
            conn.close()
            raise ssl.SSLCertVerificationError("Pinned certificate mismatch")
        if authenticate:
            self.send(conn, {"t": "auth", "v": 2, "token": credential or server.encode_urlsafe(self.token)})
        return conn

    @staticmethod
    def send(conn, msg):
        conn.sendall((json.dumps(msg, ensure_ascii=True, separators=(",", ":")) + "\n").encode("utf-8"))

    def read(self, conn):
        line = server.FrameReader(conn).read(time.monotonic() + 2)
        return None if line is None else json.loads(line)

    def test_authenticated_tls_input_and_held_cleanup(self):
        conn = self.client()
        self.assertEqual(self.read(conn), {"t": "auth_ok", "v": 2, "name": "DeskPad test"})
        self.assertIn(conn.version(), ("TLSv1.2", "TLSv1.3"))
        for msg in ({"t": "move", "dx": 4, "dy": -2}, {"t": "scroll", "sx": 1, "sy": 2},
                    {"t": "zoom", "delta": 1}, {"t": "click", "b": "R", "c": 2},
                    {"t": "text", "v": "🌍"}, {"t": "key", "k": "c", "m": ["CTRL"]},
                    {"t": "btn", "b": "L", "d": True}, {"t": "ping", "ts": 123}):
            self.send(conn, msg)
        self.assertEqual(self.read(conn), {"t": "pong", "ts": 123})
        self.assertEqual(self.injector.events[:3], [("move", 4, -2), ("scroll", 1, 2), ("zoom", 1)])
        conn.close()
        self.assertTrue(wait_for(lambda: self.injector.events[-1:] == [("btn", "L", False)]))

    def test_wrong_or_missing_auth_cannot_inject(self):
        cases = [{"t": "move", "dx": 9, "dy": 9},
                 {"t": "auth", "v": 1, "token": server.encode_urlsafe(self.token)},
                 {"t": "auth", "v": True, "token": server.encode_urlsafe(self.token)},
                 {"t": "auth", "v": 2, "token": server.encode_urlsafe(secrets.token_bytes(32))},
                 {"t": "auth", "v": 2, "token": ""}]
        for msg in cases:
            with self.subTest(kind=msg["t"], version=msg.get("v")):
                conn = self.client(authenticate=False)
                self.send(conn, msg)
                self.assertIsNone(self.read(conn))
                conn.close()
        self.assertFalse(self.injector.events)

    def test_plaintext_legacy_client_rejected(self):
        with socket.create_connection(("127.0.0.1", self.receiver.tcp_port), timeout=2) as conn:
            conn.sendall(b'{"t":"move","dx":1,"dy":1}\n')
            try:
                self.assertEqual(conn.recv(128), b"")
            except ConnectionResetError:
                pass
        self.assertFalse(self.injector.events)

    def test_other_client_cannot_steal_or_release_active_buttons(self):
        first = self.client()
        self.assertEqual(self.read(first)["t"], "auth_ok")
        self.send(first, {"t": "btn", "b": "L", "d": True})
        self.send(first, {"t": "ping", "ts": 1})
        self.assertEqual(self.read(first)["t"], "pong")
        wrong = self.client(credential=server.encode_urlsafe(secrets.token_bytes(32)))
        self.assertIsNone(self.read(wrong))
        second = self.client()
        self.assertIsNone(self.read(second))
        self.assertEqual(self.injector.events, [("btn", "L", True)])
        self.send(first, {"t": "ping", "ts": 2})
        self.assertEqual(self.read(first), {"t": "pong", "ts": 2})
        first.close()
        self.assertTrue(wait_for(lambda: self.injector.events[-1:] == [("btn", "L", False)]))

    def test_oversized_and_invalid_frames_close_without_input(self):
        for payload in (b"x" * (server.MAX_FRAME_BYTES + 1), b'{"t":"move","dx":true,"dy":0}\n', b"\xff\n"):
            with self.subTest(payload_size=len(payload)):
                conn = self.client()
                self.assertEqual(self.read(conn)["t"], "auth_ok")
                conn.sendall(payload)
                self.assertIsNone(self.read(conn))
                conn.close()
                self.assertTrue(wait_for(lambda: not self.receiver._controller.locked()))
        self.assertFalse(self.injector.events)

    def test_idle_and_auth_deadlines(self):
        unauthenticated = self.client(authenticate=False)
        self.assertIsNone(self.read(unauthenticated))
        authenticated = self.client()
        self.assertEqual(self.read(authenticated)["t"], "auth_ok")
        self.send(authenticated, {"t": "btn", "b": "R", "d": True})
        self.assertIsNone(self.read(authenticated))
        self.assertTrue(wait_for(lambda: ("btn", "R", False) in self.injector.events))

    def test_pin_mismatch_stops_authentication(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.client(pin=b"\0" * 32)
        self.assertFalse(self.injector.events)
        self.assertFalse(self.receiver._controller.locked())

    def test_discovery_no_credentials_and_legacy_udp_ignored(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as conn:
            conn.settimeout(0.1)
            address = ("127.0.0.1", self.receiver.discovery_port)
            for query in (b"DESKPAD_DISCOVER_V1", b"M|12|4", b"S|1|2", b"Z|2"):
                conn.sendto(query, address)
                with self.assertRaises(socket.timeout):
                    conn.recvfrom(1024)
            conn.sendto(server.DISCOVER_QUERY, address)
            reply, _ = conn.recvfrom(1024)
        fields = reply.decode().split("|")
        self.assertEqual(fields[0], "DESKPAD_OFFER_V2")
        self.assertEqual(len(fields), 4)
        self.assertEqual(int(fields[3]), self.receiver.tcp_port)
        self.assertNotIn(server.encode_urlsafe(self.token).encode(), reply)
        self.assertNotIn(b"DP2:", reply)
        self.assertFalse(self.injector.events)
        self.assertEqual(len(self.receiver._listeners), 2)

    def test_pending_handshake_limit(self):
        pending = [socket.create_connection(("127.0.0.1", self.receiver.tcp_port), timeout=2)
                   for _ in range(server.MAX_PENDING_HANDSHAKES)]
        for conn in pending:
            self.addCleanup(conn.close)
        self.assertTrue(wait_for(lambda: len(self.receiver._clients) == server.MAX_PENDING_HANDSHAKES))
        with socket.create_connection(("127.0.0.1", self.receiver.tcp_port), timeout=2) as extra:
            try:
                self.assertEqual(extra.recv(1), b"")
            except ConnectionResetError:
                pass
        self.assertFalse(self.injector.events)

    def test_wrong_key_fails_before_listeners(self):
        with self.assertRaises(ssl.SSLError):
            server.load_tls_material(self.cert, self.cert)


if __name__ == "__main__":
    unittest.main()
