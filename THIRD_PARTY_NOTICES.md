# Third-party notices for pairing

DeskPad's QR and PIN pairing features use the components below. These dependency licenses
do not assign a license to DeskPad's own source; the project currently has no
project LICENSE file.

| Component | Purpose | License and upstream source |
|---|---|---|
| JourneyApps ZXing Android Embedded 4.3.0 (`com.journeyapps:zxing-android-embedded`) | Android camera scanning | Apache License 2.0. [Tagged source](https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0), [official COPYING](https://raw.githubusercontent.com/journeyapps/zxing-android-embedded/v4.3.0/COPYING). |
| ZXing core 3.4.1 (`com.google.zxing:core`) | Barcode/QR decoding used by the Android scanner | Apache License 2.0; the complete upstream license file also contains an additional upstream jai-imageio notice. [Tagged source](https://github.com/zxing/zxing/tree/zxing-3.4.1), [official LICENSE](https://raw.githubusercontent.com/zxing/zxing/zxing-3.4.1/LICENSE), [official NOTICE](https://raw.githubusercontent.com/zxing/zxing/zxing-3.4.1/NOTICE). |
| QRCoder 1.4.3 (`lib/net40/QRCoder.dll`) | Windows pairing QR generation | MIT. [Vendored license](pc-companion/vendor/QRCoder-1.4.3/LICENSE.txt), [version/provenance/hash notes](pc-companion/vendor/README.md). The DLL and license are embedded in the Windows receiver. |
| Bouncy Castle Java 1.87 (`org.bouncycastle:bcprov-jdk15to18`) | Android SRP-6a for temporary PIN pairing, through the lightweight API | MIT. [Tagged source](https://github.com/bcgit/bc-java/tree/r1rv87), [official license](https://raw.githubusercontent.com/bcgit/bc-java/r1rv87/LICENSE.html), [local verbatim copy](docs/licenses/BouncyCastle-Java-1.87-LICENSE.html). |
| Bouncy Castle C# 2.7.0 (`BouncyCastle.Cryptography`, `lib/net461`) | Windows SRP-6a for temporary PIN pairing | MIT. [Tagged source](https://github.com/bcgit/bc-csharp/tree/release-2.7.0), [official license](https://raw.githubusercontent.com/bcgit/bc-csharp/release-2.7.0/LICENSE.md). The Windows vendor directory retains the distribution license and provenance. |

JourneyApps' tagged README attributes its work to “ZXing authors, Journey Mobile”.
Its exact upstream copyright line is retained in the Android notice asset. ZXing
attributes its decoder to the ZXing authors. The upstream files are used as
dependencies; DeskPad does not replace their license texts with a project license.

Verbatim copies of the tagged JourneyApps COPYING, ZXing LICENSE and ZXing NOTICE
are included under [docs/licenses](docs/licenses/) and in the APK under
`assets/licenses/`, together with `THIRD_PARTY_NOTICES.txt`. The full ZXing files
retain aggregate upstream notices; their inclusion does not mean DeskPad uses
every other component mentioned in the larger ZXing distribution.

The tagged Bouncy Castle Java license is also copied verbatim under `docs/licenses/`
and `assets/licenses/`. PIN pairing uses the published lightweight SRP APIs; it does
not install a global Android cryptography provider. The Windows Bouncy Castle license
is retained with its vendored assembly and in the receiver's embedded notices.
