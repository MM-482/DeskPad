using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

[assembly: System.Reflection.AssemblyVersion("1.0.1.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.0.1.0")]

namespace DeskPadPC
{
    // The pairing bundle is an out-of-band credential, never a discovery field.
    internal sealed class LanIdentity : IDisposable
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("DeskPad LAN identity v2");
        private readonly object _gate = new object();
        private readonly string _path;
        private byte[] _token;
        public readonly X509Certificate2 Certificate;

        private LanIdentity(X509Certificate2 certificate, byte[] token, string path)
        { Certificate = certificate; _token = token; _path = path; }

        public static LanIdentity LoadOrCreate(string storagePath = null)
        {
            string path = storagePath ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "DeskPad PC", "lan-identity-v2.bin");
            if (!File.Exists(path))
            {
                var fresh = Create(path);
                try { fresh.Save(fresh._token); return fresh; }
                catch { fresh.Dispose(); throw; }
            }
            var info = new FileInfo(path);
            if (info.Length <= 0 || info.Length > 131072) throw new InvalidDataException("Invalid LAN identity storage.");
            byte[] plain = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
            try
            {
                using (var buffer = new MemoryStream(plain, false))
                using (var reader = new BinaryReader(buffer))
                {
                    if (reader.ReadInt32() != 2) throw new InvalidDataException("Unsupported LAN identity.");
                    int length = reader.ReadInt32();
                    if (length < 1 || length > 65536) throw new InvalidDataException("Invalid LAN certificate.");
                    byte[] pfx = reader.ReadBytes(length);
                    byte[] token = reader.ReadBytes(32);
                    if (pfx.Length != length || token.Length != 32 || buffer.Position != buffer.Length)
                        throw new InvalidDataException("Incomplete LAN identity.");
                    var certificate = new X509Certificate2(pfx, "", X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
                    Array.Clear(pfx, 0, pfx.Length);
                    if (!certificate.HasPrivateKey || !HasStrongRsaKey(certificate) || certificate.NotAfter.ToUniversalTime() <= DateTime.UtcNow ||
                        certificate.NotBefore.ToUniversalTime() > DateTime.UtcNow)
                    { certificate.Dispose(); throw new InvalidDataException("LAN certificate is invalid or expired; reset pairing locally."); }
                    return new LanIdentity(certificate, token, path);
                }
            }
            finally { Array.Clear(plain, 0, plain.Length); }
        }

        // An ephemeral identity allows isolated loopback protocol tests without loading user credentials.
        internal static LanIdentity CreateEphemeral() { return Create(null); }

        private static LanIdentity Create(string path)
        {
            using (RSA rsa = new RSACng(2048))
            {
                var request = new CertificateRequest("CN=DeskPad PC", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
                request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
                var usages = new OidCollection(); usages.Add(new Oid("1.3.6.1.5.5.7.3.1"));
                request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(usages, true));
                using (var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5)))
                {
                    var certificate = new X509Certificate2(generated.Export(X509ContentType.Pfx, ""), "",
                        X509KeyStorageFlags.Exportable | X509KeyStorageFlags.UserKeySet);
                    if (!HasStrongRsaKey(certificate)) { certificate.Dispose(); throw new CryptographicException("LAN certificate key is too weak."); }
                    return new LanIdentity(certificate, RandomToken(), path);
                }
            }
        }

        internal static bool HasStrongRsaKey(X509Certificate2 certificate)
        {
            using (RSA publicKey = certificate.GetRSAPublicKey()) return publicKey != null && publicKey.KeySize >= 2048;
        }

        private static byte[] RandomToken()
        { var bytes = new byte[32]; using (var random = RandomNumberGenerator.Create()) random.GetBytes(bytes); return bytes; }

        internal static string Base64Url(byte[] bytes)
        { return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_'); }

        public string PairingCode
        {
            get
            {
                lock (_gate)
                {
                    var bundle = new byte[64];
                    using (var hash = SHA256.Create()) Buffer.BlockCopy(hash.ComputeHash(Certificate.RawData), 0, bundle, 0, 32);
                    Buffer.BlockCopy(_token, 0, bundle, 32, 32);
                    return "DP2:" + Base64Url(bundle);
                }
            }
        }

        public bool Authenticate(string encodedToken)
        {
            if (encodedToken == null || encodedToken.Length != 43) return false;
            foreach (char c in encodedToken)
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
            byte[] candidate;
            try { candidate = Convert.FromBase64String(encodedToken.Replace('-', '+').Replace('_', '/') + "="); }
            catch (FormatException) { return false; }
            if (candidate.Length != 32 || Base64Url(candidate) != encodedToken) return false;
            try
            {
                lock (_gate)
                {
                    int difference = 0;
                    for (int i = 0; i < 32; i++) difference |= candidate[i] ^ _token[i];
                    return difference == 0;
                }
            }
            finally { Array.Clear(candidate, 0, candidate.Length); }
        }

        public void RotateToken()
        {
            lock (_gate)
            {
                byte[] next = RandomToken();
                try { Save(next); }
                catch { Array.Clear(next, 0, next.Length); throw; }
                Array.Clear(_token, 0, _token.Length); _token = next;
            }
        }

        private void Save(byte[] token)
        {
            if (_path == null) return;
            byte[] pfx = Certificate.Export(X509ContentType.Pfx, "");
            byte[] plain;
            using (var buffer = new MemoryStream())
            {
                using (var writer = new BinaryWriter(buffer, Encoding.UTF8, true))
                { writer.Write(2); writer.Write(pfx.Length); writer.Write(pfx); writer.Write(token); }
                plain = buffer.ToArray();
            }
            Array.Clear(pfx, 0, pfx.Length);
            byte[] protectedBytes;
            try { protectedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser); }
            finally { Array.Clear(plain, 0, plain.Length); }
            Directory.CreateDirectory(Path.GetDirectoryName(_path));
            string temporary = _path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                File.WriteAllBytes(temporary, protectedBytes);
                if (File.Exists(_path)) File.Replace(temporary, _path, null);
                else File.Move(temporary, _path);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
        }

        public void Dispose()
        {
            lock (_gate) { Array.Clear(_token, 0, _token.Length); Certificate.Dispose(); }
        }
    }
}


