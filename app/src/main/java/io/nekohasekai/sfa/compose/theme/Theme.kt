package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// LitEnergy VPN: монохромная тема (белые кнопки на чёрном фоне)
private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFFFFFF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF2C2C2E),
        onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFFD1D1D6),
        onSecondary = Color(0xFF000000),
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFFFFFFF),
        tertiary = Color(0xFF34C759),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFF8E8E93),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF111111),
        surfaceContainer = Color(0xFF161616),
        surfaceContainerHigh = Color(0xFF1C1C1E),
        surfaceContainerHighest = Color(0xFF2C2C2E),
        outline = Color(0xFF48484A),
        outlineVariant = Color(0xFF3A3A3C),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF000000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE5E5EA),
        onPrimaryContainer = Color(0xFF000000),
        secondary = Color(0xFF3A3A3C),
        secondaryContainer = Color(0xFFE5E5EA),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF248A3D),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF6C6C70),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F7F9),
        surfaceContainer = Color(0xFFF2F2F7),
        surfaceContainerHigh = Color(0xFFEBEBF0),
        surfaceContainerHighest = Color(0xFFE5E5EA),
    )

@Composable
fun Theme(
    // LitEnergy VPN: фирменная тёмная тема всегда
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
