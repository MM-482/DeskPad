using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace DeskPadPC
{
    internal static class SecurityRegressionTests
    {
        private static int _passed, _failed;
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
        private static void Assert(bool value, string message) { if (!value) throw new Exception(message); }
        private static void Test(string name, Action action)
        {
            try { action(); _passed++; Console.WriteLine("PASS " + name); }
            catch (Exception e) { _failed++; Console.WriteLine("FAIL " + name + ": " + e.GetType().Name + " " + e.Message); }
        }

        private sealed class SlowStream : MemoryStream
        {
            public SlowStream() : base(Encoding.UTF8.GetBytes("slow-frame\n")) { }
            public override int Read(byte[] buffer, int offset, int count)
            { Thread.Sleep(70); return base.Read(buffer, offset, Math.Min(count, 2)); }
        }

        private sealed class RecordingInput : ILanInput
        {
            private readonly List<string> _events = new List<string>();
            public int Count { get { lock (_events) return _events.Count; } }
            public int Releases { get { lock (_events) return _events.FindAll(x => x == "L:up").Count; } }
            private void Add(string value) { lock (_events) _events.Add(value); }
            public void Move(int x, int y) { Add("move"); }
            public void Scroll(int x, int y) { Add("scroll"); }
            public void Zoom(int value) { Add("zoom"); }
            public void Click(string b, int count) { Add("click"); }
            public void MouseButton(string b, bool down) { Add(b + (down ? ":down" : ":up")); }
            public bool SendText(string text) { Add("text"); return true; }
            public bool SendKey(string key, List<string> modifiers) { Add("key"); return true; }
        }

        private sealed class Fixture : IDisposable
        {
            public readonly LanIdentity Identity = LanIdentity.CreateEphemeral();
            public readonly RecordingInput Input = new RecordingInput();
            public readonly ServerEngine Server;
            public volatile bool Connected;
            public Fixture()
            {
                Server = new ServerEngine(0, 0, Identity, delegate(bool connected, string status) { Connected = connected; }, delegate { }, Input, IPAddress.Loopback);
                Server.Start();
            }
            public string Token
            {
                get
                {
                    string encoded = Identity.PairingCode.Substring(4).Replace('-', '+').Replace('_', '/');
                    byte[] bundle = Convert.FromBase64String(encoded + "=="); var token = new byte[32];
                    Buffer.BlockCopy(bundle, 32, token, 0, 32); return LanIdentity.Base64Url(token);
                }
            }
            public void Dispose() { Server.Stop(); Thread.Sleep(60); Identity.Dispose(); }
        }

        private sealed class Client : IDisposable
        {
            private readonly TcpClient _client;
            public readonly SslStream Stream;
            private readonly BoundedLineReader _reader;
            public Client(Fixture fixture, bool correctPin = true)
            {
                _client = new TcpClient(); _client.NoDelay = true;
                _client.Connect(IPAddress.Loopback, fixture.Server.BoundTcpPort);
                byte[] expected;
                using (var hash = SHA256.Create()) expected = hash.ComputeHash(fixture.Identity.Certificate.RawData);
                if (!correctPin) expected[0] ^= 1;
                Stream = new SslStream(_client.GetStream(), false, delegate(object sender, System.Security.Cryptography.X509Certificates.X509Certificate cert,
                    System.Security.Cryptography.X509Certificates.X509Chain chain, SslPolicyErrors errors)
                {
                    if (cert == null) return false;
                    byte[] actual; using (var hash = SHA256.Create()) actual = hash.ComputeHash(cert.GetRawCertData());
                    int diff = 0; for (int i = 0; i < 32; i++) diff |= actual[i] ^ expected[i]; return diff == 0;
                });
                Stream.ReadTimeout = 7000; Stream.WriteTimeout = 2000;
                try { Stream.AuthenticateAsClient("DeskPad PC", null, SslProtocols.Tls12, false); }
                catch { Dispose(); throw; }
                _reader = new BoundedLineReader(Stream);
            }
            public void Send(string line) { byte[] data = Encoding.UTF8.GetBytes(line + "\n"); Stream.Write(data, 0, data.Length); Stream.Flush(); }
            public string Read() { return _reader.ReadLine(); }
            public void Auth(Fixture fixture) { Send(Json.Serialize(new { t = "auth", v = 2, token = fixture.Token })); Assert(Read().Contains("auth_ok"), "Missing auth acknowledgement"); }
            public bool Closed() { try { return Read() == null; } catch (IOException) { return true; } }
            public void Dispose() { try { if (Stream != null) Stream.Dispose(); } catch { } try { _client.Close(); } catch { } }
        }

        private static void WaitFor(Func<bool> condition, string failure)
        { for (int i = 0; i < 100; i++) { if (condition()) return; Thread.Sleep(20); } Assert(false, failure); }
        private static void Reject(string command)
        {
            using (var f = new Fixture()) using (var c = new Client(f))
            { c.Auth(f); c.Send(command); Assert(c.Closed(), "Invalid command remained open"); Assert(f.Input.Count == 0, "Invalid command injected input"); }
        }

        private static int Main(string[] args)
        {
            Test("pairing bundle and token rotation", delegate
            {
                using (var identity = LanIdentity.CreateEphemeral())
                {
                    Assert(LanIdentity.HasStrongRsaKey(identity.Certificate), "Certificate RSA key strength is below 2048 bits");
                    string code = identity.PairingCode;
                    Assert(code.StartsWith("DP2:") && code.Length == 90, "Pairing bundle format");
                    byte[] bytes = Convert.FromBase64String(code.Substring(4).Replace('-', '+').Replace('_', '/') + "==");
                    var token = new byte[32]; Buffer.BlockCopy(bytes, 32, token, 0, 32);
                    string text = LanIdentity.Base64Url(token);
                    Assert(identity.Authenticate(text), "Correct token rejected");
                    Assert(!identity.Authenticate("") && !identity.Authenticate(new string('A', 43)), "Empty/default token accepted");
                    identity.RotateToken(); Assert(!identity.Authenticate(text), "Old token survived reset");
                }
            });
            Test("DPAPI identity persists, rotates and rejects corruption", delegate
            {
                string directory = Path.Combine(Environment.CurrentDirectory, "work", "deskpad-identity-test-" + Guid.NewGuid().ToString("N"));
                string path = Path.Combine(directory, "identity.bin");
                string original, rotated;
                Directory.CreateDirectory(directory);
                try
                {
                    using (var identity = LanIdentity.LoadOrCreate(path)) original = identity.PairingCode;
                    Assert(!Encoding.UTF8.GetString(File.ReadAllBytes(path)).Contains(original), "Credential stored as plaintext");
                    using (var identity = LanIdentity.LoadOrCreate(path))
                    {
                        Assert(identity.PairingCode == original && LanIdentity.HasStrongRsaKey(identity.Certificate), "Identity changed after reload");
                        identity.RotateToken(); rotated = identity.PairingCode;
                        Assert(rotated != original, "Rotation failed");
                    }
                    using (var identity = LanIdentity.LoadOrCreate(path)) Assert(identity.PairingCode == rotated, "Rotation did not persist");
                    byte[] corrupt = File.ReadAllBytes(path); corrupt[corrupt.Length / 2] ^= 1; File.WriteAllBytes(path, corrupt);
                    bool rejected = false; try { using (var identity = LanIdentity.LoadOrCreate(path)) { } } catch (CryptographicException) { rejected = true; }
                    Assert(rejected, "Corrupt identity was silently accepted or replaced");
                }
                finally { if (File.Exists(path)) File.Delete(path); Directory.Delete(directory, false); }
            });
            Test("strict bounded UTF-8 framing", delegate
            {
                string line = new string('x', 16384);
                var reader = new BoundedLineReader(new MemoryStream(Encoding.UTF8.GetBytes(line + "\nnext\n")));
                Assert(reader.ReadLine() == line && reader.ReadLine() == "next", "Boundary or pipelined line failure");
                foreach (byte[] invalid in new[] { Encoding.UTF8.GetBytes(line + "x\n"), Encoding.UTF8.GetBytes("bad\r\n"), new byte[] { 0xFF, 10 }, Encoding.UTF8.GetBytes("partial") })
                {
                    bool rejected = false; try { new BoundedLineReader(new MemoryStream(invalid)).ReadLine(); } catch (Exception) { rejected = true; }
                    Assert(rejected, "Invalid frame accepted");
                }
            });
            Test("cumulative frame deadline rejects drip-fed data", delegate
            {
                bool rejected = false;
                try { new BoundedLineReader(new SlowStream()).ReadLine(100); } catch (IOException) { rejected = true; }
                Assert(rejected, "Slow frame extended its deadline with partial reads");
            });
            Test("pending handshakes are capped without input", delegate
            {
                using (var f = new Fixture())
                {
                    var peers = new List<TcpClient>();
                    try
                    {
                        for (int i = 0; i < 4; i++) { var peer = new TcpClient(); peer.Connect(IPAddress.Loopback, f.Server.BoundTcpPort); peers.Add(peer); }
                        using (var extra = new TcpClient())
                        {
                            extra.Connect(IPAddress.Loopback, f.Server.BoundTcpPort); extra.ReceiveTimeout = 2000;
                            Assert(extra.GetStream().ReadByte() == -1, "Fifth unauthenticated client was not refused");
                        }
                        Assert(!f.Connected && f.Input.Count == 0, "Pending peers acquired input access");
                    }
                    finally { foreach (var peer in peers) peer.Close(); }
                }
            });
            Test("stop drains an incomplete TLS handshake", delegate
            {
                using (var f = new Fixture()) using (var peer = new TcpClient())
                {
                    peer.Connect(IPAddress.Loopback, f.Server.BoundTcpPort); Thread.Sleep(60);
                    var started = DateTime.UtcNow; f.Server.Stop();
                    Assert((DateTime.UtcNow - started).TotalSeconds < 3 && f.Input.Count == 0, "Stop left a handshake worker running");
                }
            });
            Test("TLS authenticated positive input flow", delegate
            {
                using (var f = new Fixture()) using (var c = new Client(f))
                {
                    Assert(!f.Connected, "TLS alone marked connected"); c.Auth(f);
                    foreach (string command in new[] { "{\"t\":\"move\",\"dx\":3,\"dy\":-4}", "{\"t\":\"scroll\",\"sx\":1,\"sy\":2}", "{\"t\":\"zoom\",\"delta\":1}", "{\"t\":\"click\",\"b\":\"L\",\"c\":2}", "{\"t\":\"text\",\"v\":\"Hello \\u263A\"}", "{\"t\":\"key\",\"k\":\"ENTER\",\"m\":[\"CTRL\"]}" }) c.Send(command);
                    c.Send("{\"t\":\"ping\",\"ts\":1794590000000}"); Assert(c.Read().Contains("1794590000000"), "Heartbeat timestamp changed");
                    WaitFor(() => f.Input.Count == 6, "Valid commands missing"); Assert(f.Connected, "Authenticated state missing");
                }
            });
            Test("wrong certificate rejected before credentials", delegate
            {
                using (var f = new Fixture())
                {
                    bool rejected = false; try { using (var c = new Client(f, false)) { } } catch (AuthenticationException) { rejected = true; }
                    Assert(rejected && !f.Connected && f.Input.Count == 0, "Wrong pin accepted");
                }
            });
            Test("plaintext legacy TCP cannot inject", delegate
            {
                using (var f = new Fixture()) using (var c = new TcpClient())
                {
                    c.Connect(IPAddress.Loopback, f.Server.BoundTcpPort);
                    byte[] bytes = Encoding.UTF8.GetBytes("{\"t\":\"text\",\"v\":\"forbidden\"}\n"); c.GetStream().Write(bytes, 0, bytes.Length);
                    Thread.Sleep(100); Assert(f.Input.Count == 0 && !f.Connected, "Plaintext input accepted");
                }
            });
            Test("input before authentication cannot inject", delegate
            { using (var f = new Fixture()) using (var c = new Client(f)) { c.Send("{\"t\":\"move\",\"dx\":1,\"dy\":1}"); Assert(c.Closed() && f.Input.Count == 0 && !f.Connected, "Preauth input accepted"); } });
            Test("wrong token cannot inject", delegate
            { using (var f = new Fixture()) using (var c = new Client(f)) { c.Send("{\"t\":\"auth\",\"v\":2,\"token\":\"" + new string('A', 43) + "\"}"); Assert(c.Closed() && !f.Connected && f.Input.Count == 0, "Wrong token accepted"); } });
            Test("unauthenticated peers cannot disrupt controller", delegate
            {
                using (var f = new Fixture()) using (var c = new Client(f))
                {
                    c.Auth(f); c.Send("{\"t\":\"btn\",\"b\":\"L\",\"d\":true}"); WaitFor(() => f.Input.Count == 1, "Button missing");
                    using (var attacker = new Client(f)) { attacker.Send("{\"t\":\"auth\",\"v\":2,\"token\":\"bad\"}"); Assert(attacker.Closed(), "Bad peer remained open"); }
                    using (var second = new Client(f)) { second.Send(Json.Serialize(new { t = "auth", v = 2, token = f.Token })); Assert(second.Closed(), "Second controller took over"); }
                    Assert(f.Connected && f.Input.Releases == 0, "Peer released active input or changed state");
                    c.Dispose(); WaitFor(() => f.Input.Releases == 1 && !f.Connected, "Disconnect failed to release button");
                }
            });
            Test("reset disconnects and revokes old token", delegate
            {
                using (var f = new Fixture())
                {
                    string old = f.Token;
                    using (var c = new Client(f)) { c.Auth(f); f.Server.ResetPairing(); Assert(c.Closed(), "Reset kept session open"); }
                    using (var c = new Client(f)) { c.Send(Json.Serialize(new { t = "auth", v = 2, token = old })); Assert(c.Closed(), "Old token survived reset"); }
                    using (var c = new Client(f)) { c.Auth(f); }
                }
            });
            Test("discovery accepts V2 only and never input", delegate
            {
                using (var f = new Fixture()) using (var udp = new UdpClient())
                {
                    udp.Client.ReceiveTimeout = 200;
                    var endpoint = new IPEndPoint(IPAddress.Loopback, f.Server.BoundDiscoveryPort);
                    foreach (string invalid in new[] { "DESKPAD_DISCOVER_V1", "M|100|100" })
                    { byte[] bytes = Encoding.ASCII.GetBytes(invalid); udp.Send(bytes, bytes.Length, endpoint); bool timeout = false; try { IPEndPoint remote = null; udp.Receive(ref remote); } catch (SocketException) { timeout = true; } Assert(timeout, "Legacy or motion packet got response"); }
                    byte[] query = Encoding.ASCII.GetBytes("DESKPAD_DISCOVER_V2"); udp.Send(query, query.Length, endpoint); IPEndPoint sender = null;
                    string offer = Encoding.UTF8.GetString(udp.Receive(ref sender)); Assert(offer.StartsWith("DESKPAD_OFFER_V2|") && offer.Split('|').Length == 4 && !offer.Contains(f.Token), "Bad discovery offer"); Assert(f.Input.Count == 0, "Discovery injected input");
                }
            });
            Test("bounded unauthenticated handshake deadline", delegate
            {
                using (var f = new Fixture()) using (var c = new TcpClient())
                {
                    c.Connect(IPAddress.Loopback, f.Server.BoundTcpPort); c.ReceiveTimeout = 6500;
                    var started = DateTime.UtcNow; int value = c.GetStream().ReadByte();
                    Assert(value == -1 && (DateTime.UtcNow - started).TotalSeconds < 6.5 && f.Input.Count == 0, "Handshake deadline did not close peer");
                }
            });
            Test("Boolean numeric field rejected", () => Reject("{\"t\":\"move\",\"dx\":true,\"dy\":1}"));
            Test("out-of-range motion rejected", () => Reject("{\"t\":\"move\",\"dx\":2147483647,\"dy\":1}"));
            Test("unbounded click count rejected", () => Reject("{\"t\":\"click\",\"b\":\"L\",\"c\":999999}"));
            Test("invalid modifier rejected", () => Reject("{\"t\":\"key\",\"k\":\"ENTER\",\"m\":[\"ESC\"]}"));
            Test("duplicate modifier rejected", () => Reject("{\"t\":\"key\",\"k\":\"ENTER\",\"m\":[\"CTRL\",\"CTRL\"]}"));
            Test("oversized text rejected", () => Reject(Json.Serialize(new { t = "text", v = new string('a', 2049) })));
            Test("oversized frame rejected", () => Reject(new string('a', 16385)));
            Console.WriteLine("RESULT " + _passed + " passed, " + _failed + " failed; recording injector only.");
            return _failed == 0 ? 0 : 1;
        }
    }
}

