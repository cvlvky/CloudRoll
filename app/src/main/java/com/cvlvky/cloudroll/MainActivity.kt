package com.cvlvky.cloudroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.player.ActiveVideo
import com.cvlvky.cloudroll.player.Music
import com.cvlvky.cloudroll.tg.Auth
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.ui.CloudRollTheme
import com.cvlvky.cloudroll.ui.DeepLinks
import com.cvlvky.cloudroll.ui.MainApp
import com.cvlvky.cloudroll.ui.SetupFlow
import com.cvlvky.cloudroll.ui.UpdatePrompt

class MainActivity : FragmentActivity() {

    /** True while the app lock is waiting for the fingerprint or screen lock. */
    private val locked = mutableStateOf(false)
    private var leftAt = 0L
    private lateinit var prompt: BiometricPrompt

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Tg.init(applicationContext)
        addOnPictureInPictureModeChangedListener { info -> ActiveVideo.inPip.value = info.isInPictureInPictureMode }
        if (savedInstanceState == null) openLink(intent)
        prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                }
            },
        )
        locked.value = Prefs.appLock.value
        setContent {
            CloudRollTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val auth by Tg.auth.collectAsState()
                    val isLocked by locked
                    when {
                        isLocked -> LockScreen { unlock() }
                        auth == Auth.Ready -> {
                            LaunchedEffect(Unit) {
                                Music.connect(applicationContext)
                                askForNotifications()
                            }
                            MainApp()
                        }
                        else -> SetupFlow(auth)
                    }
                    UpdatePrompt()
                }
            }
        }
        if (locked.value) unlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    /** cloudroll:// links, for example "Watch from Telegram" in ReelPulse. They open once the app is unlocked and signed in. */
    private fun openLink(intent: Intent?) {
        DeepLinks.parse(intent?.data)?.let { DeepLinks.pending.value = it }
    }

    /** Leaving the app during a video keeps it playing in a small window (Android 10 and 11; newer versions do it by themselves). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        ActiveVideo.enterIfPlaying(this)
    }

    override fun onStart() {
        super.onStart()
        Tg.tellNetworkType()
        // Locks again after a minute away.
        if (Prefs.appLock.value && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > 60_000) {
            locked.value = true
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || Prefs.askedNotifications.value) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Prefs.askedNotifications.value = true
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun unlock() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock on the phone any more: nothing to check against.
            locked.value = false
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CloudRoll")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("CloudRoll is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}
