package com.deskpad.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpad.app.model.AppSettings
import com.deskpad.app.model.ThemeStyle
import com.deskpad.app.ui.theme.LocalDeskPadPalette
import com.deskpad.app.ui.theme.paletteFor
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onUpdateSettings: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalDeskPadPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Trackpad & Theme",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "Tactile Mono & Cyber Deck themes and pointer feel",
                    fontSize = 12.sp,
                    color = palette.textMuted
                )
            }

            // Selected 2-Theme Selector (Tactile Mono & Cyber Deck)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Theme",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemeStyle.selectedThemes.forEach { style ->
                        val themePreview = paletteFor(style)
                        val selected = settings.themeStyle == style
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) palette.surfaceElevated else palette.padSurface)
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) palette.accent else palette.border,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onUpdateSettings(settings.copy(themeStyle = style)) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(themePreview.background)
                                        .border(1.dp, themePreview.border, RoundedCornerShape(9.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(themePreview.accent)
                                    )
                                }
                                if (selected) {
                                    Text(
                                        text = "Active",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.accent
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = style.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = style.tagline,
                                    fontSize = 11.sp,
                                    color = palette.textMuted
                                )
                            }
                        }
                    }
                }
            }

            // Sensitivity Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.padSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pointer Speed", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${(settings.cursorSensitivity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        color = palette.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = settings.cursorSensitivity,
                    onValueChange = { onUpdateSettings(settings.copy(cursorSensitivity = it)) },
                    valueRange = 0.5f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Scroll Speed", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${(settings.scrollSensitivity * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        color = palette.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = settings.scrollSensitivity,
                    onValueChange = { onUpdateSettings(settings.copy(scrollSensitivity = it)) },
                    valueRange = 0.4f..2.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent
                    )
                )
            }

            // Toggles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.padSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                SettingToggleRow("Tap to Click", settings.tapToClick) {
                    onUpdateSettings(settings.copy(tapToClick = it))
                }
                SettingToggleRow("Natural Scrolling", settings.naturalScrolling) {
                    onUpdateSettings(settings.copy(naturalScrolling = it))
                }
                SettingToggleRow("Haptic Feedback", settings.hapticFeedback) {
                    onUpdateSettings(settings.copy(hapticFeedback = it))
                }
                SettingToggleRow("Keep Screen Awake", settings.keepScreenOn) {
                    onUpdateSettings(settings.copy(keepScreenOn = it))
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalDeskPadPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, color = palette.textPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent
            )
        )
    }
}
