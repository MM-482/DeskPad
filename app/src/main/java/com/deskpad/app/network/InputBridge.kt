package com.deskpad.app.network

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.model.ModifierState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.model.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified input dispatcher routing pointer and keyboard actions over Wi-Fi LAN or Bluetooth HID.
 */
class InputBridge(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val wifi = PcClientManager(context.applicationContext)
    val bluetooth = BluetoothHidManager(context)

    private val _activeMode = MutableStateFlow(TransportMode.WIFI)
    val activeMode: StateFlow<TransportMode> = _activeMode.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val discoveredHosts: StateFlow<List<DiscoveredHost>> = wifi.discoveredHosts
    val pairedBtDevices: StateFlow<List<BluetoothDevice>> = bluetooth.pairedDevices

    init {
        scope.launch {
            wifi.connectionState.collect { state ->
                if (_activeMode.value == TransportMode.WIFI) {
                    _connectionState.value = state
                }
            }
        }
        scope.launch {
            bluetooth.connectionState.collect { state ->
                if (_activeMode.value == TransportMode.BLUETOOTH_HID) {
                    _connectionState.value = state
                }
            }
        }
    }

    fun setMode(mode: TransportMode) {
        if (_activeMode.value == mode) return
        disconnect()
        _activeMode.value = mode
        _connectionState.value = when (mode) {
            TransportMode.WIFI -> wifi.connectionState.value
            TransportMode.BLUETOOTH_HID -> bluetooth.connectionState.value
        }
    }

    fun startDiscovery() {
        wifi.startDiscovery()
    }

    fun connectWifi(ip: String, tcpPort: Int, name: String, pairingCode: String?, replacePairing: Boolean) {
        setMode(TransportMode.WIFI)
        wifi.connect(ip, tcpPort, name, pairingCode, replacePairing)
    }

    fun connectWifiQr(envelope: PairingEnvelope, scanGeneration: Long): Boolean {
        if (!wifi.isGenerationCurrent(scanGeneration)) return false
        setMode(TransportMode.WIFI)
        wifi.connectPairedHosts(envelope)
        return true
    }

    fun connectWifiPin(ip: String, name: String, pin: CharArray, generation: Long): Boolean {
        // Preserve the dialog's generation all the way into the manager's locked start.
        // connectPin itself closes the old Wi-Fi session; do not increment its generation via setMode.
        if (!wifi.connectPin(ip, name, pin, generation)) return false
        bluetooth.disconnect()
        _activeMode.value = TransportMode.WIFI
        _connectionState.value = wifi.connectionState.value
        return true
    }

    fun connectBluetooth(device: BluetoothDevice?) {
        setMode(TransportMode.BLUETOOTH_HID)
        bluetooth.startHidProfile(device)
    }

    fun disconnect() {
        wifi.disconnect()
        bluetooth.disconnect()
    }

    fun close() {
        wifi.close()
        bluetooth.close()
        scope.cancel()
    }

    fun moveCursor(dx: Int, dy: Int) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendMove(dx, dy)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendMove(dx, dy)
        }
    }

    fun scroll(sx: Int, sy: Int) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendScroll(sx, sy)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendScroll(sy)
        }
    }

    fun zoom(delta: Int) {
        if (_activeMode.value == TransportMode.WIFI) {
            wifi.sendZoom(delta)
        }
    }

    fun click(button: MouseButton, count: Int = 1) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendClick(button, count)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendClick(button, count)
        }
    }

    fun mouseButton(button: MouseButton, down: Boolean) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendMouseButton(button, down)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendMouseButton(button, down)
        }
    }

    fun sendText(text: String) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendText(text)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendText(text)
        }
    }

    fun sendKey(key: SpecialKey, modifiers: ModifierState = ModifierState()) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendKey(key, modifiers)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendKey(key, modifiers)
        }
    }

    fun sendCharKey(ch: Char, modifiers: ModifierState = ModifierState()) {
        when (_activeMode.value) {
            TransportMode.WIFI -> wifi.sendCharKey(ch, modifiers)
            TransportMode.BLUETOOTH_HID -> bluetooth.sendCharKey(ch, modifiers)
        }
    }
}
