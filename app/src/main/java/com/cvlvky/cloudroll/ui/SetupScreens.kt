package com.cvlvky.cloudroll.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Auth
import com.cvlvky.cloudroll.tg.Tg
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything before the gallery: API keys, then signing in to Telegram. */
@Composable
fun SetupFlow(auth: Auth) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        when (auth) {
            Auth.NeedKeys -> KeysScreen()
            Auth.Phone -> PhoneScreen()
            is Auth.Qr -> QrScreen(auth.link)
            is Auth.Code -> CodeScreen(auth)
            is Auth.Password -> PasswordScreen(auth.hint)
            Auth.Email -> EmailScreen()
            is Auth.EmailCode -> EmailCodeScreen(auth.pattern)
            is Auth.Blocked -> MessageScreen("Can't sign in yet", auth.reason)
            is Auth.Failed -> MessageScreen("Something went wrong", auth.reason)
            Auth.LoggingOut -> WaitScreen("Signing out…")
            Auth.Starting -> WaitScreen("Connecting to Telegram…")
            Auth.Ready -> WaitScreen("Opening…")
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun ErrorLine(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun KeysScreen() {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(Prefs.apiId.value.takeIf { it > 0 }?.toString().orEmpty()) }
    var hash by remember { mutableStateOf(Prefs.apiHash.value) }
    var error by remember { mutableStateOf<String?>(null) }
    Page(
        "Welcome to CloudRoll",
        "Your Telegram photos, videos and music in one gallery, plus free unlimited backup of your phone's photos.",
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("One-time setup: your own Telegram API key", fontWeight = FontWeight.SemiBold)
                Text("1. Open my.telegram.org and log in with your phone number.")
                Text("2. Tap API development tools.")
                Text("3. Fill in any app title and short name (for example CloudRoll), platform Android, then Create application.")
                Text("4. Copy the App api_id and App api_hash into the boxes below.")
                Text("They are free, stay only on this phone, and are never uploaded.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = { openUrl(ctx, "https://my.telegram.org/apps") }) {
            Icon(Icons.Filled.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Open my.telegram.org")
        }
        OutlinedTextField(
            value = id,
            onValueChange = { v -> id = v.filter { it.isDigit() }.take(12) },
            label = { Text("App api_id") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hash,
            onValueChange = { v -> hash = v.trim().take(64) },
            label = { Text("App api_hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(
            onClick = {
                val n = id.toIntOrNull()
                when {
                    n == null || n <= 0 -> error = "Enter the api_id: it is a number."
                    !Regex("^[0-9a-fA-F]{32}$").matches(hash) -> error = "The api_hash is 32 letters and numbers. Copy it again."
                    else -> {
                        error = null
                        Tg.saveKeys(n, hash)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
        Text(
            "CloudRoll is an unofficial app that uses the Telegram API. It is not made by Telegram.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneScreen() {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("Sign in to Telegram", "Enter the phone number of your Telegram account, with the country code.") {
        OutlinedTextField(
            value = phone,
            onValueChange = { v -> phone = "+" + v.filter { it.isDigit() }.take(16) },
            label = { Text("Phone number") },
            leadingIcon = { Icon(Icons.Filled.Phone, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    error = Tg.submitPhone(phone)
                    busy = false
                }
            },
            enabled = !busy && phone.length > 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send the code")
        }
        TextButton(onClick = { Tg.chooseQr(true) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Use a QR code instead")
        }
        TextButton(onClick = { Prefs.apiId.value = 0; Tg.restartClient() }, modifier = Modifier.fillMaxWidth()) {
            Text("Change the API key")
        }
    }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    val row = IntArray(size)
    for (y in 0 until size) {
        for (x in 0 until size) row[x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        bmp.setPixels(row, 0, size, 0, y, size, 1)
    }
    return bmp
}

@Composable
private fun QrScreen(link: String) {
    val qr = remember(link) { qrBitmap(link).asImageBitmap() }
    Page("Scan with Telegram", "On a phone or computer where Telegram is signed in: Settings → Devices → Link Desktop Device, then scan this code.") {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(qr, contentDescription = "Telegram login QR code", modifier = Modifier.size(260.dp))
        }
        Text("The code refreshes by itself.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { Tg.chooseQr(false) }, modifier = Modifier.fillMaxWidth()) { Text("Use the phone number instead") }
    }
}

@Composable
private fun CodeScreen(auth: Auth.Code) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableStateOf(30) }
    LaunchedEffect(auth) {
        resendIn = 30
        while (resendIn > 0) {
            delay(1000)
            resendIn--
        }
    }
    fun submit() {
        busy = true
        error = null
        scope.launch {
            error = Tg.submitCode(code)
            busy = false
        }
    }
    Page("Enter the code", "${auth.via}\nNumber: ${auth.phone}") {
        OutlinedTextField(
            value = code,
            onValueChange = { v ->
                code = v.trim().take(32)
                if (auth.length > 0 && code.length == auth.length && code.all { it.isDigit() } && !busy) submit()
            },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (auth.length > 0) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(onClick = { submit() }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
        if (auth.canResend) {
            TextButton(
                onClick = { scope.launch { error = Tg.resendCode() } },
                enabled = resendIn == 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (resendIn > 0) "Send it another way in $resendIn s" else "Send the code another way") }
        }
        TextButton(onClick = { Tg.restartClient() }, modifier = Modifier.fillMaxWidth()) { Text("Wrong number? Start again") }
    }
}

@Composable
private fun PasswordScreen(hint: String) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("Two-step verification", "This account has a cloud password. Enter it to finish signing in.") {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            supportingText = { if (hint.isNotBlank()) Text("Hint: $hint") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    error = Tg.submitPassword(password)
                    busy = false
                }
            },
            enabled = !busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
    }
}

@Composable
private fun EmailScreen() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Page("Add an email", "Telegram asks for an email address for this account. It sends a code there next.") {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(onClick = { scope.launch { error = Tg.submitEmail(email) } }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun EmailCodeScreen(pattern: String) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Page("Check your email", "Telegram sent a code to $pattern.") {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim() },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(error)
        Button(onClick = { scope.launch { error = Tg.submitEmailCode(code) } }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun MessageScreen(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { Tg.retry() }) { Text("Try again") }
        TextButton(onClick = { Prefs.apiId.value = 0; Tg.restartClient() }) { Text("Change the API key") }
    }
}

@Composable
private fun WaitScreen(text: String) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        slow = false
        delay(20_000)
        slow = true
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (slow) {
            Spacer(Modifier.height(16.dp))
            Text(
                "This is taking a while. Check the internet connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Tg.retry() }) { Text("Try again") }
                TextButton(onClick = { Prefs.apiId.value = 0; Tg.restartClient() }) { Text("Change the API key") }
            }
        }
    }
}
