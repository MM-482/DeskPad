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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified input dispatcher routing pointer and keyboard actions over Wi-Fi LAN or Bluetooth HID.
 */
class InputBridge(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val wifi = PcClientManager()
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

    fun connectWifi(ip: String, tcpPort: Int, udpPort: Int, name: String) {
        setMode(TransportMode.WIFI)
        wifi.connect(ip, tcpPort, udpPort, name)
    }

    fun connectBluetooth(device: BluetoothDevice?) {
        setMode(TransportMode.BLUETOOTH_HID)
        bluetooth.startHidProfile(device)
    }

    fun disconnect() {
        wifi.disconnect()
        bluetooth.disconnect()
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
