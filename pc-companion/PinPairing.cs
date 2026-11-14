using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Agreement.Srp;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Math;
using Org.BouncyCastle.Security;

namespace DeskPadPC
{
    internal static class PinPairingSupport
    {
        private const string LibraryHash = "4f96977e9c67334742c683410b3a361258219f0d3084a5e0bc10fba96cf23a0d";
        private static readonly object Gate = new object();
        private static Assembly _library;
        private static bool _registered;
        public static void Initialize()
        {
            lock (Gate)
            {
                if (!_registered) { AppDomain.CurrentDomain.AssemblyResolve += Resolve; _registered = true; }
                Load();
            }
        }
        private static Assembly Resolve(object sender, ResolveEventArgs args)
        {
            if (new AssemblyName(args.Name).Name != "BouncyCastle.Cryptography") return null;
            lock (Gate) { return Load(); }
        }
        private static Assembly Load()
        {
            if (_library != null) return _library;
            using (var stream = typeof(PinPairingSupport).Assembly.GetManifestResourceStream("DeskPad.BouncyCastle.dll"))
            {
                if (stream == null || stream.Length > 8388608) throw new InvalidOperationException("The embedded PIN pairing component is missing or invalid.");
                using (var buffer = new MemoryStream())
                {
                    stream.CopyTo(buffer); byte[] bytes = buffer.ToArray();
                    using (var hash = SHA256.Create())
                        if (BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant() != LibraryHash)
                            throw new InvalidOperationException("The embedded PIN pairing component failed verification.");
                    return _library = Assembly.Load(bytes);
                }
            }
        }
    }

    // Only the explicitly opened, short-lived enrollment window owns a PIN.
    internal sealed class PinPairingController : IDisposable
    {
        internal readonly object Gate = new object();
        private readonly Func<string> _getDp2;
        private readonly byte[] _certificateHash;
        private readonly Func<DateTime> _clock;
        private byte[] _pin;
        private int _remaining;
        private long _generation;
        private bool _open, _beginning;
        private long _openedTimestamp;
        private DateTime _expiresUtc;
        private PinPairingSession _active;

        public PinPairingController(Func<string> getDp2, byte[] localCertSha256, Func<DateTime> testClock = null)
        {
            if (getDp2 == null || localCertSha256 == null || localCertSha256.Length != 32) throw new ArgumentException("A local PC identity is required.");
            PinPairingSupport.Initialize();
            _getDp2 = getDp2; _certificateHash = (byte[])localCertSha256.Clone();
            _clock = testClock ?? (() => DateTime.UtcNow);
        }
        public bool IsOpen { get { lock (Gate) { return IsOpenLocked(); } } }
        public int RemainingAttempts { get { lock (Gate) { return IsOpenLocked() ? _remaining : 0; } } }
        public DateTime ExpiresUtc { get { lock (Gate) { return _expiresUtc; } } }
        public string Open()
        {
            lock (Gate)
            {
                CloseLocked();
                byte[] random = new byte[4]; uint number;
                using (var rng = RandomNumberGenerator.Create())
                    do { rng.GetBytes(random); number = BitConverter.ToUInt32(random, 0); } while (number >= 4294000000U);
                Array.Clear(random, 0, random.Length);
                string pin = (number % 1000000U).ToString("D6", CultureInfo.InvariantCulture);
                _pin = Encoding.ASCII.GetBytes(pin); _remaining = 5; _open = true;
                _openedTimestamp = Stopwatch.GetTimestamp(); _expiresUtc = _clock().AddMinutes(2);
                return pin;
            }
        }
        public void Close() { lock (Gate) { CloseLocked(); } }
        public void Dispose() { Close(); }
        private bool IsOpenLocked()
        {
            if (_open && (_clock() >= _expiresUtc || (Stopwatch.GetTimestamp() - _openedTimestamp) / (double)Stopwatch.Frequency >= 120)) CloseLocked();
            return _open;
        }
        private void CloseLocked()
        {
            _open = false; _remaining = 0; _beginning = false; _generation++;
            if (_pin != null) { Array.Clear(_pin, 0, _pin.Length); _pin = null; }
            if (_active != null) { _active.Clear(); _active = null; }
        }
        public PinPairingSession Begin()
        {
            byte[] pin; long generation;
            lock (Gate)
            {
                if (!IsOpenLocked() || _remaining <= 0 || _beginning || _active != null) throw new InvalidOperationException("PIN pairing is unavailable. Open a new PIN on the PC if needed.");
                // A global attempt is consumed before expensive SRP work, even if the peer disconnects.
                _remaining--; _beginning = true; generation = _generation; pin = (byte[])_pin.Clone();
            }
            PinPairingSession session = null;
            try
            {
                session = new PinPairingSession(this, generation, pin, _certificateHash);
                lock (Gate)
                {
                    if (!IsOpenLocked() || _generation != generation || !_beginning) throw new InvalidOperationException("PIN pairing expired or was closed.");
                    _beginning = false; _active = session;
                }
                return session;
            }
            catch
            {
                lock (Gate)
                {
                    if (_generation == generation) { _beginning = false; if (_remaining == 0) CloseLocked(); }
                    if (session != null) session.Clear();
                }
                throw;
            }
            finally { Array.Clear(pin, 0, pin.Length); }
        }
        internal void EnsureActive(PinPairingSession session, long generation)
        {
            if (!IsOpenLocked() || _generation != generation || !ReferenceEquals(_active, session)) throw new InvalidOperationException("PIN pairing expired or was closed.");
        }
        internal void Release(PinPairingSession session)
        {
            lock (Gate)
            {
                session.Clear();
                if (ReferenceEquals(_active, session)) { _active = null; if (_remaining == 0) CloseLocked(); }
            }
        }
        internal string Complete(PinPairingSession session, long generation)
        {
            EnsureActive(session, generation);
            string code = _getDp2(); PinWire.DecodeCode(code);
            // Fetching the existing credential must not revive an expired enrollment.
            EnsureActive(session, generation); CloseLocked(); return code;
        }
    }

    internal sealed class PinPairingSession : IDisposable
    {
        private readonly PinPairingController _owner;
        private readonly long _generation;
        private readonly string _id, _certificateHex;
        private readonly Dictionary<string, object> _challenge;
        private Srp6Server _server;
        private byte[] _key;
        private int _stage;
        public Dictionary<string, object> Challenge { get { return new Dictionary<string, object>(_challenge, StringComparer.Ordinal); } }

        [MethodImpl(MethodImplOptions.NoInlining)]
        internal PinPairingSession(PinPairingController owner, long generation, byte[] pin, byte[] certificateHash)
        {
            _owner = owner; _generation = generation;
            byte[] sid = new byte[32], salt = new byte[16];
            using (var rng = RandomNumberGenerator.Create()) { rng.GetBytes(sid); rng.GetBytes(salt); }
            _id = PinWire.Encode(sid); _certificateHex = PinWire.Hex(certificateHash);
            byte[] identity = Encoding.UTF8.GetBytes("DeskPad-PIN-v1|" + _certificateHex + "|" + _id);
            var group = Srp6StandardGroups.rfc5054_3072;
            var generator = new Srp6VerifierGenerator(); generator.Init(group, new Sha256Digest());
            var verifier = generator.GenerateVerifier(salt, identity, pin);
            _server = new Srp6Server(); _server.Init(group, verifier, new Sha256Digest(), new SecureRandom());
            var b = _server.GenerateServerCredentials();
            if (b.SignValue <= 0 || b.CompareTo(group.N) >= 0) throw new InvalidOperationException("PIN pairing could not create a valid challenge.");
            _challenge = new Dictionary<string, object>(StringComparer.Ordinal) { { "t", "pin_challenge" }, { "v", 2 }, { "id", _id }, { "salt", PinWire.Encode(salt) }, { "b", PinWire.Encode(PinWire.Pad(b, 384)) } };
        }
        public Dictionary<string, object> VerifyClientProof(Dictionary<string, object> proof)
        {
            lock (_owner.Gate)
            {
                try
                {
                    _owner.EnsureActive(this, _generation);
                    if (_stage != 0) throw new InvalidOperationException();
                    PinWire.Require(proof, "pin_proof");
                    if ((string)proof["id"] != _id) throw new InvalidOperationException();
                    byte[] aBytes = PinWire.Decode((string)proof["a"], 384), m1Bytes = PinWire.Decode((string)proof["m1"], 32);
                    var a = new BigInteger(1, aBytes); var group = Srp6StandardGroups.rfc5054_3072;
                    if (a.SignValue <= 0 || a.CompareTo(group.N) >= 0) throw new InvalidOperationException();
                    _server.CalculateSecret(a);
                    if (!_server.VerifyClientEvidenceMessage(new BigInteger(1, m1Bytes))) throw new InvalidOperationException();
                    var m2 = _server.CalculateServerEvidenceMessage();
                    _key = PinWire.Pad(_server.CalculateSessionKey(), 32);
                    _owner.EnsureActive(this, _generation); _stage = 1;
                    return new Dictionary<string, object>(StringComparer.Ordinal) { { "t", "pin_ready" }, { "v", 2 }, { "id", _id }, { "m2", PinWire.Encode(PinWire.Pad(m2, 32)) } };
                }
                catch { _owner.Release(this); throw new InvalidOperationException("PIN verification failed or expired. Check the PC and try again."); }
            }
        }
        public string ConfirmAndGetCode(Dictionary<string, object> confirmation)
        {
            lock (_owner.Gate)
            {
                try
                {
                    _owner.EnsureActive(this, _generation);
                    if (_stage != 1 || _key == null) throw new InvalidOperationException();
                    PinWire.Require(confirmation, "pin_confirm");
                    if ((string)confirmation["id"] != _id) throw new InvalidOperationException();
                    byte[] received = PinWire.Decode((string)confirmation["proof"], 32), expected;
                    using (var hmac = new HMACSHA256(_key))
                        expected = hmac.ComputeHash(Encoding.ASCII.GetBytes("DeskPad-PIN-v1-confirm|" + _id + "|" + _certificateHex));
                    int difference = 0; for (int i = 0; i < 32; i++) difference |= received[i] ^ expected[i];
                    Array.Clear(expected, 0, expected.Length);
                    if (difference != 0) throw new InvalidOperationException();
                    return _owner.Complete(this, _generation);
                }
                catch { _owner.Release(this); throw new InvalidOperationException("PIN confirmation failed or expired. Check the PC and try again."); }
            }
        }
        internal void Clear()
        {
            _stage = 2; _server = null;
            if (_key != null) { Array.Clear(_key, 0, _key.Length); _key = null; }
        }
        public void Dispose() { _owner.Release(this); }
    }

    internal static class PinWire
    {
        public static Dictionary<string, object> Parse(string json)
        {
            if (json == null || json.Length < 2 || json.Length > 2048) throw new ArgumentException("Invalid PIN pairing message.");
            foreach (char c in json) if (c > 127) throw new ArgumentException("Invalid PIN pairing message.");
            int position = 0; var result = new Dictionary<string, object>(StringComparer.Ordinal);
            Skip(json, ref position); Take(json, ref position, '{'); Skip(json, ref position);
            if (position < json.Length && json[position] == '}') throw new ArgumentException("Invalid PIN pairing message.");
            while (true)
            {
                string key = ReadString(json, ref position); Skip(json, ref position); Take(json, ref position, ':'); Skip(json, ref position);
                object value;
                if (position < json.Length && json[position] == '"') value = ReadString(json, ref position);
                else { Take(json, ref position, '2'); value = 2; }
                if (result.ContainsKey(key)) throw new ArgumentException("Invalid PIN pairing message.");
                result.Add(key, value); Skip(json, ref position);
                if (position < json.Length && json[position] == '}') { position++; break; }
                Take(json, ref position, ','); Skip(json, ref position);
            }
            Skip(json, ref position); if (position != json.Length) throw new ArgumentException("Invalid PIN pairing message.");
            object type; if (!result.TryGetValue("t", out type) || !(type is string)) throw new ArgumentException("Invalid PIN pairing message.");
            Require(result, (string)type); return result;
        }
        internal static void Require(Dictionary<string, object> message, string type)
        {
            string[] fields;
            switch (type)
            {
                case "pin_begin": fields = new[] { "t", "v" }; break;
                case "pin_challenge": fields = new[] { "t", "v", "id", "salt", "b" }; break;
                case "pin_proof": fields = new[] { "t", "v", "id", "a", "m1" }; break;
                case "pin_ready": fields = new[] { "t", "v", "id", "m2" }; break;
                case "pin_confirm": fields = new[] { "t", "v", "id", "proof" }; break;
                case "pin_paired": fields = new[] { "t", "v", "id", "code" }; break;
                default: throw new ArgumentException("Invalid PIN pairing message.");
            }
            if (message == null || message.Count != fields.Length) throw new ArgumentException("Invalid PIN pairing message.");
            foreach (string field in fields)
            {
                object value;
                if (!message.TryGetValue(field, out value) || (field == "v" ? !(value is int) || (int)value != 2 : !(value is string))) throw new ArgumentException("Invalid PIN pairing message.");
            }
            if ((string)message["t"] != type) throw new ArgumentException("Invalid PIN pairing message.");
            foreach (string field in fields)
            {
                if (field == "t" || field == "v") continue;
                if (field == "code") { DecodeCode((string)message[field]); continue; }
                int size = field == "a" || field == "b" ? 384 : field == "salt" ? 16 : 32;
                Decode((string)message[field], size);
            }
        }
        internal static byte[] Decode(string value, int bytes)
        {
            if (value == null || value.Length != (bytes * 8 + 5) / 6) throw new ArgumentException("Invalid PIN pairing encoding.");
            foreach (char c in value) if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) throw new ArgumentException("Invalid PIN pairing encoding.");
            byte[] decoded;
            try { decoded = Convert.FromBase64String(value.Replace('-', '+').Replace('_', '/') + new string('=', (4 - value.Length % 4) % 4)); }
            catch (FormatException) { throw new ArgumentException("Invalid PIN pairing encoding."); }
            if (decoded.Length != bytes || Encode(decoded) != value) throw new ArgumentException("Invalid PIN pairing encoding.");
            return decoded;
        }
        internal static void DecodeCode(string code)
        {
            if (code == null || !code.StartsWith("DP2:", StringComparison.Ordinal)) throw new ArgumentException("A valid local pairing credential is required.");
            byte[] decoded = Decode(code.Substring(4), 64); Array.Clear(decoded, 0, decoded.Length);
        }
        internal static string Encode(byte[] bytes) { return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_'); }
        internal static string Hex(byte[] bytes) { return BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant(); }
        internal static byte[] Pad(BigInteger value, int size)
        {
            byte[] bytes = value.ToByteArrayUnsigned();
            if (value.SignValue < 0 || bytes.Length > size) throw new ArgumentException("Invalid PIN pairing integer.");
            byte[] output = new byte[size]; Buffer.BlockCopy(bytes, 0, output, size - bytes.Length, bytes.Length); return output;
        }
        private static void Skip(string text, ref int position)
        { while (position < text.Length && (text[position] == ' ' || text[position] == '\t' || text[position] == '\r' || text[position] == '\n')) position++; }
        private static void Take(string text, ref int position, char expected)
        { if (position >= text.Length || text[position++] != expected) throw new ArgumentException("Invalid PIN pairing message."); }
        private static string ReadString(string text, ref int position)
        {
            Take(text, ref position, '"'); int start = position;
            while (position < text.Length && text[position] != '"')
            { char c = text[position++]; if (c < 32 || c > 126 || c == '\\') throw new ArgumentException("Invalid PIN pairing message."); }
            if (position >= text.Length) throw new ArgumentException("Invalid PIN pairing message.");
            string result = text.Substring(start, position - start); position++; return result;
        }
    }
}
