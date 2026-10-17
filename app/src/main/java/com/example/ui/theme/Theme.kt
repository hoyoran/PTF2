package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

private val SlateDark = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2D3135),
    onSurfaceVariant = Color(0xFFC3C6CF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val SlateLight = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

fun getDynamicColorScheme(primaryHex: String, isDark: Boolean): androidx.compose.material3.ColorScheme {
    val primaryColor = try {
        Color(android.graphics.Color.parseColor(primaryHex))
    } catch (e: Exception) {
        Color(0xFF0061A4)
    }
    
    return if (isDark) {
        SlateDark.copy(
            primary = primaryColor,
            primaryContainer = Color(ColorUtils.blendARGB(primaryColor.toArgb(), 0xFF1A1C1E.toInt(), 0.3f)),
            onPrimary = if (ColorUtils.calculateLuminance(primaryColor.toArgb()) > 0.5) Color.Black else Color.White,
            onPrimaryContainer = Color.White
        )
    } else {
        SlateLight.copy(
            primary = primaryColor,
            primaryContainer = Color(ColorUtils.blendARGB(primaryColor.toArgb(), 0xFFFDFCFF.toInt(), 0.2f)),
            onPrimary = if (ColorUtils.calculateLuminance(primaryColor.toArgb()) > 0.5) Color.Black else Color.White,
            onPrimaryContainer = primaryColor
        )
    }
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    themeColorHex: String = "#0061A4",
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        themeColorHex == "SYSTEM" -> {
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) SlateDark else SlateLight
            }
        }
        themeColorHex != "#0061A4" -> {
            getDynamicColorScheme(themeColorHex, darkTheme)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SlateDark
        else -> SlateLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val activity = view.context as? android.app.Activity
            activity?.window?.let { window ->
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                
                val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(colorScheme.background.toArgb()) < 0.5
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }
}
