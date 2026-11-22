package com.cvlvky.cloudroll.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.cvlvky.cloudroll.data.Prefs

private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF00897B),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101318),
    surface = Color(0xFF101318),
)

@Composable
fun isDark(): Boolean {
    val theme by Prefs.theme.flow.collectAsState()
    return when (theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun CloudRollTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val dynamic by Prefs.dynamicColor.flow.collectAsState()
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
