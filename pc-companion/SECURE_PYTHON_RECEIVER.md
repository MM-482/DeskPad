# Python receiver: secure V2 setup

This receiver uses Python's standard library on Windows, including `ssl` and
`ctypes`. It requires a Python build with TLS support and an individually
provisioned PEM server certificate and matching private key. It does not generate
or download a shared certificate. There is no plaintext fallback.

The non-Windows injector is a limited development fallback, not a complete Linux
or macOS receiver. The setup below is for Windows.

## Start and pair

1. Provision a unique RSA certificate of at least 2048 bits with a SHA-256
   signature and matching PEM private key. Keep the key readable only by your
   Windows account. Certificate generation is a separate provisioning step; the
   receiver itself has no third-party Python package dependency. Do not reuse a
   private key from an example, repository, screenshot or another installation.
2. Run this command in an interactive terminal, substituting your own local
   certificate/key paths:

   ```powershell
   python deskpad_server.py --cert C:\DeskPadIdentity\server-cert.pem --key C:\DeskPadIdentity\server-key.pem --show-pairing-code
   ```

3. Import the intentionally displayed `DP2:` pairing code in your own DeskPad
   Android client, then discover/select the receiver or enter its LAN IP. The
   Android client must validate the certificate fingerprint from that code before
   sending its authentication token. Discovery is only a locator, not proof of
   server identity.

The pairing code is a credential. Do not post it, include it in screenshots or
recordings, redirect it to a log, or paste it into an untrusted application. The
receiver refuses `--show-pairing-code` when standard output is redirected. Omit
that option on subsequent launches to keep the code hidden.

Python uses its own identity file, separate from the C# companion. On Windows it
defaults to `%LOCALAPPDATA%\DeskPad PC\python-pairing-identity.bin`, protected with
the current Windows user's DPAPI key. `--identity-file` can choose another local
path; it takes a filename, never a token. The token is never accepted through a
command argument or environment variable and is never included in discovery or
connection logs. Non-Windows development runs enforce owner-only token file
permissions.

To revoke Python pairing, stop the receiver, delete only its Python identity file,
and restart it to generate a new random credential. Pair the phone again. Changing
the certificate also requires re-pairing. Do not delete the C# companion's identity
or other unrelated files.

## Network behavior

- UDP 48910 replies only to `DESKPAD_DISCOVER_V2` with
  `DESKPAD_OFFER_V2|name|Windows|48912` on Windows. No secret or certificate pin is
  broadcast. Discovery replies are rate-limited and untrusted.
- TCP 48912 requires TLS 1.2 or newer. Its first newline-delimited UTF-8 JSON frame
  must be `{"t":"auth","v":2,"token":"<canonical base64url token>"}`.
  The token is a random 32-byte value encoded as 43 URL-safe characters without
  padding. A successful reply is `{"t":"auth_ok","v":2,"name":"..."}`.
- The `DP2:` code contains the certificate's SHA-256 digest (32 bytes), followed
  by the random token (32 bytes), encoded together as base64url without padding.
- All pointer, scroll, zoom, click, button, text, key and heartbeat messages use
  that authenticated TLS stream. There is no UDP 48911 listener or legacy input
  command path. V1 clients must upgrade.
- One authenticated controller is allowed. Up to four additional pending
  TLS/authentication handshakes are bounded by a combined five-second deadline.
  An unauthenticated or second client cannot take over the session or release its
  held buttons. Authenticated sessions time out after ten seconds without a full
  frame; clients should send a heartbeat every two seconds.
- Each UTF-8 frame is at most 16,384 payload bytes, excluding its LF terminator.
  Text is limited to 2,048 UTF-16 code units. Numeric movement/scroll/zoom values
  must be integers within -32,767 through 32,767; booleans are not integers.
  Click count is 1–2 and buttons are L/R/M. Keys must be known special keys or one
  printable ASCII character; modifiers are unique CTRL/ALT/SHIFT/WIN entries,
  at most four. Invalid frames close the connection before input dispatch.

The script does not change Windows Firewall settings. If you allow LAN access,
scope the rules to the Python program, Private network profile, LocalSubnet and
only UDP 48910/TCP 48912. Never expose the receiver to the internet. Retire old
broad rules and UDP 48911 permissions when upgrading.

## Verification

Run from the repository root:

```powershell
python -B -m unittest discover -s pc-companion -p 'test*server.py' -v
```

The tests use recording injectors and loopback-only ephemeral ports. They do not
send real keyboard or mouse input. Real TLS integration tests generate a
temporary certificate using an existing OpenSSL executable (PATH or Git for
Windows' usual installation path); they skip explicitly if none is available.
No OpenSSL process is needed by the receiver at runtime. Set
`DESKPAD_TEST_TEMP_DIR` to an existing scratch directory to control where temporary
test material is written; test keys are removed when the test suite finishes.
