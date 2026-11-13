package com.deskpad.app.model

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

enum class TransportMode(val label: String) {
    WIFI("Wi-Fi LAN"),
    BLUETOOTH_HID("Bluetooth HID")
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Discovering : ConnectionState
    data class Connecting(val target: String) : ConnectionState
    data class Connected(
        val hostName: String,
        val detail: String,
        val latencyMs: Int? = null,
        val mode: TransportMode
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class DiscoveredHost(
    val name: String,
    val ip: String,
    val tcpPort: Int,
    val udpPort: Int,
    val os: String
)

enum class MouseButton(val wireCode: String) {
    LEFT("L"),
    RIGHT("R"),
    MIDDLE("M")
}

enum class SpecialKey(val label: String, val wireName: String) {
    ESC("Esc", "ESC"),
    TAB("Tab", "TAB"),
    ENTER("Enter", "ENTER"),
    BACKSPACE("⌫", "BACKSPACE"),
    DELETE("Del", "DELETE"),
    SPACE("Space", "SPACE"),
    UP("↑", "UP"),
    DOWN("↓", "DOWN"),
    LEFT("←", "LEFT"),
    RIGHT("→", "RIGHT"),
    HOME("Home", "HOME"),
    END("End", "END"),
    PAGE_UP("PgUp", "PGUP"),
    PAGE_DOWN("PgDn", "PGDN"),
    WIN("Win", "WIN"),
    CTRL("Ctrl", "CTRL"),
    ALT("Alt", "ALT"),
    SHIFT("Shift", "SHIFT"),
    CAPS_LOCK("Caps", "CAPS"),
    MEDIA_PLAY_PAUSE("⏯", "MEDIA_PLAY_PAUSE"),
    MEDIA_NEXT("⏭", "MEDIA_NEXT"),
    MEDIA_PREV("⏮", "MEDIA_PREV"),
    VOLUME_UP("🔊+", "VOL_UP"),
    VOLUME_DOWN("🔉-", "VOL_DOWN"),
    VOLUME_MUTE("🔇", "VOL_MUTE"),
    F1("F1", "F1"),
    F2("F2", "F2"),
    F3("F3", "F3"),
    F4("F4", "F4"),
    F5("F5", "F5"),
    F6("F6", "F6"),
    F7("F7", "F7"),
    F8("F8", "F8"),
    F9("F9", "F9"),
    F10("F10", "F10"),
    F11("F11", "F11"),
    F12("F12", "F12")
}

data class ModifierState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val win: Boolean = false
) {
    val hasAny: Boolean get() = ctrl || alt || shift || win

    fun toWireList(): List<String> = buildList {
        if (ctrl) add("CTRL")
        if (alt) add("ALT")
        if (shift) add("SHIFT")
        if (win) add("WIN")
    }
}

/**
 * Computes accelerated pointer deltas while preserving sub-pixel remainders across frames.
 */
class PointerMotionAccumulator {
    private var remX = 0f
    private var remY = 0f

    fun reset() {
        remX = 0f
        remY = 0f
    }

    fun accumulate(rawDx: Float, rawDy: Float, sensitivity: Float): Pair<Int, Int> {
        val speed = kotlin.math.hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        // Gentle non-linear acceleration: precise for small drags, swift for flicks
        val accel = (1f + (speed / 24f).pow(0.65f)).coerceIn(1f, 3.2f)
        val scaledX = rawDx * sensitivity * accel + remX
        val scaledY = rawDy * sensitivity * accel + remY

        val intX = scaledX.roundToInt()
        val intY = scaledY.roundToInt()
        remX = scaledX - intX
        remY = scaledY - intY
        return intX to intY
    }
}

/**
 * Serializes compact UDP datagrams and JSON-line TCP packets for the PC receiver.
 */
object ProtocolCodec {
    const val DEFAULT_DISCOVERY_PORT = 48910
    const val DEFAULT_UDP_PORT = 48911
    const val DEFAULT_TCP_PORT = 48912
    const val DISCOVER_QUERY = "DESKPAD_DISCOVER_V1"

    fun parseDiscoveryOffer(payload: String, senderIp: String): DiscoveredHost? {
        val parts = payload.trim().split("|")
        if (parts.size < 5 || parts[0] != "DESKPAD_OFFER_V1") return null
        val tcpPort = parts[3].toIntOrNull() ?: DEFAULT_TCP_PORT
        val udpPort = parts[4].toIntOrNull() ?: DEFAULT_UDP_PORT
        return DiscoveredHost(
            name = parts[1].ifBlank { senderIp },
            ip = senderIp,
            tcpPort = tcpPort,
            udpPort = udpPort,
            os = parts[2].ifBlank { "PC" }
        )
    }

    fun udpMovePacket(dx: Int, dy: Int): String = "M|$dx|$dy"
    fun udpScrollPacket(sx: Int, sy: Int): String = "S|$sx|$sy"
    fun udpZoomPacket(delta: Int): String = "Z|$delta"

    fun tcpPingPacket(timestampMs: Long): String =
        """{"t":"ping","ts":$timestampMs}"""

    fun tcpClickPacket(button: MouseButton, count: Int = 1): String =
        """{"t":"click","b":"${button.wireCode}","c":$count}"""

    fun tcpMouseButtonPacket(button: MouseButton, down: Boolean): String =
        """{"t":"btn","b":"${button.wireCode}","d":$down}"""

    fun tcpTextPacket(text: String): String =
        """{"t":"text","v":"${escapeJson(text)}"}"""

    fun tcpKeyPacket(keyName: String, modifiers: ModifierState = ModifierState()): String {
        val mods = modifiers.toWireList().joinToString(",") { "\"$it\"" }
        return """{"t":"key","k":"${escapeJson(keyName)}","m":[$mods]}"""
    }

    internal fun escapeJson(value: String): String = buildString(value.length + 4) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    fun clampInt8(value: Int): Byte {
        val clamped = if (abs(value) > 127) (127 * value.sign) else value
        return clamped.toByte()
    }
}
