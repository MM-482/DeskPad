using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;

namespace DeskPadPC
{
    internal static class PairingQrTests
    {
        private static int _checks;
        private static void Check(bool condition, string message)
        { _checks++; if (!condition) throw new Exception(message); }

        private static bool Rejects(string code)
        { try { using (var bitmap = PairingQrSupport.Create(code, 360)) { } return false; } catch (ArgumentException) { return true; } }

        private static bool RejectsEnvelope(string name, string[] hosts, string code)
        { try { PairingQrSupport.BuildEnvelope(name, hosts, code); return false; } catch (ArgumentException) { return true; } }

        public static int Main(string[] args)
        {
            // DUMMY fixture only. Never read a user's stored PC identity or token.
            byte[] dummy = new byte[64];
            for (int i = 0; i < dummy.Length; i++) dummy[i] = (byte)i;
            string payload = "DP2:" + Convert.ToBase64String(dummy).TrimEnd('=').Replace('+','-').Replace('/','_');
            PairingQrSupport.Initialize();
            var assembly = AppDomain.CurrentDomain.GetAssemblies().Single(a => a.GetName().Name == "QRCoder");
            Check(string.IsNullOrEmpty(assembly.Location), "QR library must load from embedded bytes, without an external DLL.");
            Check(assembly.GetName().Version.ToString() == "1.4.3.0", "Unexpected QR library version.");
            using (var bitmap = PairingQrSupport.Create(payload, 360))
            {
                Check(bitmap.Width == bitmap.Height && bitmap.Width >= 160 && bitmap.Width <= 360, "QR dimensions are unsuitable.");
                int black = 0, firstBlack = bitmap.Width;
                bool binary = true, whiteBorder = true;
                for (int y = 0; y < bitmap.Height; y++)
                    for (int x = 0; x < bitmap.Width; x++)
                    {
                        int color = bitmap.GetPixel(x, y).ToArgb();
                        binary &= color == Color.Black.ToArgb() || color == Color.White.ToArgb();
                        if (color == Color.Black.ToArgb()) { black++; firstBlack = Math.Min(firstBlack, Math.Min(x, y)); }
                        if (x == 0 || y == 0 || x == bitmap.Width-1 || y == bitmap.Height-1) whiteBorder &= color == Color.White.ToArgb();
                    }
                Check(binary, "QR must use solid black and white without antialiasing.");
                Check(black > 0 && firstBlack >= 16, "QR quiet zone is missing.");
                Check(whiteBorder, "QR border must be white.");
                using (var same = PairingQrSupport.Create(payload, 360))
                    Check(same.GetPixel(firstBlack, firstBlack) == bitmap.GetPixel(firstBlack, firstBlack) && same.Size == bitmap.Size, "QR rendering must be deterministic.");
                dummy[63] ^= 0xFF;
                string changed = "DP2:" + Convert.ToBase64String(dummy).TrimEnd('=').Replace('+','-').Replace('/','_');
                using (var fresh = PairingQrSupport.Create(changed, 360))
                {
                    bool differs = fresh.Size != bitmap.Size;
                    if (!differs)
                        for (int y=0; y<bitmap.Height && !differs; y++)
                            for (int x=0; x<bitmap.Width; x++)
                                if (fresh.GetPixel(x,y) != bitmap.GetPixel(x,y)) { differs=true; break; }
                    Check(differs, "Changed pairing credentials must generate a different QR.");
                }
            }
            Check(Rejects(null), "Null pairing credential accepted.");
            Check(Rejects("DP1:" + payload.Substring(4)), "Legacy pairing credential accepted.");
            Check(Rejects(payload + "="), "Noncanonical padded pairing credential accepted.");
            Check(Rejects(payload.Substring(0,89) + "+"), "Invalid base64url pairing credential accepted.");
            Check(Rejects("https://example.com"), "Non-DeskPad QR payload accepted.");
            string[] dummyHosts = { "192.168.56.10", "100.64.0.10" };
            string envelope = PairingQrSupport.BuildEnvelope("DeskPad Test PC", dummyHosts, payload);
            string expected = "{\"t\":\"deskpad_pair\",\"v\":2,\"name\":\"DeskPad Test PC\",\"hosts\":[\"192.168.56.10\",\"100.64.0.10\"],\"port\":48912,\"code\":\"" + payload + "\"}";
            Check(envelope == expected, "QR envelope fields or values do not match the scanner contract.");
            Check(envelope.Length <= 2048 && envelope.All(c => c <= 127), "QR envelope must be bounded ASCII JSON.");
            string escaped = PairingQrSupport.BuildEnvelope("PC \"\\\u00e9", dummyHosts, payload);
            Check(escaped.Contains("PC \\\"\\\\\\u00e9"), "PC names must be JSON escaped without expanding the contract.");
            foreach (string accepted in new[] { "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.255" })
                Check(PairingQrSupport.AddressClass(accepted) >= 0, "A permitted local IPv4 address was rejected.");
            foreach (string rejected in new[] { "8.8.8.8", "127.0.0.1", "169.254.1.1", "224.0.0.1", "0.0.0.0", "255.255.255.255", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "192.169.0.1", "192.168.001.1", "0xc0a80001", "3232235521", "192.168.1.1:48912", "example.com", "::1", " 192.168.1.1", "192.168.1.1\n" })
                Check(PairingQrSupport.AddressClass(rejected) < 0, "A noncanonical or disallowed address was accepted.");
            Check(RejectsEnvelope("", dummyHosts, payload), "An empty PC name was accepted.");
            Check(RejectsEnvelope(new string('x', 65), dummyHosts, payload), "An overlong PC name was accepted.");
            Check(RejectsEnvelope("PC\nname", dummyHosts, payload), "A control character in the PC name was accepted.");
            Check(RejectsEnvelope("PC", null, payload), "Missing addresses were accepted.");
            Check(RejectsEnvelope("PC", new string[0], payload), "An empty address list was accepted.");
            Check(RejectsEnvelope("PC", Enumerable.Range(1,9).Select(i => "10.0.0." + i).ToArray(), payload), "More than 8 addresses were accepted.");
            Check(RejectsEnvelope("PC", new[] { "10.0.0.1", "10.0.0.1" }, payload), "Duplicate addresses were accepted.");
            Check(RejectsEnvelope("PC", new[] { "8.8.8.8" }, payload), "A public address was accepted by the envelope builder.");
            Check(RejectsEnvelope("PC", dummyHosts, "DP1:" + payload.Substring(4)), "An invalid code was accepted by the envelope builder.");
            var ordered = PairingQrSupport.SelectHosts(new[] {
                new PairingQrSupport.HostCandidate("100.64.0.1", true),
                new PairingQrSupport.HostCandidate("10.0.0.1", false),
                new PairingQrSupport.HostCandidate("192.168.1.1", true),
                new PairingQrSupport.HostCandidate("192.168.1.1", false),
                new PairingQrSupport.HostCandidate("8.8.8.8", true) });
            Check(ordered.SequenceEqual(new[] { "192.168.1.1", "10.0.0.1", "100.64.0.1" }), "Address selection must prefer gateway RFC1918, deduplicate, and leave CGNAT last.");
            var many = PairingQrSupport.SelectHosts(Enumerable.Range(1,12).Select(i => new PairingQrSupport.HostCandidate("10.0.0." + i, true)));
            Check(many.Length == 8 && many.Distinct().Count() == 8, "Address selection must be bounded to 8 unique hosts.");
            bool noLan = false;
            try { PairingQrSupport.SelectHosts(new[] { new PairingQrSupport.HostCandidate("127.0.0.1", true) }); }
            catch (NoPairingLanAddressException) { noLan = true; }
            Check(noLan, "No eligible LAN address must fail with an actionable manual-pairing fallback.");
            using (var bitmap = PairingQrSupport.CreateEnvelope("DeskPad Test PC", dummyHosts, payload, 360))
            {
                Check(bitmap.Width == bitmap.Height && bitmap.Width <= 360 && bitmap.Width >= 160, "Envelope QR dimensions are unsuitable.");
                Check(bitmap.GetPixel(0,0).ToArgb() == Color.White.ToArgb(), "Envelope QR must retain its quiet zone.");
                if (args.Length == 1)
                {
                    string target = Path.GetFullPath(args[0]);
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    bitmap.Save(target, ImageFormat.Png);
                    Console.WriteLine("Saved synthetic local-address envelope with byte-0-through-63 credential only.");
                }
            }
            Console.WriteLine("PASS: " + _checks + " production QR renderer checks; no real credential, UI or network used.");
            return 0;
        }
    }
}
