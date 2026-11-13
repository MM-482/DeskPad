package com.deskpad.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpad.app.model.AppSettings
import com.deskpad.app.model.ConnectionState
import com.deskpad.app.model.MouseButton
import com.deskpad.app.model.SpecialKey
import com.deskpad.app.network.InputBridge
import com.deskpad.app.ui.components.ConnectionSheet
import com.deskpad.app.ui.components.KeyboardDeck
import com.deskpad.app.ui.components.SettingsSheet
import com.deskpad.app.ui.components.TrackpadSurface
import com.deskpad.app.ui.theme.LocalDeskPadPalette

@Composable
fun DeskPadScreen(
    bridge: InputBridge,
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val view = LocalView.current

    val connectionState by bridge.connectionState.collectAsState()
    val activeMode by bridge.activeMode.collectAsState()
    val discoveredHosts by bridge.discoveredHosts.collectAsState()
    val pairedBtDevices by bridge.pairedBtDevices.collectAsState()

    var showConnectionSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }

    fun tapHaptic() {
        if (settings.hapticFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header Pill Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConnectionStatusPill(
                state = connectionState,
                modifier = Modifier.weight(1f),
                onClick = { showConnectionSheet = true }
            )

            // Theme Pill Shortcut
            Box(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.border, RoundedCornerShape(999.dp))
                    .clickable { showSettingsSheet = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    )
                    Text(
                        text = "⚙",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Streamlined Quick Media + Keyboard Toggle Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionButton("⏮", Modifier.weight(1f)) {
                tapHaptic()
                bridge.sendKey(SpecialKey.MEDIA_PREV)
            }
            QuickActionButton("⏯", Modifier.weight(1f)) {
                tapHaptic()
                bridge.sendKey(SpecialKey.MEDIA_PLAY_PAUSE)
            }
            QuickActionButton("⏭", Modifier.weight(1f)) {
                tapHaptic()
                bridge.sendKey(SpecialKey.MEDIA_NEXT)
            }
            QuickActionButton("🔉", Modifier.weight(1f)) {
                tapHaptic()
                bridge.sendKey(SpecialKey.VOLUME_DOWN)
            }
            QuickActionButton("🔊", Modifier.weight(1f)) {
                tapHaptic()
                bridge.sendKey(SpecialKey.VOLUME_UP)
            }
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(palette.pillCornerRadius))
                    .background(if (keyboardVisible) palette.accent else palette.surface)
                    .border(
                        1.dp,
                        if (keyboardVisible) palette.accent else palette.border,
                        RoundedCornerShape(palette.pillCornerRadius)
                    )
                    .clickable {
                        tapHaptic()
                        keyboardVisible = !keyboardVisible
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (keyboardVisible) "⌨ Hide" else "⌨ Keys",
                    color = if (keyboardVisible) palette.onAccent else palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Adaptive Workspace (Side-by-side when unfolded on Galaxy Z Fold, stacked when folded)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isUnfoldedWide = maxWidth >= 600.dp

            if (isUnfoldedWide && keyboardVisible) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TrackpadSurface(
                            settings = settings,
                            isCompact = false,
                            onMove = { dx, dy -> bridge.moveCursor(dx, dy) },
                            onScroll = { sx, sy -> bridge.scroll(sx, sy) },
                            onZoom = { dz -> bridge.zoom(dz) },
                            onClick = { btn -> bridge.click(btn) },
                            onMouseButton = { btn, down -> bridge.mouseButton(btn, down) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        MouseButtonsRow(
                            pillCornerRadius = palette.pillCornerRadius,
                            padCornerRadius = palette.padCornerRadius,
                            onTapHaptic = { tapHaptic() },
                            onMouseButton = { btn, down -> bridge.mouseButton(btn, down) }
                        )
                    }

                    KeyboardDeck(
                        hapticEnabled = settings.hapticFeedback,
                        onSendText = { text -> bridge.sendText(text) },
                        onSendSpecialKey = { key, mods -> bridge.sendKey(key, mods) },
                        onSendCharKey = { ch, mods -> bridge.sendCharKey(ch, mods) },
                        modifier = Modifier.weight(1.15f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TrackpadSurface(
                        settings = settings,
                        isCompact = keyboardVisible,
                        onMove = { dx, dy -> bridge.moveCursor(dx, dy) },
                        onScroll = { sx, sy -> bridge.scroll(sx, sy) },
                        onZoom = { dz -> bridge.zoom(dz) },
                        onClick = { btn -> bridge.click(btn) },
                        onMouseButton = { btn, down -> bridge.mouseButton(btn, down) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    AnimatedVisibility(
                        visible = !keyboardVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        MouseButtonsRow(
                            pillCornerRadius = palette.pillCornerRadius,
                            padCornerRadius = palette.padCornerRadius,
                            onTapHaptic = { tapHaptic() },
                            onMouseButton = { btn, down -> bridge.mouseButton(btn, down) }
                        )
                    }

                    AnimatedVisibility(
                        visible = keyboardVisible,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        KeyboardDeck(
                            hapticEnabled = settings.hapticFeedback,
                            onSendText = { text -> bridge.sendText(text) },
                            onSendSpecialKey = { key, mods -> bridge.sendKey(key, mods) },
                            onSendCharKey = { ch, mods -> bridge.sendCharKey(ch, mods) }
                        )
                    }
                }
            }
        }
    }

    if (showConnectionSheet) {
        ConnectionSheet(
            activeMode = activeMode,
            connectionState = connectionState,
            discoveredHosts = discoveredHosts,
            pairedBtDevices = pairedBtDevices,
            hasBluetoothPermissions = bridge.bluetooth.hasPermissions(),
            savedIp = settings.lastHostIp,
            savedPort = settings.lastHostPort,
            onDismiss = { showConnectionSheet = false },
            onSelectMode = { mode ->
                bridge.setMode(mode)
                onUpdateSettings(settings.copy(transportMode = mode))
            },
            onRefreshWifi = { bridge.startDiscovery() },
            onRefreshBluetooth = { bridge.bluetooth.refreshPairedDevices() },
            onConnectWifi = { ip, tcpPort, udpPort, name ->
                onUpdateSettings(
                    settings.copy(
                        lastHostIp = ip,
                        lastHostPort = tcpPort,
                        lastHostUdpPort = udpPort,
                        lastHostName = name,
                        transportMode = com.deskpad.app.model.TransportMode.WIFI
                    )
                )
                bridge.connectWifi(ip, tcpPort, udpPort, name)
            },
            onConnectBluetooth = { dev ->
                onUpdateSettings(
                    settings.copy(transportMode = com.deskpad.app.model.TransportMode.BLUETOOTH_HID)
                )
                bridge.connectBluetooth(dev)
            },
            onDisconnect = { bridge.disconnect() }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun ConnectionStatusPill(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val (dotColor, text) = when (state) {
        is ConnectionState.Connected -> {
            val latencyPart = state.latencyMs?.let { " • ${it}ms" } ?: ""
            palette.statusConnected to "${state.hostName}$latencyPart"
        }
        is ConnectionState.Connecting -> palette.accent to "Connecting to ${state.target}..."
        is ConnectionState.Discovering -> palette.accent to "Scanning Wi-Fi..."
        is ConnectionState.Error -> palette.accent to "Tap to Connect"
        ConnectionState.Disconnected -> palette.statusDisconnected to "Tap to Connect PC"
    }

    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (state is ConnectionState.Connected) "Switch" else "Link",
            color = palette.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(palette.pillCornerRadius))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(palette.pillCornerRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MouseButtonsRow(
    pillCornerRadius: Dp,
    padCornerRadius: Dp,
    onTapHaptic: () -> Unit,
    onMouseButton: (MouseButton, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MouseButtonPad(
            label = "Left Click",
            shape = RoundedCornerShape(
                topStart = pillCornerRadius,
                topEnd = 8.dp,
                bottomStart = padCornerRadius,
                bottomEnd = 8.dp
            ),
            modifier = Modifier.weight(1f),
            onPressChanged = { down ->
                if (down) onTapHaptic()
                onMouseButton(MouseButton.LEFT, down)
            }
        )
        MouseButtonPad(
            label = "•••",
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.width(54.dp),
            onPressChanged = { down ->
                if (down) onTapHaptic()
                onMouseButton(MouseButton.MIDDLE, down)
            }
        )
        MouseButtonPad(
            label = "Right Click",
            shape = RoundedCornerShape(
                topStart = 8.dp,
                topEnd = pillCornerRadius,
                bottomStart = 8.dp,
                bottomEnd = padCornerRadius
            ),
            modifier = Modifier.weight(1f),
            onPressChanged = { down ->
                if (down) onTapHaptic()
                onMouseButton(MouseButton.RIGHT, down)
            }
        )
    }
}

@Composable
private fun MouseButtonPad(
    label: String,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    val palette = LocalDeskPadPalette.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(if (pressed) palette.surfaceElevated else palette.surface)
            .border(1.dp, if (pressed) palette.accent else palette.border, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPressChanged(true)
                    waitForUpOrCancellation()
                    pressed = false
                    onPressChanged(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (pressed) palette.accent else palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
