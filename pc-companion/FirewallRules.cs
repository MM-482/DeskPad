using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;

namespace DeskPadSecurity
{
    internal sealed class FirewallResult
    {
        public readonly bool Success;
        public readonly bool Cancelled;
        public readonly int ExitCode;
        public readonly string Message;
        internal FirewallResult(bool success, bool cancelled, int exitCode, string message)
        { Success = success; Cancelled = cancelled; ExitCode = exitCode; Message = message; }
    }

    // Call on a background thread: UAC and the elevated process may take time.
    // The receiver remains unelevated. The embedded script has a closed operation set.
    internal static class FirewallRules
    {
        internal const string ManagedGroup = "DeskPad PC Secure LAN";

        public static FirewallResult Apply(string receiverPath, params string[] additionalCleanupPaths)
        { return Run("Apply", receiverPath, additionalCleanupPaths); }

        public static FirewallResult Check(string receiverPath, params string[] additionalCleanupPaths)
        { return Run("Check", receiverPath, additionalCleanupPaths); }

        public static FirewallResult Remove(string receiverPath, params string[] additionalCleanupPaths)
        { return Run("Remove", receiverPath, additionalCleanupPaths); }

        internal static string BuildScript(string action, string receiverPath, string[] additionalCleanupPaths)
        {
            if (action != "Apply" && action != "Remove" && action != "Check") throw new ArgumentException("Unknown firewall action.");
            string primary = NormalizeReceiverPath(receiverPath);
            var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            paths.Add(primary);
            // Capture the requesting user's path before elevation. Another admin's
            // credentials must not redirect cleanup into that admin's user profile.
            paths.Add(NormalizeReceiverPath(Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData), "Programs", "DeskPad PC", "DeskPadPC.exe")));
            if (additionalCleanupPaths != null)
                foreach (string path in additionalCleanupPaths)
                    if (!string.IsNullOrWhiteSpace(path)) paths.Add(NormalizeReceiverPath(path));

            var script = new StringBuilder();
            script.Append("$DeskPadAction='").Append(action).Append("';\n");
            script.Append("$DeskPadReceiver=").Append(DecodedLiteral(primary)).Append(";\n");
            script.Append("$DeskPadCleanup=@(");
            bool first = true;
            foreach (string path in paths)
            {
                if (!first) script.Append(',');
                script.Append(DecodedLiteral(path)); first = false;
            }
            script.Append(");\n");
            using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("DeskPadFirewall.ps1"))
            {
                if (stream == null) throw new InvalidOperationException("Missing embedded firewall helper.");
                using (var reader = new StreamReader(stream, Encoding.UTF8)) script.Append(reader.ReadToEnd());
            }
            return script.ToString();
        }

        private static string DecodedLiteral(string value)
        {
            // No raw path is interpolated into PowerShell or a shell command.
            return "([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('" +
                Convert.ToBase64String(Encoding.Unicode.GetBytes(value)) + "')))";
        }

        private static string NormalizeReceiverPath(string path)
        {
            string full = Path.GetFullPath(path);
            if (full.StartsWith(@"\\", StringComparison.Ordinal) ||
                !string.Equals(Path.GetFileName(full), "DeskPadPC.exe", StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("A local DeskPadPC.exe path is required.");
            return full;
        }

        internal static string BuildEncodedCommand(string script)
        {
            byte[] source = Encoding.UTF8.GetBytes(script);
            string compressed;
            using (var buffer = new MemoryStream())
            {
                using (var gzip = new GZipStream(buffer, CompressionMode.Compress, true))
                    gzip.Write(source, 0, source.Length);
                compressed = Convert.ToBase64String(buffer.ToArray());
            }
            // Compression keeps a complete, embedded helper below CreateProcess's
            // command limit even with long user paths. No temporary elevated script.
            string wrapper = "$b=[Convert]::FromBase64String('" + compressed +
                "');$m=New-Object IO.MemoryStream(,$b);$z=New-Object IO.Compression.GZipStream($m,([IO.Compression.CompressionMode]::Decompress));" +
                "$r=New-Object IO.StreamReader($z);& ([scriptblock]::Create($r.ReadToEnd()));";
            return Convert.ToBase64String(Encoding.Unicode.GetBytes(wrapper));
        }

        private static FirewallResult Run(string action, string receiverPath, string[] additionalCleanupPaths)
        {
            try
            {
                string command = BuildEncodedCommand(BuildScript(action, receiverPath, additionalCleanupPaths));
                if (command.Length > 30000) throw new InvalidOperationException("Firewall helper arguments exceed the Windows command limit.");
                string powershell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                    "WindowsPowerShell", "v1.0", "powershell.exe");
                var psi = new ProcessStartInfo(powershell,
                    "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + command)
                { UseShellExecute = action != "Check", WindowStyle = ProcessWindowStyle.Hidden };
                if (action == "Check") psi.CreateNoWindow = true;
                else psi.Verb = "runas";
                using (var process = Process.Start(psi))
                {
                    if (process == null) return new FirewallResult(false, false, -1, "Windows did not start the firewall helper.");
                    process.WaitForExit();
                    int code = process.ExitCode;
                    if (code == 0) return new FirewallResult(true, false, 0,
                        action == "Remove" ? "DeskPad firewall rules removed." : "Private-network firewall rules verified.");
                    if (action == "Check") return new FirewallResult(false, false, code,
                        "Wi-Fi is disabled until its private-network firewall rules are verified. Click Allow Firewall to configure them. " +
                        (code == 41 ? "Administrator policy may require review." : "Verification code: " + code + "."));
                    return new FirewallResult(false, false, code, code == 41
                        ? "Other effective DeskPad firewall rules remain. Managed policy or administrator review is required; firewall migration is incomplete."
                        : "Firewall changes could not be verified (code " + code + "). Firewall setup is incomplete.");
                }
            }
            catch (Win32Exception ex)
            {
                bool cancelled = ex.NativeErrorCode == 1223;
                return new FirewallResult(false, cancelled, ex.NativeErrorCode, cancelled
                    ? "Administrator approval was canceled. Firewall setup is incomplete."
                    : "Windows could not run firewall setup: " + ex.Message);
            }
            catch (Exception ex)
            { return new FirewallResult(false, false, -1, action == "Check"
                ? "Wi-Fi is disabled: firewall verification failed. Click Allow Firewall. " + ex.Message
                : "Firewall setup failed: " + ex.Message); }
        }
    }
}
