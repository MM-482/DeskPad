package com.deskpad.app.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.DiscoveredHost
import com.deskpad.app.model.ProtocolCodec
import com.deskpad.app.model.TransportMode
import com.deskpad.app.network.PairingCode
import com.deskpad.app.network.PairingQrImport
import com.deskpad.app.network.PairingQrResult
import com.deskpad.app.ui.PrivatePairingCaptureActivity
import com.deskpad.app.ui.theme.LocalDeskPadPalette
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

private data class PairingScanTarget(val ip: String, val port: Int, val name: String, val replace: Boolean)

private fun pairingScanOptions() = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setCaptureActivity(PrivatePairingCaptureActivity::class.java)
    .setOrientationLocked(false)
    .setBeepEnabled(false)
    .setBarcodeImageEnabled(false)
    .setPrompt("Scan the private pairing QR code shown in DeskPad PC")

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
    onHasWifiPairing: suspend (String, Int) -> Boolean,
    onForgetWifiPairing: suspend (String, Int) -> Unit,
    onConnectWifi: (String, Int, String, String?, Boolean) -> Unit,
    onConnectBluetooth: (BluetoothDevice?) -> Unit,
    onDisconnect: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ipInput by remember(savedIp) { mutableStateOf(savedIp) }
    var portInput by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var selectedName by remember { mutableStateOf(savedIp) }
    var pairingInput by remember { mutableStateOf("") }
    var savedPairing by remember { mutableStateOf<Boolean?>(null) }
    var replacingPairing by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var connectingHere by remember { mutableStateOf(false) }
    var manualEntryExpanded by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf<PairingScanTarget?>(null) }
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val uiScope = rememberCoroutineScope()

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val target = scanTarget
        scanTarget = null
        pairingInput = ""
        when (val scanned = PairingQrImport.read(result.contents)) {
            PairingQrResult.Cancelled -> pairingError = "Scan cancelled. You can scan the PC's QR code again."
            PairingQrResult.Invalid -> pairingError = "That is not a valid DeskPad pairing QR code. Scan the one shown in DeskPad PC."
            is PairingQrResult.Ready -> {
                if (target == null || activeMode != TransportMode.WIFI ||
                    target.ip != ipInput.trim() || target.port != portInput.toIntOrNull()) {
                    pairingError = "Choose the PC again before scanning its QR code."
                } else {
                    pairingError = null
                    manualEntryExpanded = false
                    connectingHere = true
                    onConnectWifi(target.ip, target.port, target.name, scanned.pairingCode(), target.replace)
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && scanTarget != null) {
            qrLauncher.launch(pairingScanOptions())
        } else {
            scanTarget = null
            pairingInput = ""
            pairingError = "Camera access is only needed to scan. You can try again or enter the code manually."
        }
    }

    LaunchedEffect(ipInput, portInput) {
        savedPairing = null
        replacingPairing = false
        pairingInput = ""
        pairingError = null
        manualEntryExpanded = false
        scanTarget = null
        val port = portInput.toIntOrNull()
        savedPairing = if (ipInput.isNotBlank() && port != null && port in 1..65535) {
            onHasWifiPairing(ipInput.trim(), port)
        } else false
    }

    LaunchedEffect(connectionState) {
        if (connectingHere && connectionState is ConnectionState.Connected && connectionState.mode == TransportMode.WIFI) {
            pairingInput = ""
            onDismiss()
        }
    }

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
                .verticalScroll(rememberScrollState())
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
                        text = "Choose paired, encrypted Wi-Fi or direct Bluetooth HID",
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
                            text = "Open DeskPad PC on the same network. Select a PC or enter its address, then scan its pairing QR code.",
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
                                    selectedName = host.name
                                },
                                actionLabel = "Select →"
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
                        onValueChange = { ipInput = it; selectedName = it },
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

                if (savedPairing == true && !replacingPairing) {
                    Text("Saved PC identity", fontSize = 12.sp, color = palette.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            replacingPairing = true; pairingError = null; manualEntryExpanded = false; pairingInput = ""
                        }) {
                            Text("Pair this PC again", fontSize = 11.sp, color = palette.textPrimary)
                        }
                        OutlinedButton(onClick = {
                            val port = portInput.toIntOrNull() ?: return@OutlinedButton
                            uiScope.launch {
                                runCatching { onForgetWifiPairing(ipInput.trim(), port) }
                                    .onSuccess { savedPairing = false; pairingInput = ""; pairingError = null }
                                    .onFailure { pairingError = "Unable to forget this PC. Try again." }
                            }
                        }) {
                            Text("Forget PC", fontSize = 11.sp, color = palette.textPrimary)
                        }
                    }
                }
                if (savedPairing == false || replacingPairing) {
                    Text(
                        "For one-step pairing, use Scan QR on the main screen. Legacy codes below apply only to the selected PC. Keep them private.",
                        fontSize = 12.sp, color = palette.textMuted
                    )
                    Button(
                        onClick = {
                            val port = portInput.toIntOrNull()
                            if (ipInput.isBlank() || port == null || port !in 1..65535) {
                                pairingError = "Select a PC or enter its address first."
                            } else {
                                pairingInput = ""
                                pairingError = null
                                manualEntryExpanded = false
                                scanTarget = PairingScanTarget(
                                    ipInput.trim(), port, selectedName.ifBlank { ipInput.trim() }, replacingPairing
                                )
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    qrLauncher.launch(pairingScanOptions())
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        enabled = hasCamera && connectionState !is ConnectionState.Connecting && ipInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent)
                    ) {
                        Text("Scan legacy pairing code", fontWeight = FontWeight.SemiBold)
                    }
                    if (!hasCamera) {
                        Text("No camera on this device. Enter the code manually instead.", fontSize = 12.sp, color = palette.textMuted)
                    }
                    OutlinedButton(
                        onClick = { manualEntryExpanded = !manualEntryExpanded; pairingInput = ""; pairingError = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (manualEntryExpanded) "Hide manual entry" else "Enter code manually", color = palette.textPrimary, fontSize = 12.sp)
                    }
                    if (manualEntryExpanded) OutlinedTextField(
                        value = pairingInput,
                        onValueChange = { pairingInput = it.take(200); pairingError = null },
                        label = { Text("PC pairing code (DP2:…)", fontSize = 12.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        )
                    )
                }
                pairingError?.let { Text(it, fontSize = 12.sp, color = palette.textPrimary) }

                if ((savedPairing == true && !replacingPairing) || manualEntryExpanded) Button(
                    onClick = {
                        val port = portInput.toIntOrNull()
                        if (port == null || port !in 1..65535 || ipInput.isBlank()) {
                            pairingError = "Enter the PC address and a port between 1 and 65535"
                        } else {
                            val code = if (savedPairing == true && !replacingPairing) null else pairingInput.trim()
                            if (code != null && runCatching { PairingCode.parse(code) }.isFailure) {
                                pairingError = "Paste the complete DP2 pairing code from your PC"
                            } else {
                                pairingError = null
                                connectingHere = true
                                onConnectWifi(ipInput.trim(), port, selectedName.ifBlank { ipInput.trim() }, code, replacingPairing)
                            }
                        }
                    },
                    enabled = savedPairing != null && connectionState !is ConnectionState.Connecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent
                    )
                ) {
                    Text(
                        if (connectionState is ConnectionState.Connecting) "Verifying PC…"
                        else if (savedPairing == true && !replacingPairing) "Connect to saved PC" else "Connect using code",
                        fontWeight = FontWeight.SemiBold
                    )
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
    onClick: () -> Unit,
    actionLabel: String = "Connect →"
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
        Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.accent)
    }
}
