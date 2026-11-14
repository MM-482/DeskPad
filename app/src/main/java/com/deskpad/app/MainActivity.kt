package com.deskpad.app

import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskpad.app.model.SettingsStore
import com.deskpad.app.network.InputBridge
import com.deskpad.app.ui.DeskPadScreen
import com.deskpad.app.ui.theme.DeskPadTheme

class MainActivity : ComponentActivity() {
    private val connectionModel by lazy { ViewModelProvider(this)[DeskPadConnectionViewModel::class.java] }
    private val bridge: InputBridge get() = connectionModel.bridge
    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

}

/** One bridge survives Activity recreation and closes only when this screen's owner is cleared. */
class DeskPadConnectionViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = InputBridge(application.applicationContext)
    override fun onCleared() {
        bridge.close()
        super.onCleared()
    }
}
