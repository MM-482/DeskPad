using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using DeskPadSecurity;

internal static class FirewallRulesTests
{
    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    public static int Main()
    {
        // BuildScript only constructs the embedded command; it never launches UAC
        // and never reads or changes firewall state.
        string path = @"C:\DeskPad & '$test`\DeskPadPC.exe";
        string script = FirewallRules.BuildScript("Apply", path, new string[] { path });
        Check(script.Contains(Convert.ToBase64String(Encoding.Unicode.GetBytes(path))), "Path was not encoded losslessly.");
        Check(!script.Contains(path), "Raw receiver path was interpolated into PowerShell.");
        Check(script.Contains("$DeskPadAction='Apply'"), "Closed action field missing.");
        Check(script.Contains("function Get-DeskPadPlan"), "Embedded resource missing.");
        string remove = FirewallRules.BuildScript("Remove", path, null);
        Check(remove.Contains("$DeskPadAction='Remove'"), "Remove mode not constructed.");
        string check = FirewallRules.BuildScript("Check", path, null);
        Check(check.Contains("$DeskPadAction='Check'"), "Read-only Check mode not constructed.");
        bool rejected = false;
        try { FirewallRules.BuildScript("Apply;evil", path, null); } catch (ArgumentException) { rejected = true; }
        Check(rejected, "Unrecognized action accepted.");
        rejected = false;
        try { FirewallRules.BuildScript("Apply", @"\\server\share\DeskPadPC.exe", null); } catch (ArgumentException) { rejected = true; }
        Check(rejected, "UNC receiver accepted.");
        rejected = false;
        try { FirewallRules.BuildScript("Apply", @"C:\Other\powershell.exe", null); } catch (ArgumentException) { rejected = true; }
        Check(rejected, "Other executable accepted.");
        string command = FirewallRules.BuildEncodedCommand("exit 23");
        string powershell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe");
        using (var process = Process.Start(new ProcessStartInfo(powershell, "-NoProfile -NonInteractive -EncodedCommand " + command)
            { UseShellExecute = false, CreateNoWindow = true }))
        { process.WaitForExit(); Check(process.ExitCode == 23, "Embedded compressed command did not preserve exit status."); }
        Check(FirewallRules.BuildEncodedCommand(script).Length < 30000, "Embedded helper exceeds command limit.");
        Console.WriteLine("PASS: 11 C# firewall-helper construction/transport checks; no elevation or firewall changes.");
        return 0;
    }
}
