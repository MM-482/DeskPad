# Vendored Windows components

## Bouncy Castle 2.7.0

DeskPad embeds the official `BouncyCastle.Cryptography` 2.7.0 `.NET Framework 4.6.1` assembly for short-lived six-digit PIN enrollment using its SRP-6a implementation. The existing Windows receiver targets .NET Framework 4.8; the pinned assembly has no NuGet dependencies. The complete receiver and its embedded dependency remain inside the single setup executable. No cryptography download or external DLL is needed at runtime.

- Official release: https://www.bouncycastle.org/download/bouncy-castle-c/
- Official NuGet: https://www.nuget.org/packages/BouncyCastle.Cryptography/2.7.0
- Package: https://api.nuget.org/v3-flatcontainer/bouncycastle.cryptography/2.7.0/bouncycastle.cryptography.2.7.0.nupkg
- Official matching DLL: https://downloads.bouncycastle.org/csharp/2.7.0/net461/BouncyCastle.Cryptography.dll
- Published upstream checksum: https://downloads.bouncycastle.org/csharp/docs/CHECKSUMS.csv
- Package SHA-256: `f091ffccab4d03993e660bace277659a79dee0972f54d7f1f4bd46d680966241`
- DLL SHA-256, independently matched to the upstream published .NET 4.61 checksum: `4f96977e9c67334742c683410b3a361258219f0d3084a5e0bc10fba96cf23a0d`
- Upstream tagged source: https://github.com/bcgit/bc-csharp/tree/release-2.7.0
- NuGet repository provenance commit: `4007498b13582d90ee1eda5d9920c324428b98b3`
- MIT license: https://raw.githubusercontent.com/bcgit/bc-csharp/release-2.7.0/LICENSE.md
- The tagged license is preserved verbatim at `BouncyCastle-2.7.0/LICENSE.md` (SHA-256 `0e01f1549c9022f406392ac2947d32223b7c2e977d21ea2f8c182fdeb4dae5fd`) and embedded as `DeskPad.BouncyCastle.LICENSE.md`.

The PIN exchange uses the fixed RFC 5054 3072-bit group, SHA-256, fresh server state and mutual SRP evidence. The SRP identity and final confirmation bind the exchange to the actual local TLS certificate hash and a fresh session ID. The PIN is an expiring enrollment secret and is never the permanent remote-control credential. This records provenance and integrity; it does not claim an independent audit, FIPS validation, or NuGet-signature validation of this application.

## QRCoder 1.4.3

DeskPad embeds **QRCoder 1.4.3**, using only its `.NET Framework 4.0` assembly to draw the local PC pairing QR. The DLL is loaded from the receiver's embedded resources after a SHA-256 check. The installer embeds that complete receiver; no separate DLL or QR web service is needed at runtime.

- Official package: [QRCoder 1.4.3 on NuGet](https://www.nuget.org/packages/QRCoder/1.4.3)
- Download: [NuGet flat-container package](https://api.nuget.org/v3-flatcontainer/qrcoder/1.4.3/qrcoder.1.4.3.nupkg)
- Upstream source: [QRCoder v1.4.3](https://github.com/codebude/QRCoder/tree/v1.4.3)
- Upstream release commit: `cecf206` (as identified by the upstream release page).
- Upstream license: [MIT license at v1.4.3](https://raw.githubusercontent.com/codebude/QRCoder/v1.4.3/LICENSE.txt), preserved verbatim in `QRCoder-1.4.3/LICENSE.txt` and embedded in the receiver as `DeskPad.QRCoder.LICENSE.txt`.
- Package SHA-256: `8ade16246255ae99986fc60b9532de52b4db196f00166dab5ec916ecdbc2b4c2`
- Vendored `lib/net40/QRCoder.dll` SHA-256: `561acfe4b1a14c837b189fb9fc5c6d3e82440184bbde61912de723d62d6368b3`

The package declares no NuGet dependencies for .NET Framework 4.0. Its framework assembly references use Windows/.NET Framework assemblies, including System.Drawing; no System.Drawing.Common package or modern .NET runtime is added. Version 1.4.3 is deliberately pinned for the existing .NET Framework compiler/runtime. No claim is made that this is the latest QRCoder release or that the NuGet package signature was independently validated.

The main-scan production renderer encodes a bounded `deskpad_pair` V2 JSON envelope containing the PC's private LAN addresses, port 48912 and the unchanged canonical `DP2:` credential. It uses Q error correction, solid black modules, a white background and the standard quiet zone. The advanced text/copy path retains the bare DP2 credential. The renderer never saves or logs a real pairing credential or QR image. Tests may save an explicitly synthetic credential containing byte values 0 through 63; that fixture is not a real PC identity.

This third-party component's MIT license does not change the licensing status of DeskPad's own source.
