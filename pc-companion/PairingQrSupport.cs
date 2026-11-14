using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using QRCoder;

namespace DeskPadPC
{
    internal sealed class NoPairingLanAddressException : InvalidOperationException
    {
        public NoPairingLanAddressException() : base("No eligible local IPv4 address is available. Connect this PC to your trusted home Wi-Fi or Ethernet network, then reopen this window. You can also use Copy code (advanced) with manual pairing.") { }
    }

    // The library stays embedded: no download, external process or payload file.
    internal static class PairingQrSupport
    {
        private const string LibraryHash = "561acfe4b1a14c837b189fb9fc5c6d3e82440184bbde61912de723d62d6368b3";
        private static readonly object Gate = new object();
        private static Assembly _library;
        private static bool _resolverRegistered;

        public static void Initialize()
        {
            lock (Gate)
            {
                if (!_resolverRegistered)
                {
                    AppDomain.CurrentDomain.AssemblyResolve += ResolveLibrary;
                    _resolverRegistered = true;
                }
                LoadEmbeddedLibrary();
            }
        }

        private static Assembly ResolveLibrary(object sender, ResolveEventArgs args)
        {
            var requested = new AssemblyName(args.Name);
            if (!string.Equals(requested.Name, "QRCoder", StringComparison.Ordinal)) return null;
            lock (Gate) { return LoadEmbeddedLibrary(); }
        }

        private static Assembly LoadEmbeddedLibrary()
        {
            if (_library != null) return _library;
            using (var input = typeof(PairingQrSupport).Assembly.GetManifestResourceStream("DeskPad.QRCoder.dll"))
            {
                if (input == null || input.Length > 1048576) throw new InvalidOperationException("The embedded QR component is missing or invalid.");
                using (var buffer = new MemoryStream())
                {
                    input.CopyTo(buffer);
                    byte[] bytes = buffer.ToArray();
                    using (var hash = SHA256.Create())
                        if (BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant() != LibraryHash)
                            throw new InvalidOperationException("The embedded QR component failed verification.");
                    _library = Assembly.Load(bytes);
                    return _library;
                }
            }
        }

        public static Bitmap Create(string pairingCode, int maximumPixels)
        {
            ValidatePayload(pairingCode);
            if (maximumPixels < 160 || maximumPixels > 1600) throw new ArgumentOutOfRangeException("maximumPixels");
            Initialize();
            return RenderCore(pairingCode, maximumPixels);
        }

        public static Bitmap CreateForThisPc(string pairingCode, int maximumPixels)
        {
            return CreateEnvelope(Environment.MachineName, GetLocalHosts(), pairingCode, maximumPixels);
        }

        public static Bitmap CreateEnvelope(string name, string[] hosts, string pairingCode, int maximumPixels)
        {
            string payload = BuildEnvelope(name, hosts, pairingCode);
            if (maximumPixels < 160 || maximumPixels > 1600) throw new ArgumentOutOfRangeException("maximumPixels");
            Initialize();
            return RenderCore(payload, maximumPixels);
        }

        internal static string BuildEnvelope(string name, string[] hosts, string pairingCode)
        {
            ValidatePayload(pairingCode);
            if (name == null || name.Length < 1 || name.Length > 64)
                throw new ArgumentException("The PC name must contain 1 to 64 characters.");
            foreach (char character in name)
                if (char.IsControl(character)) throw new ArgumentException("The PC name cannot contain control characters.");
            if (hosts == null || hosts.Length < 1 || hosts.Length > 8)
                throw new ArgumentException("The pairing QR requires 1 to 8 local IPv4 addresses.");
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var payload = new StringBuilder("{\"t\":\"deskpad_pair\",\"v\":2,\"name\":");
            AppendJsonString(payload, name);
            payload.Append(",\"hosts\":[");
            for (int i = 0; i < hosts.Length; i++)
            {
                if (AddressClass(hosts[i]) < 0 || !seen.Add(hosts[i]))
                    throw new ArgumentException("Pairing addresses must be unique canonical private IPv4 addresses.");
                if (i != 0) payload.Append(',');
                AppendJsonString(payload, hosts[i]);
            }
            payload.Append("],\"port\":48912,\"code\":");
            AppendJsonString(payload, pairingCode);
            payload.Append('}');
            // ASCII JSON makes the byte bound explicit, including non-ASCII PC names.
            if (payload.Length > 2048) throw new ArgumentException("The pairing QR payload exceeds its size limit.");
            return payload.ToString();
        }

        private static void AppendJsonString(StringBuilder output, string value)
        {
            output.Append('"');
            foreach (char character in value)
            {
                if (character == '"' || character == '\\') output.Append('\\').Append(character);
                else if (character < 32 || character > 126) output.Append("\\u").Append(((int)character).ToString("x4"));
                else output.Append(character);
            }
            output.Append('"');
        }

        // Returns 0 for RFC1918, 1 for CGNAT, and -1 for every other address.
        internal static int AddressClass(string value)
        {
            if (string.IsNullOrEmpty(value)) return -1;
            string[] parts = value.Split('.');
            if (parts.Length != 4) return -1;
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.Length; i++)
            {
                string part = parts[i];
                if (part.Length < 1 || part.Length > 3 || (part.Length > 1 && part[0] == '0')) return -1;
                int number = 0;
                foreach (char character in part)
                {
                    if (character < '0' || character > '9') return -1;
                    number = number * 10 + character - '0';
                }
                if (number > 255) return -1;
                bytes[i] = (byte)number;
            }
            if (bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) ||
                (bytes[0] == 192 && bytes[1] == 168)) return 0;
            if (bytes[0] == 100 && bytes[1] >= 64 && bytes[1] <= 127) return 1;
            return -1;
        }

        internal sealed class HostCandidate
        {
            public readonly string Address;
            public readonly bool HasDefaultGateway;
            public HostCandidate(string address, bool hasDefaultGateway) { Address = address; HasDefaultGateway = hasDefaultGateway; }
        }

        internal static string[] SelectHosts(IEnumerable<HostCandidate> candidates)
        {
            var ranked = new SortedDictionary<int, SortedSet<string>>();
            foreach (HostCandidate candidate in candidates)
            {
                if (candidate == null) continue;
                int category = AddressClass(candidate.Address);
                if (category < 0) continue;
                int rank = category * 2 + (candidate.HasDefaultGateway ? 0 : 1);
                SortedSet<string> bucket;
                if (!ranked.TryGetValue(rank, out bucket)) { bucket = new SortedSet<string>(StringComparer.Ordinal); ranked.Add(rank, bucket); }
                bucket.Add(candidate.Address);
            }
            var hosts = new List<string>();
            foreach (SortedSet<string> bucket in ranked.Values)
                foreach (string address in bucket)
                    if (!hosts.Contains(address) && hosts.Count < 8) hosts.Add(address);
            if (hosts.Count == 0) throw new NoPairingLanAddressException();
            return hosts.ToArray();
        }

        private static string[] GetLocalHosts()
        {
            var candidates = new List<HostCandidate>();
            try
            {
                foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    try
                    {
                        IPInterfaceProperties properties = nic.GetIPProperties();
                        bool gateway = false;
                        foreach (GatewayIPAddressInformation item in properties.GatewayAddresses)
                            if (item.Address.AddressFamily == AddressFamily.InterNetwork && !item.Address.Equals(IPAddress.Any) && !IPAddress.IsLoopback(item.Address)) gateway = true;
                        foreach (UnicastIPAddressInformation item in properties.UnicastAddresses)
                            if (item.Address.AddressFamily == AddressFamily.InterNetwork)
                                candidates.Add(new HostCandidate(item.Address.ToString(), gateway));
                    }
                    catch (NetworkInformationException) { }
                }
            }
            catch (NetworkInformationException) { }
            return SelectHosts(candidates);
        }

        private static void ValidatePayload(string value)
        {
            if (value == null || value.Length != 90 || !value.StartsWith("DP2:", StringComparison.Ordinal))
                throw new ArgumentException("A valid DeskPad pairing credential is required.");
            string encoded = value.Substring(4);
            foreach (char c in encoded)
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'))
                    throw new ArgumentException("A valid DeskPad pairing credential is required.");
            byte[] decoded;
            try { decoded = Convert.FromBase64String(encoded.Replace('-', '+').Replace('_', '/') + "=="); }
            catch (FormatException) { throw new ArgumentException("A valid DeskPad pairing credential is required."); }
            try
            {
                if (decoded.Length != 64 || Convert.ToBase64String(decoded).TrimEnd('=').Replace('+', '-').Replace('/', '_') != encoded)
                    throw new ArgumentException("A valid DeskPad pairing credential is required.");
            }
            finally { Array.Clear(decoded, 0, decoded.Length); }
        }

        // Keep the first QRCoder JIT boundary after the verified embedded load.
        [MethodImpl(MethodImplOptions.NoInlining)]
        private static Bitmap RenderCore(string payload, int maximumPixels)
        {
            using (var generator = new QRCodeGenerator())
            using (var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.Q))
            using (var qr = new QRCode(data))
            {
                int pixelsPerModule = Math.Max(1, maximumPixels / data.ModuleMatrix.Count);
                return qr.GetGraphic(pixelsPerModule, Color.Black, Color.White, true);
            }
        }
    }
}
