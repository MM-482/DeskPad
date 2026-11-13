package com.deskpad.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deskpad.app.model.SettingsStore
import com.deskpad.app.network.InputBridge
import com.deskpad.app.ui.DeskPadScreen
import com.deskpad.app.ui.theme.DeskPadTheme

class MainActivity : ComponentActivity() {
    private lateinit var bridge: InputBridge
    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = InputBridge(applicationContext)
        settingsStore = SettingsStore(applicationContext)

        val initialSettings = settingsStore.load()
        bridge.setMode(initialSettings.transportMode)

        setContent {
            var settings by remember { mutableStateOf(initialSettings) }

            LaunchedEffect(settings.themeStyle) {
                val style = if (settings.themeStyle.isLight) {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                } else {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            DeskPadTheme(style = settings.themeStyle) {
                DeskPadScreen(
                    bridge = bridge,
                    settings = settings,
                    onUpdateSettings = { updated ->
                        settings = updated
                        settingsStore.save(updated)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            bridge.disconnect()
        }
        super.onDestroy()
    }
}
