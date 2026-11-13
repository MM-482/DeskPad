package com.deskpad.app.model

import android.content.Context

enum class ThemeStyle(
    val id: String,
    val displayName: String,
    val tagline: String,
    val description: String,
    val isLight: Boolean
) {
    OBSIDIAN_GLASS(
        id = "obsidian",
        displayName = "Obsidian Glass",
        tagline = "OLED Stealth & Smoked Glass",
        description = "Pure OLED black canvas with frosted hairline borders and ice-cyan touch feedback.",
        isLight = false
    ),
    TACTILE_MONO(
        id = "tactile",
        displayName = "Tactile Mono",
        tagline = "Industrial Hardware Utility",
        description = "Matte charcoal surfaces, micro-dot trackpad matrix, and crimson precision cues.",
        isLight = false
    ),
    NORDIC_STUDIO(
        id = "nordic",
        displayName = "Nordic Studio",
        tagline = "Warm Alabaster & Daylight",
        description = "Bright Magic-Trackpad aesthetic with warm stone depth and calm emerald accents.",
        isLight = true
    ),
    CYBER_DECK(
        id = "cyber",
        displayName = "Cyber Deck",
        tagline = "Midnight Telemetry & Grid",
        description = "Deep indigo workspace with violet telemetry accents and precision reticle grid.",
        isLight = false
    ),
    MATERIAL_FLUID(
        id = "material",
        displayName = "Material Fluid",
        tagline = "Pixel Expressive Tonal UI",
        description = "Modern Android Material You squircle surfaces with lavender and mint containers.",
        isLight = false
    );

    companion object {
        val selectedThemes = listOf(TACTILE_MONO, CYBER_DECK)

        fun fromId(id: String?): ThemeStyle =
            selectedThemes.firstOrNull { it.id == id } ?: TACTILE_MONO
    }
}

data class AppSettings(
    val themeStyle: ThemeStyle = ThemeStyle.TACTILE_MONO,
    val cursorSensitivity: Float = 1.35f,
    val scrollSensitivity: Float = 1.2f,
    val naturalScrolling: Boolean = true,
    val tapToClick: Boolean = true,
    val hapticFeedback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val transportMode: TransportMode = TransportMode.WIFI,
    val lastHostIp: String = "",
    val lastHostPort: Int = ProtocolCodec.DEFAULT_TCP_PORT,
    val lastHostUdpPort: Int = ProtocolCodec.DEFAULT_UDP_PORT,
    val lastHostName: String = ""
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("deskpad_prefs", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeStyle = ThemeStyle.fromId(prefs.getString("theme_id", null)),
        cursorSensitivity = prefs.getFloat("cursor_sens", 1.35f),
        scrollSensitivity = prefs.getFloat("scroll_sens", 1.2f),
        naturalScrolling = prefs.getBoolean("natural_scroll", true),
        tapToClick = prefs.getBoolean("tap_to_click", true),
        hapticFeedback = prefs.getBoolean("haptic", true),
        keepScreenOn = prefs.getBoolean("keep_screen_on", true),
        transportMode = runCatching {
            TransportMode.valueOf(prefs.getString("transport_mode", TransportMode.WIFI.name)!!)
        }.getOrDefault(TransportMode.WIFI),
        lastHostIp = prefs.getString("last_ip", "") ?: "",
        lastHostPort = prefs.getInt("last_port", ProtocolCodec.DEFAULT_TCP_PORT),
        lastHostUdpPort = prefs.getInt("last_udp_port", ProtocolCodec.DEFAULT_UDP_PORT),
        lastHostName = prefs.getString("last_host_name", "") ?: ""
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("theme_id", settings.themeStyle.id)
            .putFloat("cursor_sens", settings.cursorSensitivity)
            .putFloat("scroll_sens", settings.scrollSensitivity)
            .putBoolean("natural_scroll", settings.naturalScrolling)
            .putBoolean("tap_to_click", settings.tapToClick)
            .putBoolean("haptic", settings.hapticFeedback)
            .putBoolean("keep_screen_on", settings.keepScreenOn)
            .putString("transport_mode", settings.transportMode.name)
            .putString("last_ip", settings.lastHostIp)
            .putInt("last_port", settings.lastHostPort)
            .putInt("last_udp_port", settings.lastHostUdpPort)
            .putString("last_host_name", settings.lastHostName)
            .apply()
    }
}
