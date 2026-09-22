package com.omarea.vtools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omarea.common.ui.ThemeMode

/**
 * Material 3 design tokens for Scene.
 *
 * All Compose screens should use [SceneTheme] and read colors/typography from [MaterialTheme].
 * Spacing tokens live in [SceneSpacing] so screens stay visually consistent and easy to modify.
 */
private val SceneBlue = Color(0xFF3D6FE0)
private val SceneBlueDark = Color(0xFF9FBBFF)
private val SceneTeal = Color(0xFF00696E)
private val SceneTealDark = Color(0xFF4CDADE)

val SceneLightColors = lightColorScheme(
    primary = SceneBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = SceneTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBEE),
    onSecondaryContainer = Color(0xFF002021),
    surface = Color(0xFFFAF9FD),
    surfaceVariant = Color(0xFFE1E2EC),
    background = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1B21),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
    error = Color(0xFFBA1A1A)
)

val SceneDarkColors = darkColorScheme(
    primary = SceneBlueDark,
    onPrimary = Color(0xFF002E6A),
    primaryContainer = Color(0xFF1B4596),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = SceneTealDark,
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF004F51),
    onSecondaryContainer = Color(0xFFB2EBEE),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF44464F),
    background = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    error = Color(0xFFFFB4AB)
)

/** Spacing tokens (dp) shared by all Scene screens. */
object SceneSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/**
 * Applies the Scene Material 3 theme.
 *
 * @param darkTheme when true the dark color scheme is used
 * @param dynamicColor when true (Android 12+) uses the system dynamic palette
 */
@Composable
fun SceneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SceneDarkColors
        else -> SceneLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/** Applies [SceneTheme] using the app's own theme mode (wallpaper/light/dark). */
@Composable
fun SceneTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    SceneTheme(darkTheme = mode.isDarkMode, content = content)
}
