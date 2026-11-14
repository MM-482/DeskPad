package com.deskpad.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.network.InputBridge
import com.deskpad.app.network.PairingEnvelope
import com.deskpad.app.ui.theme.LocalDeskPadPalette

/** PIN contents live only in this foreground composition, never saved state or preferences. */
@Composable
fun MainPinPairingButton(bridge: InputBridge, hosts: List<DiscoveredHost>, state: ConnectionState, modifier: Modifier = Modifier) {
    val palette = LocalDeskPadPalette.current
    var open by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DiscoveredHost?>(null) }
    var userSelected by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0L) }
    var submitted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val available = hosts.filter { it.tcpPort == 48912 && PairingEnvelope.isAllowedHost(it.ip) }
    val busy = submitted && state is ConnectionState.Connecting
    fun dismiss() {
        if (submitted && bridge.wifi.isGenerationCurrent(generation)) bridge.disconnect()
        pin = ""; open = false; submitted = false
    }
    LaunchedEffect(available, open) {
        if (open && !manual && !userSelected) selected = available.singleOrNull()
    }
    LaunchedEffect(state, open, submitted) {
        if (open && submitted && state is ConnectionState.Connected && bridge.wifi.isGenerationCurrent(generation)) {
            pin = ""; open = false; submitted = false
        }
    }
    OutlinedButton(
        onClick = {
            pin = ""; manual = false; address = ""; selected = null; userSelected = false
            submitted = false; message = null; generation = bridge.wifi.connectionGeneration()
            open = true; bridge.startDiscovery()
        },
        enabled = state !is ConnectionState.Connecting,
        modifier = modifier.fillMaxWidth().height(46.dp)
    ) { Text("Enter PIN", color = palette.textPrimary) }
    if (open) {
        AlertDialog(
            onDismissRequest = { dismiss() },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Pair with your PC") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("In DeskPad PC, open Pair a phone. Enter the six-digit PIN shown there. Keep the PC pairing window open.")
                    if (!manual) {
                        if (available.isEmpty()) Text("Looking for PCs on your network… You can refresh or enter an address below.", fontSize = 12.sp)
                        available.forEach { host ->
                            Text(
                                (if (selected == host) "● " else "○ ") + host.name + " (" + host.ip + ")",
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    selected = host; userSelected = true
                                }.padding(vertical = 10.dp)
                            )
                        }
                        TextButton(enabled = !busy, onClick = { bridge.startDiscovery() }) { Text("Refresh PCs") }
                    } else {
                        OutlinedTextField(
                            value = address, onValueChange = { if (it.length <= 15) address = it },
                            label = { Text("PC's local IPv4 address") }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(enabled = !busy, onClick = { manual = !manual; selected = null; userSelected = false }) {
                        Text(if (manual) "Choose a discovered PC" else "Enter PC address (advanced)")
                    }
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value -> if (value.length <= 6 && value.all { it in '0'..'9' }) pin = value },
                        label = { Text("6-digit PIN") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Pairing replaces this PC's saved pairing only after the PC proves it knows the PIN. Each Pair tap makes one attempt.", fontSize = 11.sp)
                    if (busy) Text("Verifying PC…", color = palette.accent)
                    if (submitted && state is ConnectionState.Error) Text(state.message, fontSize = 12.sp)
                    message?.let { Text(it, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(enabled = !busy && pin.length == 6 && (if (manual) PairingEnvelope.isAllowedHost(address) else selected != null), onClick = {
                    val host = if (manual) address else selected!!.ip
                    val name = if (manual) address else selected!!.name
                    val secret = pin.toCharArray()
                    pin = ""
                    if (bridge.connectWifiPin(host, name, secret, generation)) {
                        generation = bridge.wifi.connectionGeneration(); submitted = true; message = null
                    } else {
                        message = "The connection changed. Close this dialog and try again."
                    }
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { dismiss() }) { Text("Cancel") } }
        )
    }
}
