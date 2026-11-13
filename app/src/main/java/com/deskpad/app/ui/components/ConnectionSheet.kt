package com.deskpad.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.model.TransportMode
import com.deskpad.app.ui.theme.LocalDeskPadPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
    activeMode: TransportMode,
    connectionState: ConnectionState,
    discoveredHosts: List<DiscoveredHost>,
    pairedBtDevices: List<BluetoothDevice>,
    hasBluetoothPermissions: Boolean,
    savedIp: String,
    savedPort: Int,
    onDismiss: () -> Unit,
    onSelectMode: (TransportMode) -> Unit,
    onRefreshWifi: () -> Unit,
    onRefreshBluetooth: () -> Unit,
    onConnectWifi: (String, Int, Int, String) -> Unit,
    onConnectBluetooth: (BluetoothDevice?) -> Unit,
    onDisconnect: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ipInput by remember(savedIp) { mutableStateOf(savedIp) }
    var portInput by remember(savedPort) { mutableStateOf(savedPort.toString()) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onRefreshBluetooth()
        }
    }

    LaunchedEffect(activeMode) {
        if (activeMode == TransportMode.WIFI) {
            onRefreshWifi()
        } else if (hasBluetoothPermissions) {
            onRefreshBluetooth()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Connect to PC",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Choose Wi-Fi (fastest) or direct Bluetooth HID",
                        fontSize = 12.sp,
                        color = palette.textMuted
                    )
                }
                if (connectionState is ConnectionState.Connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("Disconnect", fontSize = 12.sp, color = palette.accent)
                    }
                }
            }

            // Transport Mode Segmented Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.padSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    val selected = activeMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.accent else palette.padSurface)
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) palette.onAccent else palette.textMuted
                        )
                    }
                }
            }

            if (connectionState is ConnectionState.Error) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceElevated)
                        .border(1.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = connectionState.message,
                        color = palette.textPrimary,
                        fontSize = 12.sp
                    )
                }
            }

            if (activeMode == TransportMode.WIFI) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nearby PCs (Auto-Discovered)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Scan",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRefreshWifi() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (discoveredHosts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.padSurface)
                            .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Run deskpad_server.py on your PC (same Wi-Fi) to appear automatically, or enter IP below.",
                            color = palette.textMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        discoveredHosts.forEach { host ->
                            HostRow(
                                title = host.name,
                                subtitle = "${host.ip} • ${host.os}",
                                onClick = {
                                    ipInput = host.ip
                                    portInput = host.tcpPort.toString()
                                    onConnectWifi(host.ip, host.tcpPort, host.udpPort, host.name)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "Manual IP Address",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("PC IP (e.g. 192.168.1.42)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        )
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Port", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        )
                    )
                }

                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: ProtocolCodec.DEFAULT_TCP_PORT
                        onConnectWifi(ipInput.trim(), port, ProtocolCodec.DEFAULT_UDP_PORT, ipInput.trim())
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent
                    )
                ) {
                    Text("Connect via Wi-Fi", fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Bluetooth HID Mode
                if (!hasBluetoothPermissions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Button(
                        onClick = {
                            btPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_ADVERTISE
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Text("Grant Bluetooth Permissions")
                    }
                } else {
                    Text(
                        text = "Pair your phone with your PC in system Bluetooth settings first, then tap your PC below to attach as a wireless mouse & keyboard.",
                        fontSize = 12.sp,
                        color = palette.textMuted
                    )

                    if (pairedBtDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.padSurface)
                                .border(1.dp, palette.border, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "No paired Bluetooth devices found.",
                                color = palette.textMuted,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pairedBtDevices.forEach { dev ->
                                @SuppressLint("MissingPermission")
                                val devName = dev.name ?: "Bluetooth Device"
                                HostRow(
                                    title = devName,
                                    subtitle = dev.address,
                                    onClick = {
                                        onConnectBluetooth(dev)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { onRefreshBluetooth() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Refresh Paired Devices", color = palette.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceElevated)
            .border(1.dp, palette.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(palette.statusConnected)
            )
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Text(subtitle, fontSize = 12.sp, color = palette.textMuted)
            }
        }
        Text("Connect →", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.accent)
    }
}
