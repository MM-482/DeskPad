package com.deskpad.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.deskpad.app.network.PairingEnvelope
import com.deskpad.app.network.PairingEnvelopeImport
import com.deskpad.app.network.PairingEnvelopeResult
import com.deskpad.app.ui.PrivatePairingCaptureActivity
import com.deskpad.app.ui.theme.LocalDeskPadPalette
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Direct foreground QR pairing does not depend on Link, discovery, an address field, or a clipboard. */
@Composable
fun MainQrPairingButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    connectionGeneration: () -> Long,
    onPair: (PairingEnvelope, Long) -> Boolean
) {
    val palette = LocalDeskPadPalette.current
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var scanGeneration by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val options = remember {
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(PrivatePairingCaptureActivity::class.java)
            .setOrientationLocked(false).setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setPrompt("Point at the private QR code shown in DeskPad PC")
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val generation = scanGeneration
        scanGeneration = null
        scanning = false
        when (val imported = PairingEnvelopeImport.read(result.contents)) {
            PairingEnvelopeResult.Cancelled -> message = "Scan cancelled. Your saved pairing is unchanged."
            PairingEnvelopeResult.Invalid -> message = "Scan the current QR shown in DeskPad PC. This QR could not be used."
            is PairingEnvelopeResult.Ready -> {
                message = if (generation != null && onPair(imported.envelope, generation)) null
                else "The connection changed while scanning. Tap Scan QR to try again."
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && scanGeneration != null) {
            runCatching { scanner.launch(options) }.onFailure {
                scanning = false; scanGeneration = null
                message = "The camera couldn't open. Try again, or use manual pairing in Link."
            }
        } else {
            scanning = false; scanGeneration = null
            message = "Camera access is only needed to scan. You can try again or use manual pairing in Link."
        }
    }
    Column(modifier) {
        Button(
            onClick = {
                message = null
                scanGeneration = connectionGeneration()
                scanning = true
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { scanner.launch(options) }.onFailure {
                        scanning = false; scanGeneration = null
                        message = "The camera couldn't open. Try again, or use manual pairing in Link."
                    }
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            enabled = enabled && !scanning && hasCamera,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent)
        ) {
            Text(if (scanning) "Opening camera…" else "Scan QR", fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (hasCamera) "Scan only the QR on your PC. Scanning pairs it or replaces its saved pairing."
            else "No camera on this device. Use Link for manual pairing or Bluetooth.",
            fontSize = 10.sp, color = palette.textMuted
        )
        message?.let { Text(it, fontSize = 12.sp, color = palette.textPrimary) }
    }
}
