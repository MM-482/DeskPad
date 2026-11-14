# DeskPad LAN security and migration

## What the old issue meant

The 1.0 receiver listened on all interfaces and dispatched raw UDP motion and plaintext TCP input without authenticating the sender. A device able to reach its ports could potentially send pointer/keyboard commands with the privileges available to the receiver. This is an unauthorized-input vulnerability. It is not evidence that anyone actually accessed a particular PC, and it does not itself bypass Windows administrator boundaries.

The old Allow Firewall action created port-only inbound allows for any network profile, program and remote address. A README warning could not enforce isolation on a hotel, guest, office or other shared network.

## Version 1.0.1 protections

- All control input travels over a TLS 1.2+ stream on TCP 48912. There is no plaintext or unauthenticated compatibility mode and no UDP input socket.
- A unique RSA-2048/SHA-256 certificate and random 256-bit secret are generated per PC user identity. Certificate generation and loading enforce a minimum RSA key size of 2048 bits. Private identity storage uses Windows DPAPI for the current user.
- Pairing starts from **Scan QR** on the phone's main screen. The private QR displayed by the PC supplies its connection addresses and the existing canonical `DP2:` credential, so the user does not have to select a PC first. The credential contains the 32-byte SHA-256 fingerprint of the leaf certificate followed by the 32-byte secret, using unpadded URL-safe Base64. Address transfer changes the setup flow, not the cryptographic protocol. Treat the QR and full credential text as control credentials.
- The direct-scan QR is a bounded V2 DeskPad JSON envelope: protocol tag `deskpad_pair`, version 2, a PC name, one to eight distinct canonical private/CGNAT IPv4 addresses, port 48912 and the canonical DP2 credential. Maximum payload length is 2048 characters. Invalid payloads, public addresses and unrelated QR content must not start a connection. QR address data remains a locator; TLS must still match the imported certificate pin before the secret is sent.
- The main-screen **Enter PIN** alternative enrolls one selected PC using a temporary six-digit PIN. It uses certificate-bound SRP-6a through Bouncy Castle's lightweight APIs, not plaintext PIN transmission or a PIN-derived replacement for the normal random secret. Enrollment never accepts input or becomes the controller connection. Android opens a fresh normal pinned TLS/token-authenticated connection before saving the pairing.
- Android checks that exact certificate fingerprint and certificate validity during the TLS handshake before sending the secret. UDP discovery provides location hints only; it cannot supply a trusted fingerprint.
- The server accepts no input until it authenticates the secret and returns a V2 acknowledgement. One authenticated controller is allowed. Wrong or incomplete pairing cannot replace that controller or release its held inputs.
- Input frames, JSON depth, text length and numeric fields are bounded. Handshake/authentication and complete-frame deadlines limit stalled connections. Pending handshakes are capped. Session teardown releases that session's held mouse buttons.
- Android uses a Keystore AES-GCM wrapping key and no-backup storage for saved pairings. A lost key or corrupt record requires re-pairing. Input queues belong to one authenticated session; old events are not replayed into new sessions.
- Reset access on the PC rotates the secret, disconnects clients and invalidates old pairings and their QR codes. If a QR or pairing text leaks, reset access immediately and scan the new QR only on your own phones.

## Temporary six-digit PIN enrollment

Opening the Windows pairing window explicitly creates a cryptographically random six-digit PIN, including possible leading zeros. It closes after two minutes, five globally reserved attempts, one successful enrollment, dismissal, stop or reset. **New PIN** replaces the previous window. Only one exchange can be in flight. A challenge consumes an attempt even if the peer disconnects; changing IP addresses does not provide a fresh budget. A LAN peer can exhaust this budget and deny PIN pairing. QR or a locally requested new PIN remains the recovery path.

The Android **Enter PIN** dialog chooses one discovered PC or a manually entered private/CGNAT IPv4 address. Each explicit **Pair** tap permits one connection and one SRP exchange, with no automatic retry against other hosts. Discovery still supplies no trust. Bootstrap uses a dedicated TLS socket with its own restricted certificate handling; QR, saved-pairing and normal input paths retain exact certificate pinning.

Both implementations use Bouncy Castle's stock SRP-6a APIs, the fixed RFC 5054 3072-bit group and SHA-256. The SRP identity is UTF-8 `DeskPad-PIN-v1|<lowercase SHA-256 of actual TLS leaf DER>|<fresh 32-byte attempt ID in canonical Base64url>`. The PC derives the hash from its own certificate; Android derives it from the certificate used by that socket. Neither accepts a peer-supplied identity or certificate hash. This binds the PIN exchange to its TLS endpoint and prevents a relay between connections presenting different certificates. This application profile does not negotiate legacy TLS-SRP cipher suites.

Each attempt has fresh salt and SRP ephemeral values. Public values are fixed-width 384-byte unsigned integers with `0 < A,B < N`; evidence and the derived key are fixed-width 32 bytes. After mutual M1/M2 verification, Android confirms with HMAC-SHA-256 under the derived key over `DeskPad-PIN-v1-confirm|<attempt ID>|<actual leaf hash>`. Only a valid confirmation from the current, unexpired attempt permits export of the existing DP2 credential. Android checks that credential's fingerprint against the observed leaf, then completes normal pinned TLS/token authentication before saving anything or enabling input. Wrong, canceled, stale or malformed attempts cannot replace saved trust or take over a controller.

PIN frames use strict flat ASCII JSON, exact fields, canonical unpadded Base64url, no duplicate keys or string escapes, and bounded lengths. TLS/first-frame setup has a five-second deadline; the subsequent exchange has one total fifteen-second socket deadline. The regular connection cap still applies. The Python standard-library receiver retains advanced full-credential pairing and does not implement this PIN bootstrap.

The dependencies are [Bouncy Castle C# 2.7.0](https://github.com/bcgit/bc-csharp/tree/release-2.7.0) and [Bouncy Castle Java 1.87](https://github.com/bcgit/bc-java/tree/r1rv87). Group parameters and reference test values originate in [RFC 5054](https://www.rfc-editor.org/rfc/rfc5054); certificate binding follows the endpoint-binding principle in [RFC 5929](https://www.rfc-editor.org/rfc/rfc5929). The profile and its tests do not constitute an independent cryptographic audit.

## Windows firewall enforcement

The installer migrates the old rules before starting the receiver. The portable app performs a read-only effective-policy check before opening any LAN listeners and leaves Wi-Fi off until setup is verified.

The allowed rules are program-bound, inbound, Private profile, LocalSubnet remote addresses, Edge Traversal blocked:

| Purpose | Protocol | Local port |
|---|---|---|
| Discovery, with no input or credential data | UDP | 48910 |
| Authenticated encrypted input | TCP | 48912 |

Program-bound Public/Domain block rules also cover those ports and the retired UDP input port 48911. Migration removes the specifically identified old `DeskPad PC` broad rules and matching program-bound automatic rules. It does not turn Windows Firewall off, mark a network Private, create router forwarding or change unrelated apps' rules. Effective policy is checked afterward; managed policy conflicts, cancellation and failure are reported as incomplete setup rather than success.

Do not mark a shared hotel or office network Private to make the app connect. Office Domain profiles are intentionally excluded. Use Bluetooth where compatible or a trusted network you control. Local-subnet scoping is another restriction, not an authentication mechanism.

## Updating from 1.0

1. Quit the old PC receiver. Install the new PC package and approve the administrator prompt for scoped firewall migration. Do not keep an old portable receiver running.
2. Install the new Android APK over the existing app using the same signing identity. Old clients and receivers are deliberately incompatible with the new secure LAN protocol.
3. On the PC, open **Pair phone / Reset access** to show its QR. On the phone's main screen, tap **Scan QR**, grant camera access and scan it. The app reads the PC connection details and starts the pinned, authenticated connection without opening Link or selecting a PC first. You only need the companion for Wi-Fi, not Bluetooth HID.

If scanning is unavailable, tap **Enter PIN** on the main screen, choose your PC and enter its temporary PIN while the PC pairing window remains open. Tap **Pair** once to verify and connect. You can also open **Link**, select or enter the PC, then use the advanced **Enter code manually** path with the complete `DP2:` credential from the PC's advanced text/copy option. A bare DP2 code requires a selected PC; it cannot supply an address to the main-screen scan. Invalid or canceled pairing must not authorize input. Camera permission is needed only for scanning and is separate from Bluetooth permissions.

Saved pairings are specific to the PC address and port. After an address change, scan the same PC's QR for the new address. A certificate identity change must be explicitly verified using a new QR from the PC, never accepted from discovery.

If migration is canceled or blocked by company policy, Wi-Fi remains disabled. Do not solve this by restoring the old Any-profile rules. A company administrator may need to approve a policy-compatible setup.

## Local recovery and retained credentials

PC identity storage is `%LOCALAPPDATA%\DeskPad PC\lan-identity-v2.bin`. It is retained through normal updates and uninstall so a routine update does not silently replace the PC identity. The installed programs are under a separate `%LOCALAPPDATA%\Programs\DeskPad PC` directory.

If identity storage is corrupt or expired, the receiver fails closed. For an explicit full identity reset: quit every DeskPad PC process, remove that one identity file locally, then reopen the updated app and re-pair every phone using its new code. This invalidates the previous certificate and secret. Do not delete unrelated files or publish the old identity file. To revoke phones without changing the certificate, use Reset access in the app.

No secret is included in discovery, status text, normal logs, source packages or build artifacts. Do not include a pairing QR, temporary PIN, complete pairing text, or a camera preview of that QR in a support screenshot, Reddit post, demo clip or bug report. Dismiss the PC pairing window when finished. Reset access if a control credential was shared.

## Limits of these checks

Regression tests use loopback TLS and recording input handlers. They exercise accepted and rejected protocol behavior without controlling the actual desktop. Synthetic firewall tests inspect migration plans and effective-policy logic without changing the machine's firewall. The read-only check can be exercised as a normal user.

These automated checks do not establish an in-place Windows installer/UAC migration, camera scanning or PIN entry on a physical phone, Android Keystore behavior on every device, real phone-to-PC gestures, Bluetooth compatibility, Fold continuity or a performance benchmark. Actual device and installation checks must be reported separately. Other software, administrator policy, a compromised endpoint or a stolen pairing credential can still affect security.
