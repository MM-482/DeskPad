using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace DeskPadSetup
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        private static void Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            bool uninstall = false;
            bool silent = false;
            foreach (string a in args)
            {
                if (string.Equals(a, "/uninstall", StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(a, "--uninstall", StringComparison.OrdinalIgnoreCase))
                    uninstall = true;
                if (string.Equals(a, "/silent", StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(a, "/S", StringComparison.OrdinalIgnoreCase))
                    silent = true;
            }

            if (uninstall && silent)
            {
                InstallerEngine.Uninstall();
                return;
            }

            if (!uninstall && silent)
            {
                InstallerEngine.Install(true, true, false, false, null);
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new SetupForm(uninstall));
        }
    }

    internal static class Gfx
    {
        public static GraphicsPath RoundedRect(RectangleF bounds, float radius)
        {
            float d = Math.Min(radius * 2f, Math.Min(bounds.Width, bounds.Height));
            var path = new GraphicsPath();
            path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
            path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
            path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
            path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }
    }

    internal sealed class PillButton : Control
    {
        private bool _hover;
        private bool _down;

        public Color FillColor { get; set; }
        public Color HoverColor { get; set; }
        public Color BorderColor { get; set; }
        public Color TextColor { get; set; }
        public int CornerRadius { get; set; }
        public bool HasBorder { get; set; }

        public PillButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            CornerRadius = 11;
            HasBorder = true;
            Cursor = Cursors.Hand;
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
        }

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e) { _down = true; Invalidate(); base.OnMouseDown(e); }
        protected override void OnMouseUp(MouseEventArgs e) { _down = false; Invalidate(); base.OnMouseUp(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            Color fill = _down ? ControlPaint.Dark(FillColor, 0.08f) : (_hover ? HoverColor : FillColor);
            using (var path = Gfx.RoundedRect(rect, CornerRadius))
            {
                using (var b = new SolidBrush(fill)) g.FillPath(b, path);
                if (HasBorder)
                {
                    using (var p = new Pen(BorderColor, 1f)) g.DrawPath(p, path);
                }
            }
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), TextColor, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }
    }

    internal sealed class ToggleRow : Control
    {
        public bool Checked { get; set; }
        public string LabelText { get; set; }

        private readonly Color _accent = Color.FromArgb(255, 59, 48);
        private readonly Color _boxBg = Color.FromArgb(35, 35, 41);
        private readonly Color _border = Color.FromArgb(58, 58, 66);
        private readonly Color _text = Color.FromArgb(244, 244, 240);

        public ToggleRow(string text, bool initial)
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
            LabelText = text;
            Checked = initial;
            Cursor = Cursors.Hand;
            Font = new Font("Segoe UI", 9.5f);
        }

        protected override void OnClick(EventArgs e)
        {
            Checked = !Checked;
            Invalidate();
            base.OnClick(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var box = new RectangleF(2, (Height - 18) / 2f, 18, 18);
            using (var path = Gfx.RoundedRect(box, 5))
            {
                using (var b = new SolidBrush(Checked ? _accent : _boxBg)) g.FillPath(b, path);
                using (var p = new Pen(Checked ? _accent : _border, 1f)) g.DrawPath(p, path);
            }
            if (Checked)
            {
                using (var pen = new Pen(Color.White, 2f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                {
                    g.DrawLine(pen, 6, Height / 2f, 9.5f, Height / 2f + 3.5f);
                    g.DrawLine(pen, 9.5f, Height / 2f + 3.5f, 16, Height / 2f - 3.5f);
                }
            }
            TextRenderer.DrawText(g, LabelText, Font, new Rectangle(28, 0, Width - 28, Height), _text, TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        }
    }

    internal sealed class SetupForm : Form
    {
        private readonly bool _uninstallMode;
        private readonly Icon _appIcon;
        private readonly Color _bg = Color.FromArgb(16, 16, 18);
        private readonly Color _surface = Color.FromArgb(24, 24, 28);
        private readonly Color _surfaceElevated = Color.FromArgb(35, 35, 41);
        private readonly Color _border = Color.FromArgb(50, 50, 58);
        private readonly Color _textPrimary = Color.FromArgb(244, 244, 240);
        private readonly Color _textMuted = Color.FromArgb(146, 146, 154);
        private readonly Color _accent = Color.FromArgb(255, 59, 48);
        private readonly Color _ok = Color.FromArgb(52, 211, 153);

        private readonly ToggleRow _desktopCheck;
        private readonly ToggleRow _startMenuCheck;
        private readonly ToggleRow _startupCheck;
        private readonly ToggleRow _launchCheck;
        private readonly PillButton _actionBtn;
        private readonly PillButton _cancelBtn;
        private readonly PillButton _closeBtn;

        private int _progress;
        private bool _done;
        private bool _busy;
        private string _statusLine;

        private const int WM_NCLBUTTONDOWN = 0xA1;
        private const int HT_CAPTION = 0x2;
        [DllImport("user32.dll")] private static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);
        [DllImport("user32.dll")] private static extern bool ReleaseCapture();

        public SetupForm(bool uninstallMode)
        {
            _uninstallMode = uninstallMode;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.UserPaint, true);

            Text = uninstallMode ? "Uninstall DeskPad PC" : "DeskPad PC Setup";
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(460, uninstallMode ? 240 : 376);
            BackColor = _bg;

            _appIcon = LoadEmbeddedIcon();
            Icon = _appIcon;

            _statusLine = uninstallMode
                ? "Removes DeskPad PC, shortcuts, and startup entry."
                : "Installs DeskPad PC to " + InstallerEngine.InstallDir;

            _closeBtn = new PillButton
            {
                Text = "✕",
                Location = new Point(408, 16),
                Size = new Size(32, 28),
                CornerRadius = 9,
                FillColor = _surfaceElevated,
                HoverColor = ControlPaint.Light(_surfaceElevated, 0.08f),
                BorderColor = _border,
                TextColor = _textPrimary,
                Font = new Font("Segoe UI", 9f, FontStyle.Bold)
            };
            _closeBtn.Click += delegate { if (!_busy) Close(); };
            Controls.Add(_closeBtn);

            if (!uninstallMode)
            {
                _desktopCheck = new ToggleRow("Create Desktop shortcut", true) { Location = new Point(36, 174), Size = new Size(388, 26) };
                _startMenuCheck = new ToggleRow("Add to Start Menu", true) { Location = new Point(36, 204), Size = new Size(388, 26) };
                _startupCheck = new ToggleRow("Run DeskPad PC when Windows starts", false) { Location = new Point(36, 234), Size = new Size(388, 26) };
                _launchCheck = new ToggleRow("Launch DeskPad PC when finished", true) { Location = new Point(36, 264), Size = new Size(388, 26) };
                Controls.AddRange(new Control[] { _desktopCheck, _startMenuCheck, _startupCheck, _launchCheck });
            }

            int btnY = uninstallMode ? 182 : 318;
            _cancelBtn = new PillButton
            {
                Text = "Cancel",
                Location = new Point(20, btnY),
                Size = new Size(120, 38),
                CornerRadius = 12,
                FillColor = _surfaceElevated,
                HoverColor = ControlPaint.Light(_surfaceElevated, 0.08f),
                BorderColor = _border,
                TextColor = _textPrimary
            };
            _cancelBtn.Click += delegate { if (!_busy) Close(); };

            _actionBtn = new PillButton
            {
                Text = uninstallMode ? "Uninstall" : (InstallerEngine.IsInstalled() ? "Update / Reinstall" : "Install DeskPad PC"),
                Location = new Point(152, btnY),
                Size = new Size(288, 38),
                CornerRadius = 12,
                HasBorder = false,
                FillColor = _accent,
                HoverColor = ControlPaint.Light(_accent, 0.08f),
                TextColor = Color.White
            };
            _actionBtn.Click += OnActionClicked;

            Controls.Add(_cancelBtn);
            Controls.Add(_actionBtn);
            UpdateRegion();
        }

        private static Icon LoadEmbeddedIcon()
        {
            try
            {
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("DeskPad.ico"))
                {
                    if (s != null) return new Icon(s, new Size(48, 48));
                }
            }
            catch { }
            try { return Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application; }
            catch { return SystemIcons.Application; }
        }

        private void UpdateRegion()
        {
            using (var path = Gfx.RoundedRect(new RectangleF(0, 0, Width, Height), 18))
            {
                Region = new Region(path);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button == MouseButtons.Left && e.Y < 56)
            {
                ReleaseCapture();
                SendMessage(Handle, WM_NCLBUTTONDOWN, HT_CAPTION, 0);
            }
        }

        private void OnActionClicked(object sender, EventArgs e)
        {
            if (_done)
            {
                Close();
                return;
            }
            if (_busy) return;
            _busy = true;
            _actionBtn.Text = _uninstallMode ? "Removing..." : "Installing...";
            _cancelBtn.Enabled = false;

            bool desktop = _desktopCheck != null && _desktopCheck.Checked;
            bool startMenu = _startMenuCheck != null && _startMenuCheck.Checked;
            bool startup = _startupCheck != null && _startupCheck.Checked;
            bool launch = _launchCheck != null && _launchCheck.Checked;

            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    if (_uninstallMode)
                    {
                        UpdateProgress(35, "Stopping running instances...");
                        InstallerEngine.Uninstall();
                        UpdateProgress(100, "DeskPad PC has been uninstalled.");
                    }
                    else
                    {
                        InstallerEngine.Install(desktop, startMenu, startup, launch, delegate(int pct, string msg)
                        {
                            UpdateProgress(pct, msg);
                        });
                        UpdateProgress(100, "Installation complete.");
                    }
                    BeginInvoke((MethodInvoker)delegate
                    {
                        _busy = false;
                        _done = true;
                        _actionBtn.FillColor = _ok;
                        _actionBtn.HoverColor = ControlPaint.Light(_ok, 0.08f);
                        _actionBtn.TextColor = Color.FromArgb(10, 22, 16);
                        _actionBtn.Text = "Done";
                        _cancelBtn.Text = "Close";
                        _cancelBtn.Enabled = true;
                        Invalidate();
                    });
                }
                catch (Exception ex)
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        _busy = false;
                        _statusLine = "Error: " + ex.Message;
                        _actionBtn.Text = "Retry";
                        _cancelBtn.Enabled = true;
                        Invalidate();
                    });
                }
            });
        }

        private void UpdateProgress(int pct, string msg)
        {
            if (IsDisposed) return;
            try
            {
                BeginInvoke((MethodInvoker)delegate
                {
                    _progress = pct;
                    _statusLine = msg;
                    Invalidate();
                });
            }
            catch { }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            using (var outer = Gfx.RoundedRect(new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f), 18))
            using (var pen = new Pen(_border, 1.2f))
            {
                g.DrawPath(pen, outer);
            }

            g.DrawIcon(_appIcon, new Rectangle(20, 16, 26, 26));
            TextRenderer.DrawText(g, _uninstallMode ? "Uninstall DeskPad PC" : "DeskPad PC Installer", new Font("Segoe UI", 11.5f, FontStyle.Bold), new Point(54, 14), _textPrimary);
            TextRenderer.DrawText(g, "Version 1.0  •  Windows Companion", new Font("Segoe UI", 8f), new Point(54, 34), _textMuted);

            var card = new RectangleF(20, 62, 420, 94);
            using (var path = Gfx.RoundedRect(card, 14))
            {
                using (var bg = new SolidBrush(_surface)) g.FillPath(bg, path);
                using (var pen = new Pen(_border, 1f)) g.DrawPath(pen, path);
            }

            TextRenderer.DrawText(g, _uninstallMode ? "UNINSTALL" : "DESTINATION", new Font("Segoe UI", 7.5f, FontStyle.Bold), new Point(36, 74), _textMuted);
            TextRenderer.DrawText(g, _statusLine, new Font("Segoe UI", 9.5f, FontStyle.Bold), new Rectangle(36, 94, 388, 22), _done ? _ok : _textPrimary, TextFormatFlags.EndEllipsis);

            // Progress Bar
            var track = new RectangleF(36, 128, 388, 10);
            using (var trackPath = Gfx.RoundedRect(track, 5))
            using (var trackBg = new SolidBrush(_surfaceElevated))
            {
                g.FillPath(trackBg, trackPath);
            }
            if (_progress > 0)
            {
                float fillW = Math.Max(10f, track.Width * (_progress / 100f));
                using (var fillPath = Gfx.RoundedRect(new RectangleF(track.X, track.Y, fillW, track.Height), 5))
                using (var fillBg = new SolidBrush(_done ? _ok : _accent))
                {
                    g.FillPath(fillBg, fillPath);
                }
            }

            if (!_uninstallMode)
            {
                var optCard = new RectangleF(20, 166, 420, 134);
                using (var path = Gfx.RoundedRect(optCard, 14))
                {
                    using (var bg = new SolidBrush(_surface)) g.FillPath(bg, path);
                    using (var pen = new Pen(_border, 1f)) g.DrawPath(pen, path);
                }
            }
        }
    }

    internal static class InstallerEngine
    {
        public static readonly string InstallDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Programs",
            "DeskPad PC"
        );

        private static string ExePath { get { return Path.Combine(InstallDir, "DeskPadPC.exe"); } }
        private static string IcoPath { get { return Path.Combine(InstallDir, "DeskPad.ico"); } }
        private static string UninstallerCopyPath { get { return Path.Combine(InstallDir, "DeskPadPCSetup.exe"); } }

        private static string StartMenuShortcut
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "DeskPad PC.lnk"); }
        }

        private static string DesktopShortcut
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "DeskPad PC.lnk"); }
        }

        private const string UninstallRegKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\DeskPadPC";
        private const string RunRegKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public static bool IsInstalled()
        {
            return File.Exists(ExePath);
        }

        public static void Install(bool createDesktop, bool createStartMenu, bool addStartup, bool launchAfter, Action<int, string> onProgress)
        {
            Action<int, string> report = delegate(int p, string m) { if (onProgress != null) onProgress(p, m); };

            report(15, "Closing any running DeskPad PC instance...");
            KillRunning();
            Thread.Sleep(250);

            report(35, "Creating installation directory...");
            Directory.CreateDirectory(InstallDir);

            report(55, "Extracting DeskPadPC.exe and icon assets...");
            ExtractResource("DeskPadPC.exe", ExePath);
            ExtractResource("DeskPad.ico", IcoPath);

            try
            {
                string self = Application.ExecutablePath;
                if (!string.Equals(Path.GetFullPath(self), Path.GetFullPath(UninstallerCopyPath), StringComparison.OrdinalIgnoreCase))
                {
                    File.Copy(self, UninstallerCopyPath, true);
                }
            }
            catch { }

            report(75, "Creating shortcuts & Windows app registration...");
            if (createStartMenu) CreateShortcut(StartMenuShortcut, ExePath, InstallDir, IcoPath);
            if (createDesktop) CreateShortcut(DesktopShortcut, ExePath, InstallDir, IcoPath);
            SetStartup(addStartup);
            RegisterUninstallEntry();

            if (launchAfter)
            {
                report(92, "Starting DeskPad PC...");
                Process.Start(new ProcessStartInfo(ExePath) { WorkingDirectory = InstallDir });
            }
        }

        public static void Uninstall()
        {
            KillRunning();
            Thread.Sleep(250);

            TryDelete(StartMenuShortcut);
            TryDelete(DesktopShortcut);
            SetStartup(false);

            try { Registry.CurrentUser.DeleteSubKeyTree(UninstallRegKey, false); } catch { }
            TryDelete(ExePath);
            TryDelete(IcoPath);

            try
            {
                string self = Application.ExecutablePath;
                if (string.Equals(Path.GetFullPath(self), Path.GetFullPath(UninstallerCopyPath), StringComparison.OrdinalIgnoreCase))
                {
                    // Schedule self-cleanup after exit
                    var psi = new ProcessStartInfo("cmd.exe", "/c timeout /t 2 >nul & rmdir /s /q \"" + InstallDir + "\"")
                    {
                        CreateNoWindow = true,
                        UseShellExecute = false
                    };
                    Process.Start(psi);
                }
                else
                {
                    if (Directory.Exists(InstallDir)) Directory.Delete(InstallDir, true);
                }
            }
            catch { }
        }

        private static void KillRunning()
        {
            try
            {
                foreach (var p in Process.GetProcessesByName("DeskPadPC"))
                {
                    try { p.Kill(); p.WaitForExit(1500); } catch { }
                }
            }
            catch { }
        }

        private static void ExtractResource(string resourceName, string targetPath)
        {
            var asm = Assembly.GetExecutingAssembly();
            using (var stream = asm.GetManifestResourceStream(resourceName))
            {
                if (stream == null) throw new InvalidOperationException("Missing embedded resource: " + resourceName);
                using (var fs = new FileStream(targetPath, FileMode.Create, FileAccess.Write))
                {
                    stream.CopyTo(fs);
                }
            }
        }

        private static void CreateShortcut(string shortcutPath, string targetPath, string workDir, string iconPath)
        {
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            object shell = Activator.CreateInstance(shellType);
            try
            {
                object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcutPath });
                Type scType = shortcut.GetType();
                scType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { targetPath });
                scType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { workDir });
                scType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { iconPath + ",0" });
                scType.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut, new object[] { "DeskPad PC Trackpad & Keyboard Receiver" });
                scType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
            }
            finally
            {
                Marshal.FinalReleaseComObject(shell);
            }
        }

        private static void SetStartup(bool enabled)
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(RunRegKey, true))
                {
                    if (key == null) return;
                    if (enabled) key.SetValue("DeskPadPC", "\"" + ExePath + "\"");
                    else key.DeleteValue("DeskPadPC", false);
                }
            }
            catch { }
        }

        private static void RegisterUninstallEntry()
        {
            using (var key = Registry.CurrentUser.CreateSubKey(UninstallRegKey))
            {
                if (key == null) return;
                key.SetValue("DisplayName", "DeskPad PC");
                key.SetValue("DisplayVersion", "1.0.0");
                key.SetValue("Publisher", "DeskPad");
                key.SetValue("DisplayIcon", IcoPath);
                key.SetValue("InstallLocation", InstallDir);
                key.SetValue("UninstallString", "\"" + UninstallerCopyPath + "\" /uninstall");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                key.SetValue("EstimatedSize", 120, RegistryValueKind.DWord);
            }
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }
    }
}
