package com.cvlvky.cloudroll

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.g000sha256.tdl.TdlClient

/** Toolchain check: every library the app uses, referenced once. */
@UnstableApi
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val qr = QRCodeWriter().encode("tg://login?token=x", BarcodeFormat.QR_CODE, 64, 64)
        val bio = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        val player = ExoPlayer.Builder(this).build()
        player.release()
        setContent {
            MaterialTheme {
                Column {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Text("CloudRoll · TDLib ${TdlClient.TDL_VERSION} · qr ${qr.width} · bio $bio")
                }
            }
        }
    }
}
