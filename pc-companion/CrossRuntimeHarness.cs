#if CROSS_RUNTIME_HARNESS
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Web.Script.Serialization;

namespace DeskPadPC
{
    // Test-only entry point: never constructs the UI or invokes Win32 input.
    internal static class CrossRuntimeHarness
    {
        private sealed class RecordingInput : ILanInput
        {
            private readonly List<string> _events = new List<string>();
            private void Add(string text) { lock (_events) _events.Add(text); }
            public string[] Snapshot() { lock (_events) return _events.ToArray(); }
            public void Move(int x, int y) { Add("move:" + x + ":" + y); }
            public void Scroll(int x, int y) { Add("scroll:" + x + ":" + y); }
            public void Zoom(int value) { Add("zoom:" + value); }
            public void Click(string b, int count) { Add("click:" + b + ":" + count); }
            public void MouseButton(string b, bool down) { Add("btn:" + b + ":" + (down ? "down" : "up")); }
            public bool SendText(string text) { Add("text:" + text); return true; }
            public bool SendKey(string key, List<string> modifiers) { Add("key:" + key); return true; }
        }

        private static int Main(string[] args)
        {
            if (args.Length != 1) return 2;
            var json = new JavaScriptSerializer();
            var input = new RecordingInput();
            using (var identity = LanIdentity.CreateEphemeral())
            {
                var server = new ServerEngine(0, 0, identity, delegate { }, delegate { }, input, IPAddress.Loopback);
                try
                {
                    server.Start();
                    // The ephemeral test credential is written only to the private scratch file, never stdout.
                    File.WriteAllText(args[0], json.Serialize(new { port = server.BoundTcpPort,
                        discoveryPort = server.BoundDiscoveryPort, pairingCode = identity.PairingCode,
                        certificate = Convert.ToBase64String(identity.Certificate.RawData),
                        publicKeyBits = identity.Certificate.PublicKey.Key.KeySize }));
                    Console.WriteLine("READY");
                    string line;
                    while ((line = Console.ReadLine()) != null)
                    {
                        if (line == "stats") Console.WriteLine(json.Serialize(input.Snapshot()));
                        else if (line == "stop") break;
                    }
                    return 0;
                }
                finally { server.Stop(); }
            }
        }
    }
}
#endif
