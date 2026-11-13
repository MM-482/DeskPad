package com.deskpad.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Optional direct Bluetooth HID (Mouse + Keyboard + Media Consumer Control) mode
 * requiring zero companion software on the PC.
 */
class BluetoothHidManager(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private var buttonMask: Int = 0
    private var appRegistered = false

    private val executor = Executors.newSingleThreadExecutor()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        if (!hasPermissions() || adapter == null || !adapter.isEnabled) {
            _pairedDevices.value = emptyList()
            return
        }
        _pairedDevices.value = adapter.bondedDevices?.toList().orEmpty()
    }

    @SuppressLint("MissingPermission")
    fun startHidProfile(targetDevice: BluetoothDevice? = null) {
        if (!hasPermissions()) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions required")
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Enable Bluetooth on your phone first")
            return
        }
        _connectionState.value = ConnectionState.Connecting(targetDevice?.name ?: "Bluetooth HID")

        val serviceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val hid = proxy as BluetoothHidDevice
                hidDevice = hid
                registerApp(hid, targetDevice)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    hostDevice = null
                    appRegistered = false
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        val ok = btAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            _connectionState.value = ConnectionState.Error("Bluetooth HID profile unavailable on this device")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp(hid: BluetoothHidDevice, pendingTarget: BluetoothDevice?) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "DeskPad Remote",
            "Trackpad & Keyboard HID",
            "DeskPad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            COMPOSITE_HID_DESCRIPTOR
        )

        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                appRegistered = registered
                if (registered) {
                    val target = pendingTarget ?: pluggedDevice
                    if (target != null) {
                        hid.connect(target)
                    } else {
                        _connectionState.value = ConnectionState.Error(
                            "HID ready — pair or select your PC from the list"
                        )
                    }
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        hostDevice = device
                        _connectionState.value = ConnectionState.Connected(
                            hostName = device?.name ?: "Bluetooth PC",
                            detail = device?.address ?: "HID",
                            latencyMs = null,
                            mode = TransportMode.BLUETOOTH_HID
                        )
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionState.value = ConnectionState.Connecting(device?.name ?: "PC")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (hostDevice?.address == device?.address) {
                            hostDevice = null
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                }
            }
        }

        if (appRegistered) {
            pendingTarget?.let { hid.connect(it) }
        } else {
            hid.registerApp(sdp, null, null, executor, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val hid = hidDevice
        val host = hostDevice
        if (hid != null && host != null && hasPermissions()) {
            runCatching { hid.disconnect(host) }
        }
        if (hid != null && appRegistered && hasPermissions()) {
            runCatching { hid.unregisterApp() }
        }
        hostDevice = null
        appRegistered = false
        _connectionState.value = ConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    fun sendMove(dx: Int, dy: Int) {
        val hid = hidDevice ?: return
        val host = hostDevice ?: return
        if (!hasPermissions()) return
        val x = ProtocolCodec.clampInt8(dx)
        val y = ProtocolCodec.clampInt8(dy)
        hid.sendReport(host, REPORT_ID_MOUSE, byteArrayOf(buttonMask.toByte(), x, y, 0))
    }

    @SuppressLint("MissingPermission")
    fun sendScroll(sy: Int) {
        val hid = hidDevice ?: return
        val host = hostDevice ?: return
        if (!hasPermissions() || sy == 0) return
        val wheel = ProtocolCodec.clampInt8(-sy)
        hid.sendReport(host, REPORT_ID_MOUSE, byteArrayOf(buttonMask.toByte(), 0, 0, wheel))
    }

    @SuppressLint("MissingPermission")
    fun sendMouseButton(button: MouseButton, down: Boolean) {
        val bit = when (button) {
            MouseButton.LEFT -> 0x01
            MouseButton.RIGHT -> 0x02
            MouseButton.MIDDLE -> 0x04
        }
        buttonMask = if (down) (buttonMask or bit) else (buttonMask and bit.inv())
        val hid = hidDevice ?: return
        val host = hostDevice ?: return
        if (!hasPermissions()) return
        hid.sendReport(host, REPORT_ID_MOUSE, byteArrayOf(buttonMask.toByte(), 0, 0, 0))
    }

    fun sendClick(button: MouseButton, count: Int = 1) {
        repeat(count) {
            sendMouseButton(button, true)
            sendMouseButton(button, false)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendText(text: String) {
        for (ch in text) {
            sendCharKey(ch, ModifierState())
        }
    }

    @SuppressLint("MissingPermission")
    fun sendCharKey(ch: Char, modifiers: ModifierState) {
        val mapping = charToHidUsage(ch) ?: return
        val modByte = modifierByte(modifiers, extraShift = mapping.second)
        sendKeyboardUsage(modByte, mapping.first.toByte())
    }

    @SuppressLint("MissingPermission")
    fun sendKey(key: SpecialKey, modifiers: ModifierState) {
        if (key == SpecialKey.WIN) {
            val withWin = modifiers.copy(win = true)
            sendKeyboardUsage(modifierByte(withWin, extraShift = false), 0)
            return
        }
        val consumerCode = specialKeyToConsumerUsage(key)
        if (consumerCode != null) {
            val hid = hidDevice ?: return
            val host = hostDevice ?: return
            if (!hasPermissions()) return
            hid.sendReport(host, REPORT_ID_CONSUMER, byteArrayOf(consumerCode.toByte(), 0))
            Thread.sleep(6)
            hid.sendReport(host, REPORT_ID_CONSUMER, byteArrayOf(0, 0))
            return
        }
        val usage = specialKeyToHidUsage(key) ?: return
        sendKeyboardUsage(modifierByte(modifiers, extraShift = false), usage.toByte())
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyboardUsage(modByte: Byte, usage: Byte) {
        val hid = hidDevice ?: return
        val host = hostDevice ?: return
        if (!hasPermissions()) return
        hid.sendReport(host, REPORT_ID_KEYBOARD, byteArrayOf(modByte, 0, usage, 0, 0, 0, 0, 0))
        Thread.sleep(6)
        hid.sendReport(host, REPORT_ID_KEYBOARD, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
    }

    private fun modifierByte(modifiers: ModifierState, extraShift: Boolean): Byte {
        var mask = 0
        if (modifiers.ctrl) mask = mask or 0x01
        if (modifiers.shift || extraShift) mask = mask or 0x02
        if (modifiers.alt) mask = mask or 0x04
        if (modifiers.win) mask = mask or 0x08
        return mask.toByte()
    }

    private fun specialKeyToConsumerUsage(key: SpecialKey): Int? = when (key) {
        SpecialKey.MEDIA_PLAY_PAUSE -> 0x01 // bit 0: Play/Pause
        SpecialKey.MEDIA_NEXT -> 0x02       // bit 1: Scan Next
        SpecialKey.MEDIA_PREV -> 0x04       // bit 2: Scan Prev
        SpecialKey.VOLUME_MUTE -> 0x08      // bit 3: Mute
        SpecialKey.VOLUME_UP -> 0x10        // bit 4: Vol +
        SpecialKey.VOLUME_DOWN -> 0x20      // bit 5: Vol -
        else -> null
    }

    private fun specialKeyToHidUsage(key: SpecialKey): Int? = when (key) {
        SpecialKey.ENTER -> 0x28
        SpecialKey.ESC -> 0x29
        SpecialKey.BACKSPACE -> 0x2A
        SpecialKey.TAB -> 0x2B
        SpecialKey.SPACE -> 0x2C
        SpecialKey.CAPS_LOCK -> 0x39
        SpecialKey.F1 -> 0x3A
        SpecialKey.F2 -> 0x3B
        SpecialKey.F3 -> 0x3C
        SpecialKey.F4 -> 0x3D
        SpecialKey.F5 -> 0x3E
        SpecialKey.F6 -> 0x3F
        SpecialKey.F7 -> 0x40
        SpecialKey.F8 -> 0x41
        SpecialKey.F9 -> 0x42
        SpecialKey.F10 -> 0x43
        SpecialKey.F11 -> 0x44
        SpecialKey.F12 -> 0x45
        SpecialKey.HOME -> 0x4A
        SpecialKey.PAGE_UP -> 0x4B
        SpecialKey.DELETE -> 0x4C
        SpecialKey.END -> 0x4D
        SpecialKey.PAGE_DOWN -> 0x4E
        SpecialKey.RIGHT -> 0x4F
        SpecialKey.LEFT -> 0x50
        SpecialKey.DOWN -> 0x51
        SpecialKey.UP -> 0x52
        else -> null
    }

    private fun charToHidUsage(ch: Char): Pair<Int, Boolean>? = when (ch) {
        in 'a'..'z' -> (0x04 + (ch - 'a')) to false
        in 'A'..'Z' -> (0x04 + (ch - 'A')) to true
        in '1'..'9' -> (0x1E + (ch - '1')) to false
        '0' -> 0x27 to false
        ' ' -> 0x2C to false
        '\n' -> 0x28 to false
        '\t' -> 0x2B to false
        '-' -> 0x2D to false
        '_' -> 0x2D to true
        '=' -> 0x2E to false
        '+' -> 0x2E to true
        '[' -> 0x2F to false
        '{' -> 0x2F to true
        ']' -> 0x30 to false
        '}' -> 0x30 to true
        '\\' -> 0x31 to false
        '|' -> 0x31 to true
        ';' -> 0x33 to false
        ':' -> 0x33 to true
        '\'' -> 0x34 to false
        '"' -> 0x34 to true
        '`' -> 0x35 to false
        '~' -> 0x35 to true
        ',' -> 0x36 to false
        '<' -> 0x36 to true
        '.' -> 0x37 to false
        '>' -> 0x37 to true
        '/' -> 0x38 to false
        '?' -> 0x38 to true
        '!' -> 0x1E to true
        '@' -> 0x1F to true
        '#' -> 0x20 to true
        '$' -> 0x21 to true
        '%' -> 0x22 to true
        '^' -> 0x23 to true
        '&' -> 0x24 to true
        '*' -> 0x25 to true
        '(' -> 0x26 to true
        ')' -> 0x27 to true
        else -> null
    }

    companion object {
        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_ID_KEYBOARD = 2
        private const val REPORT_ID_CONSUMER = 3

        private val COMPOSITE_HID_DESCRIPTOR = byteArrayOf(
            // Mouse (Report ID 1)
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
            0xC0.toByte(), 0xC0.toByte(),

            // Keyboard (Report ID 2)
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),

            // Consumer Control (Report ID 3)
            0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x06,
            0x09, 0xCD.toByte(), // Play/Pause
            0x09, 0xB5.toByte(), // Scan Next
            0x09, 0xB6.toByte(), // Scan Prev
            0x09, 0xE2.toByte(), // Mute
            0x09, 0xE9.toByte(), // Vol Up
            0x09, 0xEA.toByte(), // Vol Down
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x02, 0x81.toByte(), 0x03,
            0xC0.toByte()
        )
    }
}
