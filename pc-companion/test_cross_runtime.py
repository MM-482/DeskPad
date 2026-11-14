r"""Independent Python TLS client -> C# receiver, only loopback and fake input.

Requires Windows Framework csc and a caller-selected existing scratch directory:
  python test_cross_runtime.py --work C:\path\to\work
No pairing credential is printed. Temporary credentials and binaries are removed.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    csc = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    if not csc.is_file():
        raise SystemExit("Windows .NET Framework compiler required for this optional cross-runtime check")
    with tempfile.TemporaryDirectory(prefix="deskpad-cross-runtime-", dir=args.work) as directory:
        temporary = Path(directory)
        executable, metadata = temporary / "Harness.exe", temporary / "metadata.json"
        command = [str(csc), "/nologo", "/target:exe", "/platform:x64", "/define:CROSS_RUNTIME_HARNESS",
                   "/main:DeskPadPC.CrossRuntimeHarness", "/out:" + str(executable),
                   "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/r:System.Web.Extensions.dll",
                   "/r:System.Security.dll", str(source / "DeskPadPC.cs"), str(source / "SecureServer.cs"),
                   str(source / "LanSecurity.cs"), str(source / "FirewallRules.cs"), str(source / "PairingQrSupport.cs"), str(source / "PinPairing.cs"),
                   "/r:" + str(source / "vendor/BouncyCastle-2.7.0/BouncyCastle.Cryptography.dll"),
                   "/resource:" + str(source / "vendor/BouncyCastle-2.7.0/BouncyCastle.Cryptography.dll") + ",DeskPad.BouncyCastle.dll",
                   "/r:" + str(source / "vendor/QRCoder-1.4.3/QRCoder.dll"),
                   "/resource:" + str(source / "vendor/QRCoder-1.4.3/QRCoder.dll") + ",DeskPad.QRCoder.dll",
                   str(source / "CrossRuntimeHarness.cs")]
        subprocess.run(command, check=True, capture_output=True, timeout=40)
        process = subprocess.Popen([str(executable), str(metadata)], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
        clients = []
        try:
            # Wait for file and READY without printing its credential.
            end = time.monotonic() + 20
            while not metadata.is_file() and time.monotonic() < end and process.poll() is None:
                time.sleep(0.02)
            if not metadata.is_file():
                raise AssertionError("C# test harness did not start")
            assert process.stdout.readline().strip() == "READY", "Harness readiness failed"
            config = json.loads(metadata.read_text(encoding="utf-8-sig"))
            assert config["publicKeyBits"] >= 2048, "Generated C# certificate RSA key is only " + str(config["publicKeyBits"]) + " bits"
            bundle = base64.urlsafe_b64decode(config["pairingCode"][4:] + "==")
            assert len(bundle) == 64 and len(config["pairingCode"]) == 90, "Pairing bundle format"
            der = base64.b64decode(config["certificate"])
            assert secrets.compare_digest(bundle[:32], hashlib.sha256(der).digest()), "Fingerprint mismatch"
            token = base64.urlsafe_b64encode(bundle[32:]).decode().rstrip("=")
            context = ssl.create_default_context(cadata=ssl.DER_cert_to_PEM_cert(der))
            context.check_hostname = False
            context.minimum_version = ssl.TLSVersion.TLSv1_2

            def connect(auth_token=token):
                raw = socket.create_connection(("127.0.0.1", config["port"]), timeout=3)
                tls = context.wrap_socket(raw, server_hostname="DeskPad PC")
                clients.append(tls)
                assert secrets.compare_digest(hashlib.sha256(tls.getpeercert(binary_form=True)).digest(), bundle[:32]), "TLS pin mismatch"
                tls.sendall((json.dumps({"t": "auth", "v": 2, "token": auth_token}) + "\n").encode())
                return tls

            def read(tls):
                data = bytearray()
                while len(data) < 16385:
                    value = tls.recv(1)
                    if not value:
                        return None
                    if value == b"\n":
                        return json.loads(data.decode("utf-8"))
                    data.extend(value)
                raise AssertionError("Oversized C# reply")

            def stats():
                process.stdin.write("stats\n")
                process.stdin.flush()
                return json.loads(process.stdout.readline())

            wrong = connect(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="))
            assert read(wrong) is None and stats() == [], "Wrong token injected input"
            wrong.close()
            tls = connect()
            acknowledgement = read(tls)
            assert (acknowledgement is not None and acknowledgement.get("t") == "auth_ok"
                    and type(acknowledgement.get("v")) is int and acknowledgement["v"] == 2
                    and isinstance(acknowledgement.get("name"), str) and acknowledgement["name"]), "Authentication acknowledgement mismatch"
            assert tls.version() == "TLSv1.2", "C# TLS protocol mismatch"
            commands = [{"t": "move", "dx": 13, "dy": -4}, {"t": "scroll", "sx": 1, "sy": -2},
                        {"t": "zoom", "delta": 2}, {"t": "click", "b": "R", "c": 2},
                        {"t": "text", "v": "Hello 🌍"}, {"t": "key", "k": "ENTER", "m": ["CTRL"]},
                        {"t": "btn", "b": "L", "d": True}, {"t": "ping", "ts": 1234567890123}]
            tls.sendall(b"".join((json.dumps(value, ensure_ascii=True) + "\n").encode() for value in commands))
            assert read(tls) == {"t": "pong", "ts": 1234567890123}, "Timestamp interop failed"
            assert stats() == ["move:13:-4", "scroll:1:-2", "zoom:2", "click:R:2", "text:Hello 🌍", "key:ENTER", "btn:L:down"], "Input interop mismatch"
            tls.close()
            end = time.monotonic() + 3
            while time.monotonic() < end:
                if stats()[-1:] == ["btn:L:up"]:
                    break
                time.sleep(0.02)
            else:
                raise AssertionError("Held button was not released after disconnect")
            print("PASS Python TLS client -> C# receiver: bundle/pin/auth/Unicode/all-input/ping/cleanup")
        finally:
            for client in clients:
                client.close()
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait(timeout=5)
            for pipe in (process.stdin, process.stdout, process.stderr):
                pipe.close()


if __name__ == "__main__":
    main()
