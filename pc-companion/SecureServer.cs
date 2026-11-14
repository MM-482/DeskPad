using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
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
    internal interface ILanInput
    {
        void Move(int dx, int dy);
        void Scroll(int sx, int sy);
        void Zoom(int delta);
        void Click(string button, int count);
        void MouseButton(string button, bool down);
        bool SendText(string text);
        bool SendKey(string key, List<string> modifiers);
    }

    internal sealed class WindowsLanInput : ILanInput
    {
        public void Move(int dx, int dy) { Win32Input.Move(dx, dy); }
        public void Scroll(int sx, int sy) { Win32Input.Scroll(sx, sy); }
        public void Zoom(int delta) { Win32Input.Zoom(delta); }
        public void Click(string button, int count) { Win32Input.Click(button, count); }
        public void MouseButton(string button, bool down) { Win32Input.MouseButton(button, down); }
        public bool SendText(string text) { return Win32Input.SendText(text); }
        public bool SendKey(string key, List<string> modifiers) { return Win32Input.SendKey(key, modifiers); }
    }

    internal sealed class BoundedLineReader
    {
        private readonly Stream _stream;
        private readonly byte[] _buffer = new byte[4096];
        private int _offset, _count;
        public const int MaximumBytes = 16384;
        public BoundedLineReader(Stream stream) { _stream = stream; }
        public string ReadLine(int timeoutMs = 10000)
        {
            var deadline = Stopwatch.StartNew();
            using (var line = new MemoryStream())
            {
                while (true)
                {
                    int remaining = timeoutMs - (int)deadline.ElapsedMilliseconds;
                    if (remaining <= 0) throw new IOException("Frame deadline exceeded.");
                    if (_offset == _count)
                    {
                        if (_stream.CanTimeout) _stream.ReadTimeout = remaining;
                        _count = _stream.Read(_buffer, 0, _buffer.Length); _offset = 0;
                        if (_count == 0)
                        { if (line.Length == 0) return null; throw new InvalidDataException("Incomplete frame."); }
                    }
                    byte value = _buffer[_offset++];
                    if (value == 10) return new UTF8Encoding(false, true).GetString(line.ToArray());
                    if (value == 13 || line.Length >= MaximumBytes) throw new InvalidDataException("Invalid or oversized frame.");
                    line.WriteByte(value);
                }
            }
        }
    }

    internal sealed class ServerEngine
    {
        private readonly int _discoveryPort, _tcpPort;
        private readonly IPAddress _bindAddress;
        private readonly LanIdentity _identity;
        private readonly Action<bool, string> _onStatus;
        private readonly Action<string> _onActivity;
        private readonly ILanInput _input;
        private readonly object _controllerGate = new object();
        private readonly HashSet<TcpClient> _clients = new HashSet<TcpClient>();
        private readonly Semaphore _slots = new Semaphore(4, 4);
        private readonly ManualResetEvent _clientsDrained = new ManualResetEvent(true);
        private readonly HashSet<string> _heldButtons = new HashSet<string>();
        private TcpClient _controller;
        private volatile bool _running;
        private UdpClient _discovery;
        private TcpListener _listener;
        internal PinPairingController PinPairing { get; private set; }
        internal int BoundTcpPort { get { return ((IPEndPoint)_listener.LocalEndpoint).Port; } }
        internal int BoundDiscoveryPort { get { return ((IPEndPoint)_discovery.Client.LocalEndPoint).Port; } }

        public ServerEngine(int discoveryPort, int tcpPort, LanIdentity identity,
            Action<bool, string> onStatus, Action<string> onActivity, ILanInput input = null, IPAddress bindAddress = null)
        {
            _discoveryPort = discoveryPort; _tcpPort = tcpPort; _identity = identity;
            _onStatus = onStatus; _onActivity = onActivity; _input = input ?? new WindowsLanInput(); _bindAddress = bindAddress ?? IPAddress.Any;
            using (var hash = SHA256.Create())
                PinPairing = new PinPairingController(delegate { return _identity.PairingCode; }, hash.ComputeHash(_identity.Certificate.RawData));
        }

        public void Start()
        {
            try
            {
                _discovery = new UdpClient(new IPEndPoint(_bindAddress, _discoveryPort));
                _listener = new TcpListener(_bindAddress, _tcpPort); _listener.Start(8);
                _running = true;
                StartThread(DiscoveryLoop, "DeskPadDiscoveryV2");
                StartThread(AcceptLoop, "DeskPadTlsAccept");
            }
            catch { Stop(); throw; }
        }

        public void Stop()
        {
            _running = false;
            PinPairing.Close();
            try { if (_discovery != null) _discovery.Close(); } catch { }
            try { if (_listener != null) _listener.Stop(); } catch { }
            lock (_controllerGate)
            {
                foreach (var client in _clients) { try { client.Close(); } catch { } }
                ReleaseController();
            }
            // Drain closed client workers before the UI releases certificate key handles.
            _clientsDrained.WaitOne(6000);
        }

        public void ResetPairing()
        {
            lock (_controllerGate)
            {
                PinPairing.Close();
                _identity.RotateToken();
                foreach (var client in _clients) { try { client.Close(); } catch { } }
                ReleaseController();
            }
        }

        private static void StartThread(ThreadStart action, string name)
        { new Thread(action) { IsBackground = true, Name = name }.Start(); }

        private void DiscoveryLoop()
        {
            try
            {
                string name = Environment.MachineName.Replace('|', '_').Replace('\r', '_').Replace('\n', '_');
                if (name.Length > 128) name = name.Substring(0, 128);
                byte[] offer = Encoding.UTF8.GetBytes("DESKPAD_OFFER_V2|" + name + "|Windows|" + BoundTcpPort);
                while (_running)
                {
                    var remote = new IPEndPoint(IPAddress.Any, 0);
                    byte[] query = _discovery.Receive(ref remote);
                    if (query.Length == 19 && Encoding.ASCII.GetString(query) == "DESKPAD_DISCOVER_V2")
                        _discovery.Send(offer, offer.Length, remote);
                }
            }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
        }

        private void AcceptLoop()
        {
            try
            {
                while (_running)
                {
                    TcpClient client = _listener.AcceptTcpClient();
                    if (!_slots.WaitOne(0)) { client.Close(); continue; }
                    lock (_controllerGate)
                    {
                        if (!_running) { client.Close(); _slots.Release(); return; }
                        _clientsDrained.Reset();
                        _clients.Add(client);
                    }
                    ThreadPool.QueueUserWorkItem(HandleClient, client);
                }
            }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
        }

        private void HandleClient(object state)
        {
            var client = (TcpClient)state;
            try
            {
                client.NoDelay = true; client.ReceiveTimeout = 5000; client.SendTimeout = 5000;
                using (client)
                using (var deadline = new Timer(delegate { try { client.Close(); } catch { } }, null, 5000, Timeout.Infinite))
                using (var tls = new SslStream(client.GetStream(), false))
                {
                    tls.ReadTimeout = 5000; tls.WriteTimeout = 5000;
                    tls.AuthenticateAsServer(_identity.Certificate, false, SslProtocols.Tls12, false);
                    var reader = new BoundedLineReader(tls);
                    var json = new JavaScriptSerializer { MaxJsonLength = BoundedLineReader.MaximumBytes, RecursionLimit = 8 };
                    string firstLine = reader.ReadLine(5000);
                    var first = Parse(firstLine, json);
                    if (StringField(first, "t") == "pin_begin")
                    {
                        var begin = PinWire.Parse(firstLine);
                        if (begin.Count != 2 || StringField(begin, "t") != "pin_begin" || IntField(begin, "v", 2, 2) != 2)
                            throw new InvalidDataException("Invalid PIN enrollment frame.");
                        // Enrollment is a separate connection and never becomes an input controller.
                        // Keep the initial TLS/first-frame deadline at five seconds, then bound all
                        // PAKE frames together by a fresh fifteen-second socket-close deadline.
                        deadline.Change(15000, Timeout.Infinite);
                        HandlePinEnrollment(tls, reader, json);
                        return;
                    }
                    lock (_controllerGate)
                    {
                        string token = StringField(first, "token");
                        if (StringField(first, "t") != "auth" || IntField(first, "v", 2, 2) != 2 || !_identity.Authenticate(token))
                            throw new InvalidDataException("Pairing required.");
                        if (!_running || _controller != null) throw new InvalidDataException("Another phone is already connected.");
                        _controller = client;
                        WriteLine(tls, json.Serialize(new Dictionary<string, object>
                            { { "t", "auth_ok" }, { "v", 2 }, { "name", Environment.MachineName } }));
                        deadline.Change(Timeout.Infinite, Timeout.Infinite);
                        tls.ReadTimeout = 10000;
                        _onStatus(true, "Paired • Encrypted connection");
                        _onActivity("Authenticated phone linked");
                    }
                    string line;
                    while (_running && (line = reader.ReadLine()) != null)
                    {
                        var message = Parse(line, json);
                        lock (_controllerGate)
                        {
                            if (!ReferenceEquals(_controller, client)) break;
                            ProcessCommand(message, tls, json);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                // Malformed, unauthenticated and timed-out peers never affect a valid controller.
                if (!(e is IOException || e is SocketException || e is AuthenticationException || e is InvalidDataException ||
                    e is ObjectDisposedException || e is ArgumentException || e is InvalidOperationException))
                    System.Diagnostics.Debug.WriteLine("DeskPad connection rejected: " + e.GetType().Name);
            }
            finally
            {
                lock (_controllerGate)
                {
                    if (ReferenceEquals(_controller, client)) ReleaseController();
                    _clients.Remove(client);
                    if (_clients.Count == 0) _clientsDrained.Set();
                }
                try { client.Close(); } catch { }
                _slots.Release();
            }
        }

        private void HandlePinEnrollment(SslStream tls, BoundedLineReader reader, JavaScriptSerializer json)
        {
            try
            {
                using (var attempt = PinPairing.Begin())
                {
                    WriteLine(tls, json.Serialize(attempt.Challenge));
                    var ready = attempt.VerifyClientProof(PinWire.Parse(reader.ReadLine(15000)));
                    WriteLine(tls, json.Serialize(ready));
                    string pairingCode = attempt.ConfirmAndGetCode(PinWire.Parse(reader.ReadLine(15000)));
                    WriteLine(tls, json.Serialize(new Dictionary<string, object>
                        { { "t", "pin_paired" }, { "v", 2 }, { "id", attempt.Challenge["id"] }, { "code", pairingCode } }));
                }
            }
            catch
            {
                // One public failure shape; never include PINs, credentials or crypto details.
                try { WriteLine(tls, "{\"t\":\"pin_error\",\"v\":2}"); } catch { }
                throw;
            }
        }

        private void ReleaseController()
        {
            if (_controller == null) return;
            foreach (string button in _heldButtons)
            {
                try { _input.MouseButton(button, false); }
                catch (Exception) { /* Teardown must still revoke the session. */ }
            }
            _heldButtons.Clear(); _controller = null;
            _onStatus(false, "Waiting for paired phone");
        }

        private static void WriteLine(Stream stream, string line)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(line + "\n"); stream.Write(bytes, 0, bytes.Length); stream.Flush();
        }

        private static Dictionary<string, object> Parse(string line, JavaScriptSerializer json)
        {
            if (line == null || line.Length == 0) throw new InvalidDataException("Missing frame.");
            Dictionary<string, object> message;
            try { message = json.Deserialize<Dictionary<string, object>>(line); }
            catch (Exception e) { throw new InvalidDataException("Invalid JSON frame.", e); }
            if (message == null) throw new InvalidDataException("Expected object.");
            return message;
        }

        private static string StringField(Dictionary<string, object> message, string key)
        {
            object value;
            if (!message.TryGetValue(key, out value) || !(value is string)) throw new InvalidDataException("Expected string.");
            return (string)value;
        }

        private static int IntField(Dictionary<string, object> message, string key, int minimum, int maximum)
        {
            object value;
            if (!message.TryGetValue(key, out value) || !(value is int)) throw new InvalidDataException("Expected integer.");
            int number = (int)value;
            if (number < minimum || number > maximum) throw new InvalidDataException("Integer out of range.");
            return number;
        }

        private static string Button(Dictionary<string, object> message)
        {
            string button = StringField(message, "b");
            if (button != "L" && button != "R" && button != "M") throw new InvalidDataException("Unknown button.");
            return button;
        }

        private void ProcessCommand(Dictionary<string, object> message, Stream stream, JavaScriptSerializer json)
        {
            string type = StringField(message, "t");
            if (type == "ping")
            {
                object timestamp;
                if (!message.TryGetValue("ts", out timestamp) || !(timestamp is int || timestamp is long) || Convert.ToInt64(timestamp) < 0)
                    throw new InvalidDataException("Invalid ping.");
                WriteLine(stream, json.Serialize(new Dictionary<string, object> { { "t", "pong" }, { "ts", timestamp } }));
            }
            else if (type == "move")
            { _input.Move(IntField(message, "dx", -32767, 32767), IntField(message, "dy", -32767, 32767)); _onActivity("Pointer input"); }
            else if (type == "scroll")
            { _input.Scroll(IntField(message, "sx", -32767, 32767), IntField(message, "sy", -32767, 32767)); _onActivity("Scroll input"); }
            else if (type == "zoom")
            { _input.Zoom(IntField(message, "delta", -32767, 32767)); _onActivity("Zoom input"); }
            else if (type == "click")
            { _input.Click(Button(message), IntField(message, "c", 1, 2)); _onActivity("Mouse click"); }
            else if (type == "btn")
            {
                string button = Button(message); object down;
                if (!message.TryGetValue("d", out down) || !(down is bool)) throw new InvalidDataException("Expected button state.");
                _input.MouseButton(button, (bool)down);
                if ((bool)down) _heldButtons.Add(button); else _heldButtons.Remove(button);
            }
            else if (type == "text")
            {
                string text = StringField(message, "v");
                if (text.Length > 2048) throw new InvalidDataException("Text too long.");
                _input.SendText(text); _onActivity("Keyboard input");
            }
            else if (type == "key")
            {
                string key = StringField(message, "k"); object raw;
                if (!Win32Input.IsSupportedKey(key) || !message.TryGetValue("m", out raw) || !(raw is object[] || raw is ArrayList))
                    throw new InvalidDataException("Invalid key.");
                var modifiers = new List<string>();
                foreach (object value in (IEnumerable)raw)
                {
                    string modifier = value as string;
                    if (modifier != "CTRL" && modifier != "ALT" && modifier != "SHIFT" && modifier != "WIN")
                        throw new InvalidDataException("Invalid modifier.");
                    if (modifiers.Contains(modifier) || modifiers.Count >= 4) throw new InvalidDataException("Invalid modifier list.");
                    modifiers.Add(modifier);
                }
                _input.SendKey(key, modifiers); _onActivity("Keyboard shortcut");
            }
            else throw new InvalidDataException("Unsupported input command.");
        }
    }
}

