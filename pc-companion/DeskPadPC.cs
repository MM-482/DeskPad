using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;
using DeskPadSecurity;

namespace DeskPadPC
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        private static void Main()
        {
            PairingQrSupport.Initialize();
            PinPairingSupport.Initialize();
            try { SetProcessDPIAware(); } catch { }
            bool createdNew;
            using (var mutex = new Mutex(true, "DeskPadPC_SingleInstance_Mutex", out createdNew))
            {
                if (!createdNew)
                {
                    MessageBox.Show("DeskPad PC Receiver is already running in your system tray.", "DeskPad PC", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new DeskPadForm());
            }
        }
    }

    internal sealed class UiPalette
    {
        public string Name;
        public bool IsCyber;
        public Color Background;
        public Color Surface;
        public Color SurfaceElevated;
        public Color Border;
        public Color TextPrimary;
        public Color TextMuted;
        public Color Accent;
        public Color StatusConnected;
        public Color GridDot;
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
            TextRenderer.DrawText(g, Text, Font, new Rectangle(0, 0, Width, Height), TextColor, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
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

    internal sealed class DeskPadForm : Form
    {
        private const int DiscoveryPort = 48910;
        private const int TcpPort = 48912;

        private readonly UiPalette _tactilePalette = new UiPalette
        {
            Name = "Tactile Mono",
            IsCyber = false,
            Background = Color.FromArgb(16, 16, 18),
            Surface = Color.FromArgb(24, 24, 28),
            SurfaceElevated = Color.FromArgb(35, 35, 41),
            Border = Color.FromArgb(50, 50, 58),
            TextPrimary = Color.FromArgb(244, 244, 240),
            TextMuted = Color.FromArgb(146, 146, 154),
            Accent = Color.FromArgb(255, 59, 48),
            StatusConnected = Color.FromArgb(52, 211, 153),
            GridDot = Color.FromArgb(30, 255, 255, 255)
        };

        private readonly UiPalette _cyberPalette = new UiPalette
        {
            Name = "Cyber Deck",
            IsCyber = true,
            Background = Color.FromArgb(11, 13, 23),
            Surface = Color.FromArgb(18, 22, 41),
            SurfaceElevated = Color.FromArgb(29, 34, 64),
            Border = Color.FromArgb(95, 139, 92, 246),
            TextPrimary = Color.FromArgb(238, 242, 255),
            TextMuted = Color.FromArgb(129, 140, 248),
            Accent = Color.FromArgb(168, 85, 247),
            StatusConnected = Color.FromArgb(52, 211, 153),
            GridDot = Color.FromArgb(32, 139, 92, 246)
        };

        private UiPalette _theme;
        private bool _connected;
        private string _statusText = "Waiting for DeskPad phone";
        private string _activityText = "Idle • Ready for Wi-Fi discovery";
        private long _packetCount;
        private DateTime _lastActivityUtc = DateTime.MinValue;
        private string _copiedNotice = "";

        private readonly PillButton _themeBtn;
        private readonly PillButton _minBtn;
        private readonly PillButton _closeBtn;
        private readonly PillButton _copyBtn;
        private readonly PillButton _firewallBtn;
        private readonly PillButton _startupBtn;
        private readonly PillButton _trayBtn;
        private readonly PillButton _pairBtn;
        private readonly LanIdentity _identity;

        private readonly NotifyIcon _trayIcon;
        private ServerEngine _engine;
        private bool _lanStarted;
        private readonly System.Windows.Forms.Timer _uiTimer;
        private readonly Icon _appIcon;
        private bool _allowExit;

        private const int WM_NCLBUTTONDOWN = 0xA1;
        private const int HT_CAPTION = 0x2;
        [DllImport("user32.dll")]
        private static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);
        [DllImport("user32.dll")]
        private static extern bool ReleaseCapture();

        public DeskPadForm()
        {
            _theme = _tactilePalette;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.UserPaint, true);

            Text = "DeskPad PC";
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(440, 412);
            BackColor = _theme.Background;

            _appIcon = LoadEmbeddedIcon();
            Icon = _appIcon;

            _themeBtn = new PillButton { Text = "Cyber Deck", Location = new Point(244, 16), Size = new Size(96, 28), CornerRadius = 14, Font = new Font("Segoe UI", 8.5f, FontStyle.Bold) };
            _themeBtn.Click += delegate
            {
                _theme = _theme.IsCyber ? _tactilePalette : _cyberPalette;
                _themeBtn.Text = _theme.IsCyber ? "Tactile Mono" : "Cyber Deck";
                ApplyPalette();
            };

            _minBtn = new PillButton { Text = "—", Location = new Point(350, 16), Size = new Size(32, 28), CornerRadius = 9, Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
            _minBtn.Click += delegate { Hide(); };

            _closeBtn = new PillButton { Text = "✕", Location = new Point(388, 16), Size = new Size(32, 28), CornerRadius = 9, Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
            _closeBtn.Click += delegate
            {
                _allowExit = true;
                Close();
            };

            _copyBtn = new PillButton { Text = "Copy IP", Location = new Point(316, 174), Size = new Size(88, 30), CornerRadius = 10, Font = new Font("Segoe UI", 8.5f, FontStyle.Bold) };
            _copyBtn.Click += delegate
            {
                string ip = NetworkUtils.GetPrimaryIp();
                if (!string.IsNullOrEmpty(ip))
                {
                    Clipboard.SetText(ip);
                    _copiedNotice = "Copied " + ip;
                    _copyBtn.Text = "Copied ✓";
                    Invalidate();
                }
            };

            _firewallBtn = new PillButton { Text = "Allow Firewall", Location = new Point(20, 350), Size = new Size(126, 38), CornerRadius = 12 };
            _firewallBtn.Click += delegate { ConfigureFirewallRule(); };

            _startupBtn = new PillButton { Text = IsStartupEnabled() ? "Startup: On" : "Startup: Off", Location = new Point(156, 350), Size = new Size(126, 38), CornerRadius = 12 };
            _startupBtn.Click += delegate
            {
                bool next = !IsStartupEnabled();
                SetStartupEnabled(next);
                _startupBtn.Text = next ? "Startup: On" : "Startup: Off";
                ApplyPalette();
            };

            _trayBtn = new PillButton { Text = "Hide to Tray", Location = new Point(292, 350), Size = new Size(128, 38), CornerRadius = 12, HasBorder = false };
            _trayBtn.Click += delegate { Hide(); };

            _pairBtn = new PillButton { Text = "Pair phone / Reset access", Location = new Point(20, 294), Size = new Size(400, 42), CornerRadius = 12 };
            _pairBtn.Click += delegate { ShowPairingDialog(); };
            Controls.AddRange(new Control[] { _themeBtn, _minBtn, _closeBtn, _copyBtn, _firewallBtn, _startupBtn, _trayBtn, _pairBtn });

            var menu = new ContextMenuStrip();
            menu.Items.Add("Open DeskPad PC", null, delegate { ShowAndActivate(); });
            menu.Items.Add("Copy LAN IP (" + NetworkUtils.GetPrimaryIp() + ")", null, delegate
            {
                Clipboard.SetText(NetworkUtils.GetPrimaryIp());
            });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Quit", null, delegate
            {
                _allowExit = true;
                Close();
            });

            _trayIcon = new NotifyIcon
            {
                Icon = _appIcon,
                Text = "DeskPad PC • " + NetworkUtils.GetPrimaryIp(),
                Visible = true,
                ContextMenuStrip = menu
            };
            _trayIcon.DoubleClick += delegate { ShowAndActivate(); };

            ApplyPalette();
            UpdateWindowRegion();

            try
            {
                _identity = LanIdentity.LoadOrCreate();
                _engine = new ServerEngine(DiscoveryPort, TcpPort, _identity, OnStatusChanged, OnInputActivity);
                _statusText = "Checking Windows network access...";
                _activityText = "Wi-Fi stays off until Private-only rules are verified";
                _firewallBtn.Enabled = false;
                Shown += delegate
                {
                    string receiverPath = Application.ExecutablePath;
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        FirewallResult result = FirewallRules.Check(receiverPath);
                        if (IsDisposed) return;
                        try { BeginInvoke((MethodInvoker)delegate { FinishFirewallCheck(result, false); }); }
                        catch (InvalidOperationException) { }
                    });
                };
            }
            catch (Exception)
            {
                if (_engine != null) _engine.Stop();
                _pairBtn.Enabled = false;
                _statusText = "Wi-Fi disabled";
                _activityText = "Could not load identity or open secure listener";
                MessageBox.Show("DeskPad could not start secure Wi-Fi. No insecure fallback was opened. Check that another receiver is not running and that this user's LAN identity storage is accessible. See the security update guide for explicit identity reset instructions.", "DeskPad PC", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }

            _uiTimer = new System.Windows.Forms.Timer { Interval = 350 };
            _uiTimer.Tick += delegate
            {
                if (!string.IsNullOrEmpty(_copiedNotice) && _copyBtn.Text == "Copied ✓")
                {
                    // Reset label smoothly after a short beat
                    if ((DateTime.UtcNow - _lastActivityUtc).TotalSeconds > 1.5)
                    {
                        _copyBtn.Text = "Copy IP";
                        _copiedNotice = "";
                    }
                }
                Invalidate(new Rectangle(20, 62, 400, 92));
            };
            _uiTimer.Start();
        }

        private void ApplyPalette()
        {
            BackColor = _theme.Background;

            Action<PillButton, bool> styleSec = delegate(PillButton btn, bool active)
            {
                btn.FillColor = active ? Color.FromArgb(40, _theme.Accent.R, _theme.Accent.G, _theme.Accent.B) : _theme.SurfaceElevated;
                btn.HoverColor = ControlPaint.Light(_theme.SurfaceElevated, 0.08f);
                btn.BorderColor = active ? _theme.Accent : _theme.Border;
                btn.TextColor = active ? _theme.Accent : _theme.TextPrimary;
                btn.Invalidate();
            };

            styleSec(_themeBtn, false);
            styleSec(_minBtn, false);
            styleSec(_closeBtn, false);
            styleSec(_copyBtn, false);
            styleSec(_firewallBtn, false);
            styleSec(_pairBtn, false);
            styleSec(_startupBtn, IsStartupEnabled());

            _trayBtn.FillColor = _theme.Accent;
            _trayBtn.HoverColor = ControlPaint.Light(_theme.Accent, 0.08f);
            _trayBtn.TextColor = Color.White;
            _trayBtn.Invalidate();

            Invalidate();
        }

        private void UpdateWindowRegion()
        {
            using (var path = Gfx.RoundedRect(new RectangleF(0, 0, Width, Height), 18))
            {
                Region = new Region(path);
            }
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            UpdateWindowRegion();
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

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            // Outer frame border
            using (var outerPath = Gfx.RoundedRect(new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f), 18))
            using (var outerPen = new Pen(_theme.Border, 1.2f))
            {
                g.DrawPath(outerPen, outerPath);
            }

            // Header Icon + Title
            g.DrawIcon(_appIcon, new Rectangle(20, 16, 26, 26));
            TextRenderer.DrawText(g, "DeskPad PC", new Font("Segoe UI", 11.5f, FontStyle.Bold), new Point(54, 14), _theme.TextPrimary);
            TextRenderer.DrawText(g, "v1.0.1 • " + _theme.Name, new Font("Segoe UI", 8f), new Point(54, 34), _theme.TextMuted);

            // Status Hero Card
            var statusCard = new RectangleF(20, 62, 400, 92);
            using (var path = Gfx.RoundedRect(statusCard, 14))
            {
                using (var bg = new SolidBrush(_theme.Surface)) g.FillPath(bg, path);
                DrawCardPattern(g, statusCard);
                using (var pen = new Pen(_theme.Border, 1f)) g.DrawPath(pen, path);
            }

            bool pulseActive = (DateTime.UtcNow - _lastActivityUtc).TotalMilliseconds < 450;
            Color ledColor = _connected ? _theme.StatusConnected : _theme.Accent;
            if (pulseActive || _connected)
            {
                using (var glow = new SolidBrush(Color.FromArgb(55, ledColor)))
                {
                    g.FillEllipse(glow, 33, 76, 20, 20);
                }
            }
            using (var led = new SolidBrush(ledColor))
            {
                g.FillEllipse(led, 38, 81, 10, 10);
            }

            TextRenderer.DrawText(g, _statusText, new Font("Segoe UI", 11f, FontStyle.Bold), new Rectangle(58, 75, 345, 24), _theme.TextPrimary, TextFormatFlags.EndEllipsis);
            TextRenderer.DrawText(g, _activityText, new Font("Segoe UI", 8.8f), new Rectangle(38, 104, 366, 20), _theme.TextMuted, TextFormatFlags.EndEllipsis);
            TextRenderer.DrawText(g, string.Format("Host: {0}   •   Events: {1}", Environment.MachineName, _packetCount), new Font("Consolas", 8.5f), new Rectangle(38, 126, 366, 18), _theme.TextMuted, TextFormatFlags.EndEllipsis);

            // Network Card
            var netCard = new RectangleF(20, 166, 400, 114);
            using (var path = Gfx.RoundedRect(netCard, 14))
            {
                using (var bg = new SolidBrush(_theme.Surface)) g.FillPath(bg, path);
                using (var pen = new Pen(_theme.Border, 1f)) g.DrawPath(pen, path);
            }

            TextRenderer.DrawText(g, "LOCAL WI-FI ADDRESS", new Font("Segoe UI", 7.5f, FontStyle.Bold), new Point(36, 178), _theme.TextMuted);
            TextRenderer.DrawText(g, NetworkUtils.GetPrimaryIp(), new Font("Consolas", 14.5f, FontStyle.Bold), new Rectangle(36, 196, 270, 28), _theme.TextPrimary, TextFormatFlags.EndEllipsis);

            string allIps = NetworkUtils.GetLocalIpsText();
            string subNet = string.IsNullOrEmpty(_copiedNotice)
                ? string.Format("Private LAN: Discovery {0}  •  TLS {1}", DiscoveryPort, TcpPort)
                : _copiedNotice + "  (" + allIps + ")";
            TextRenderer.DrawText(g, subNet, new Font("Segoe UI", 8.5f), new Rectangle(36, 234, 368, 18), _theme.TextMuted, TextFormatFlags.EndEllipsis);
            TextRenderer.DrawText(g, "Pair once, then Link → select this PC", new Font("Segoe UI", 8.2f), new Rectangle(36, 254, 368, 18), _theme.Accent, TextFormatFlags.EndEllipsis);
        }

        private void DrawCardPattern(Graphics g, RectangleF rect)
        {
            g.SetClip(rect);
            if (_theme.IsCyber)
            {
                using (var pen = new Pen(_theme.GridDot, 1f))
                {
                    for (float x = rect.X + 20; x < rect.Right; x += 22) g.DrawLine(pen, x, rect.Y, x, rect.Bottom);
                    for (float y = rect.Y + 20; y < rect.Bottom; y += 22) g.DrawLine(pen, rect.X, y, rect.Right, y);
                }
            }
            else
            {
                using (var dot = new SolidBrush(_theme.GridDot))
                {
                    for (float x = rect.X + 16; x < rect.Right - 8; x += 16)
                    {
                        for (float y = rect.Y + 14; y < rect.Bottom - 8; y += 16)
                        {
                            g.FillEllipse(dot, x, y, 2f, 2f);
                        }
                    }
                }
            }
            g.ResetClip();
        }

        private void ConfigureFirewallRule()
        {
            _firewallBtn.Enabled = false;
            _activityText = "Waiting for firewall approval...";
            string receiverPath = Application.ExecutablePath;
            ThreadPool.QueueUserWorkItem(delegate
            {
                FirewallResult result = FirewallRules.Apply(receiverPath);
                if (IsDisposed) return;
                try
                {
                    BeginInvoke((MethodInvoker)delegate
                    {
                        FinishFirewallCheck(result, true);
                    });
                }
                catch (InvalidOperationException) { }
            });
        }

        private void FinishFirewallCheck(FirewallResult result, bool showFailure)
        {
            _firewallBtn.Enabled = true;
            if (result.Success && _identity != null)
            {
                if (!_lanStarted)
                {
                    try
                    {
                        _engine = new ServerEngine(DiscoveryPort, TcpPort, _identity, OnStatusChanged, OnInputActivity);
                        _engine.Start(); _lanStarted = true;
                        _statusText = "Waiting for paired phone";
                        _activityText = "Private LAN verified • Encrypted pairing required";
                    }
                    catch
                    {
                        if (_engine != null) _engine.Stop();
                        _statusText = "Wi-Fi disabled";
                        _activityText = "Could not open the secure receiver ports";
                    }
                }
            }
            else
            {
                if (_engine != null) _engine.Stop();
                _lanStarted = false;
                _statusText = "Wi-Fi disabled — firewall setup required";
                _activityText = "Click Allow Firewall to restrict access to Private LAN";
                if (showFailure) MessageBox.Show(result.Message, "DeskPad firewall", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            Invalidate();
        }

        private void ShowPairingDialog()
        {
            if (_identity == null || _engine == null) return;
            using (var dialog = new Form())
            {
                dialog.Text = "Pair a phone with DeskPad";
                dialog.StartPosition = FormStartPosition.CenterParent;
                dialog.FormBorderStyle = FormBorderStyle.FixedDialog;
                dialog.MaximizeBox = false; dialog.MinimizeBox = false;
                dialog.AutoScaleMode = AutoScaleMode.Dpi;
                dialog.AutoScaleDimensions = new SizeF(96f, 96f);
                dialog.ClientSize = new Size(780, 608);
                dialog.BackColor = _theme.Background; dialog.ForeColor = _theme.TextPrimary;
                var heading = new Label { Text = "Pair your phone", Location = new Point(24, 20), Size = new Size(732, 30), Font = new Font("Segoe UI", 13f, FontStyle.Bold) };
                var instruction = new Label { Text = "On your phone: tap Scan QR and point the camera here", Location = new Point(24, 60), Size = new Size(732, 40), Font = new Font("Segoe UI", 10f) };
                var picture = new PictureBox { Location = new Point(24, 112), Size = new Size(360, 360), BackColor = Color.White, SizeMode = PictureBoxSizeMode.CenterImage, AccessibleName = "Private DeskPad pairing QR code" };
                var warning = new Label { Text = "The QR and PIN grant control of this PC. Keep them private and reset access if they are shared.", Location = new Point(24, 490), Size = new Size(732, 42), Font = new Font("Segoe UI", 9f) };
                var pinHeading = new Label { Text = "Or use a temporary PIN", Location = new Point(424, 116), Size = new Size(332, 30), Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
                var pinDigits = new Label { Text = "------", Location = new Point(424, 156), Size = new Size(332, 54), Font = new Font("Consolas", 28f, FontStyle.Bold), AccessibleName = "Temporary pairing PIN closed" };
                var pinStatus = new Label { Location = new Point(424, 224), Size = new Size(332, 72), Font = new Font("Segoe UI", 10f) };
                var newPin = new Button { Text = "New PIN", Location = new Point(424, 312), Size = new Size(200, 36) };
                var pinHelp = new Label { Text = "On your phone, tap Enter PIN, choose this PC and enter the PIN. It expires after 2 minutes, 5 attempts, or one successful pairing.", Location = new Point(424, 372), Size = new Size(332, 88), Font = new Font("Segoe UI", 9f) };
                var pinTimer = new System.Windows.Forms.Timer { Interval = 250 };
                Action updatePinStatus = delegate
                {
                    if (!_engine.PinPairing.IsOpen)
                    {
                        pinDigits.Text = "------";
                        pinDigits.AccessibleName = "Temporary pairing PIN closed";
                        pinStatus.Text = "PIN closed. Choose New PIN to pair another phone.";
                    }
                    else
                    {
                        int seconds = Math.Max(0, (int)Math.Ceiling((_engine.PinPairing.ExpiresUtc - DateTime.UtcNow).TotalSeconds));
                        pinStatus.Text = string.Format("Expires in {0}:{1:00}\n{2} attempts remaining", seconds / 60, seconds % 60, _engine.PinPairing.RemainingAttempts);
                    }
                };
                Action openPin = delegate
                {
                    try { pinDigits.Text = _engine.PinPairing.Open(); pinDigits.AccessibleName = "Temporary pairing PIN: " + pinDigits.Text; updatePinStatus(); }
                    catch { pinDigits.Text = "------"; pinDigits.AccessibleName = "Temporary pairing PIN closed"; pinStatus.Text = "PIN unavailable. Use the QR or try again."; }
                };
                newPin.Click += delegate { openPin(); };
                pinTimer.Tick += delegate { updatePinStatus(); };
                var copy = new Button { Text = "Copy code (advanced)", Location = new Point(24, 550), Size = new Size(190, 36) };
                copy.Click += delegate
                {
                    try { Clipboard.SetText(_identity.PairingCode); copy.Text = "Copied"; }
                    catch { MessageBox.Show("The clipboard is unavailable. Scan the QR instead.", "DeskPad", MessageBoxButtons.OK, MessageBoxIcon.Information); }
                };
                Action refreshQr = delegate
                {
                    Image previous = picture.Image;
                    picture.Image = null;
                    if (previous != null) previous.Dispose();
                    try
                    {
                        int size = Math.Min(picture.ClientSize.Width, picture.ClientSize.Height);
                        picture.Image = PairingQrSupport.CreateForThisPc(_identity.PairingCode, size);
                        picture.AccessibleDescription = "Scan this QR in the DeskPad phone app. Keep it private.";
                    }
                    catch (NoPairingLanAddressException)
                    {
                        picture.AccessibleDescription = "No local IPv4 address. Connect to a trusted home network or use manual pairing.";
                        MessageBox.Show("No eligible local IPv4 address is available. Connect this PC to your trusted home Wi-Fi or Ethernet network, then reopen this window. You can also use Copy code (advanced) with manual pairing.", "DeskPad", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    }
                    catch
                    {
                        picture.AccessibleDescription = "QR unavailable. Use Copy code (advanced).";
                        MessageBox.Show("The pairing QR could not be generated. Use Copy code (advanced) and paste it into DeskPad's manual pairing field.", "DeskPad", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                };
                var reset = new Button { Text = "Reset access", Location = new Point(224, 550), Size = new Size(130, 36) };
                reset.Click += delegate
                {
                    if (MessageBox.Show("Disconnect all phones and invalidate every saved pairing code? You will need to pair your phones again.", "Reset DeskPad access", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                    try { _engine.ResetPairing(); copy.Text = "Copy code (advanced)"; refreshQr(); updatePinStatus(); }
                    catch { MessageBox.Show("Could not save new pairing credentials. Access was not reset.", "DeskPad", MessageBoxButtons.OK, MessageBoxIcon.Error); }
                };
                var close = new Button { Text = "Close", Location = new Point(624, 550), Size = new Size(132, 36), DialogResult = DialogResult.OK };
                dialog.Controls.AddRange(new Control[] { heading, instruction, picture, warning, pinHeading, pinDigits, pinStatus, newPin, pinHelp, copy, reset, close });
                dialog.Shown += delegate { refreshQr(); openPin(); pinTimer.Start(); };
                dialog.CancelButton = close;
                try { dialog.ShowDialog(this); }
                finally
                {
                    pinTimer.Stop(); pinTimer.Dispose(); _engine.PinPairing.Close(); pinDigits.Text = "------"; pinDigits.AccessibleName = "Temporary pairing PIN closed";
                    Image image = picture.Image; picture.Image = null;
                    if (image != null) image.Dispose();
                }
            }
        }

        private static bool IsStartupEnabled()
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", false))
                {
                    return key != null && key.GetValue("DeskPadPC") != null;
                }
            }
            catch { return false; }
        }

        private static void SetStartupEnabled(bool enabled)
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", true))
                {
                    if (key == null) return;
                    if (enabled) key.SetValue("DeskPadPC", "\"" + Application.ExecutablePath + "\"");
                    else key.DeleteValue("DeskPadPC", false);
                }
            }
            catch { }
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

        private void ShowAndActivate()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        private void OnStatusChanged(bool connected, string message)
        {
            if (IsDisposed) return;
            try
            {
                BeginInvoke((MethodInvoker)delegate
                {
                    if (!_lanStarted) return;
                    _connected = connected;
                    _statusText = message;
                    if (!connected) _activityText = "Encrypted Wi-Fi • Waiting for paired phone";
                    _trayIcon.Text = connected ? "DeskPad PC • " + message : "DeskPad PC • Waiting";
                    Invalidate();
                });
            }
            catch { }
        }

        private void OnInputActivity(string summary)
        {
            Interlocked.Increment(ref _packetCount);
            _lastActivityUtc = DateTime.UtcNow;
            if (IsDisposed) return;
            try
            {
                BeginInvoke((MethodInvoker)delegate
                {
                    _activityText = summary;
                });
            }
            catch { }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!_allowExit && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }
            _uiTimer.Stop();
            if (_engine != null) _engine.Stop();
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            if (_identity != null) _identity.Dispose();
            base.OnFormClosing(e);
        }
    }

    internal static class NetworkUtils
    {
        public static List<string> GetLocalIps()
        {
            var ips = new List<string>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    foreach (UnicastIPAddressInformation ip in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ip.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            string s = ip.Address.ToString();
                            if (!s.StartsWith("127.") && !s.StartsWith("169.254.") && !ips.Contains(s))
                            {
                                ips.Add(s);
                            }
                        }
                    }
                }
            }
            catch { }
            return ips;
        }

        public static string GetPrimaryIp()
        {
            var ips = GetLocalIps();
            return ips.Count > 0 ? ips[0] : "127.0.0.1";
        }

        public static string GetLocalIpsText()
        {
            var ips = GetLocalIps();
            return ips.Count > 0 ? string.Join(", ", ips.ToArray()) : "127.0.0.1";
        }
    }

    internal static class Win32Input
    {
        private const uint INPUT_MOUSE = 0;
        private const uint INPUT_KEYBOARD = 1;

        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;
        private const uint MOUSEEVENTF_HWHEEL = 0x1000;

        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;

        private static readonly Dictionary<string, ushort> VkMap = new Dictionary<string, ushort>
        {
            { "BACKSPACE", 0x08 }, { "TAB", 0x09 }, { "ENTER", 0x0D },
            { "SHIFT", 0x10 }, { "CTRL", 0x11 }, { "ALT", 0x12 },
            { "CAPS", 0x14 }, { "ESC", 0x1B }, { "SPACE", 0x20 },
            { "PGUP", 0x21 }, { "PGDN", 0x22 }, { "END", 0x23 }, { "HOME", 0x24 },
            { "LEFT", 0x25 }, { "UP", 0x26 }, { "RIGHT", 0x27 }, { "DOWN", 0x28 },
            { "DELETE", 0x2E }, { "WIN", 0x5B },
            { "F1", 0x70 }, { "F2", 0x71 }, { "F3", 0x72 }, { "F4", 0x73 },
            { "F5", 0x74 }, { "F6", 0x75 }, { "F7", 0x76 }, { "F8", 0x77 },
            { "F9", 0x78 }, { "F10", 0x79 }, { "F11", 0x7A }, { "F12", 0x7B },
            { "VOL_MUTE", 0xAD }, { "VOL_DOWN", 0xAE }, { "VOL_UP", 0xAF },
            { "MEDIA_NEXT", 0xB0 }, { "MEDIA_PREV", 0xB1 }, { "MEDIA_PLAY_PAUSE", 0xB3 }
        };

        private static readonly HashSet<ushort> ExtendedVks = new HashSet<ushort>
        {
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E, 0x5B
        };

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        private struct HARDWAREINPUT { public uint uMsg; public ushort wParamL, wParamH; }
        [StructLayout(LayoutKind.Explicit)]
        private struct INPUT_UNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public HARDWAREINPUT hi; }
        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT { public uint type; public INPUT_UNION u; }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);
        [DllImport("user32.dll")]
        private static extern IntPtr GetKeyboardLayout(uint idThread);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern short VkKeyScanEx(char ch, IntPtr dwhkl);

        private static bool Dispatch(params INPUT[] inputs)
        {
            if (inputs.Length == 0) return true;
            return SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT))) > 0;
        }

        public static void Move(int dx, int dy)
        {
            var inp = new INPUT { type = INPUT_MOUSE };
            inp.u.mi = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = MOUSEEVENTF_MOVE };
            Dispatch(inp);
        }

        public static void Scroll(int sx, int sy)
        {
            var list = new List<INPUT>();
            if (sy != 0)
            {
                var v = new INPUT { type = INPUT_MOUSE };
                v.u.mi = new MOUSEINPUT { mouseData = unchecked((uint)(sy * 24)), dwFlags = MOUSEEVENTF_WHEEL };
                list.Add(v);
            }
            if (sx != 0)
            {
                var h = new INPUT { type = INPUT_MOUSE };
                h.u.mi = new MOUSEINPUT { mouseData = unchecked((uint)(-sx * 24)), dwFlags = MOUSEEVENTF_HWHEEL };
                list.Add(h);
            }
            if (list.Count > 0) Dispatch(list.ToArray());
        }

        public static void Zoom(int delta)
        {
            if (delta == 0) return;
            KeyVk(VkMap["CTRL"], true);
            Scroll(0, delta * 3);
            KeyVk(VkMap["CTRL"], false);
        }

        public static void MouseButton(string button, bool down)
        {
            uint flag = 0;
            if (button == "L") flag = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            else if (button == "R") flag = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            else if (button == "M") flag = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            if (flag == 0) return;
            var inp = new INPUT { type = INPUT_MOUSE };
            inp.u.mi = new MOUSEINPUT { dwFlags = flag };
            Dispatch(inp);
        }

        public static void Click(string button, int count)
        {
            int n = Math.Max(1, count);
            for (int i = 0; i < n; i++) { MouseButton(button, true); MouseButton(button, false); }
        }

        private static INPUT MakeVkInput(ushort vk, bool down)
        {
            uint flags = down ? 0 : KEYEVENTF_KEYUP;
            if (ExtendedVks.Contains(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
            ushort scan = (ushort)MapVirtualKey(vk, 0);
            var inp = new INPUT { type = INPUT_KEYBOARD };
            inp.u.ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags };
            return inp;
        }

        private static bool KeyVk(ushort vk, bool down)
        {
            return Dispatch(MakeVkInput(vk, down));
        }

        public static bool SendText(string text)
        {
            var inputs = new List<INPUT>(text.Length * 4);
            IntPtr layout = GetKeyboardLayout(0);
            for (int i = 0; i < text.Length; i++)
            {
                char ch = text[i];
                if (ch == '\r') continue;
                if (ch == '\n')
                {
                    inputs.Add(MakeVkInput(0x0D, true));
                    inputs.Add(MakeVkInput(0x0D, false));
                    continue;
                }
                if (ch == '\t')
                {
                    inputs.Add(MakeVkInput(0x09, true));
                    inputs.Add(MakeVkInput(0x09, false));
                    continue;
                }

                short mapped = (ch >= 32 && ch <= 126) ? VkKeyScanEx(ch, layout) : (short)-1;
                if (mapped != -1 && ((mapped >> 8) & 0x06) == 0)
                {
                    ushort vk = (ushort)(mapped & 0xFF);
                    bool needShift = ((mapped >> 8) & 0x01) != 0;
                    if (needShift) inputs.Add(MakeVkInput(0x10, true));
                    inputs.Add(MakeVkInput(vk, true));
                    inputs.Add(MakeVkInput(vk, false));
                    if (needShift) inputs.Add(MakeVkInput(0x10, false));
                }
                else
                {
                    ushort codeUnit = ch;
                    var d = new INPUT { type = INPUT_KEYBOARD };
                    d.u.ki = new KEYBDINPUT { wScan = codeUnit, dwFlags = KEYEVENTF_UNICODE };
                    var u = new INPUT { type = INPUT_KEYBOARD };
                    u.u.ki = new KEYBDINPUT { wScan = codeUnit, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP };
                    inputs.Add(d);
                    inputs.Add(u);
                }
            }
            return inputs.Count == 0 || Dispatch(inputs.ToArray());
        }

        public static bool SendKey(string keyName, List<string> modifiers)
        {
            var modVks = new List<ushort>();
            bool ok = true;
            foreach (string mod in modifiers)
            {
                ushort mvk;
                if (VkMap.TryGetValue(mod, out mvk)) { modVks.Add(mvk); ok &= KeyVk(mvk, true); }
            }
            ushort? vk = ResolveVk(keyName);
            if (vk.HasValue) { ok &= KeyVk(vk.Value, true); ok &= KeyVk(vk.Value, false); }
            else if (keyName.Length == 1) ok &= SendText(keyName);
            for (int i = modVks.Count - 1; i >= 0; i--) KeyVk(modVks[i], false);
            return ok;
        }

        internal static bool IsSupportedKey(string keyName)
        {
            return keyName != null && (VkMap.ContainsKey(keyName) ||
                (keyName.Length == 1 && keyName[0] >= 32 && keyName[0] <= 126));
        }

        private static ushort? ResolveVk(string keyName)
        {
            ushort mapped;
            if (VkMap.TryGetValue(keyName, out mapped)) return mapped;
            if (keyName.Length == 1)
            {
                char ch = char.ToUpperInvariant(keyName[0]);
                if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) return (ushort)ch;
                switch (keyName[0])
                {
                    case ',': return 0xBC; case '.': return 0xBE; case '/': return 0xBF;
                    case ';': return 0xBA; case '\'': return 0xDE; case '[': return 0xDB;
                    case ']': return 0xDD; case '\\': return 0xDC; case '-': return 0xBD;
                    case '=': return 0xBB; case '`': return 0xC0;
                }
            }
            return null;
        }
    }
}
