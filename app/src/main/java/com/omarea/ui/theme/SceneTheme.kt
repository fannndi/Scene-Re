package com.omarea.ui.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Monochrome Miuix theme.
 *
 * One grayscale palette for the whole app: near-black accents on light,
 * near-white accents on dark, four-step neutral surfaces. The XML side gets
 * the same values from `values/colors.xml` + `styles.xml`.
 *
 * Responsibility: build the theme controller with the fixed palette.
 * Non-goals: per-screen styling.
 */
object SceneTheme {

    fun controller(dark: Boolean): ThemeController = ThemeController(
        colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        lightColors = monoLight(),
        darkColors = monoDark()
    )

    // ------------------------------------------------------------- light
    private fun monoLight(): Colors = lightColorScheme(
        primary = Color(0xFF111111),
        onPrimary = Color(0xFFFFFFFF),
        primaryVariant = Color(0xFF333333),
        onPrimaryVariant = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6E6E6),
        onPrimaryContainer = Color(0xFF111111),
        secondary = Color(0xFF6B6B6B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryVariant = Color(0xFF8A8A8A),
        onSecondaryVariant = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDEDED),
        onSecondaryContainer = Color(0xFF111111),
        secondaryContainerVariant = Color(0xFFF2F2F2),
        onSecondaryContainerVariant = Color(0xFF6B6B6B),
        tertiaryContainer = Color(0xFFEDEDED),
        onTertiaryContainer = Color(0xFF111111),
        background = Color(0xFFF2F2F2),
        onBackground = Color(0xFF111111),
        onBackgroundVariant = Color(0xFF6B6B6B),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF2F2F2),
        onSurfaceSecondary = Color(0xFF6B6B6B),
        onSurfaceVariantSummary = Color(0xFF8A8A8A),
        onSurfaceVariantActions = Color(0xFF111111),
        onSurfaceContainer = Color(0xFF111111),
        onSurfaceContainerVariant = Color(0xFF6B6B6B),
        error = Color(0xFFE11D48),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE4E6),
        onErrorContainer = Color(0xFF881337),
        outline = Color(0x33111111),
        dividerLine = Color(0x14000000),
        windowDimming = Color(0x66000000)
    )

    // -------------------------------------------------------------- dark
    private fun monoDark(): Colors = darkColorScheme(
        primary = Color(0xFFA78BFA),
        onPrimary = Color(0xFF111111),
        primaryVariant = Color(0xFF8B5CF6),
        onPrimaryVariant = Color(0xFF111111),
        primaryContainer = Color(0xFF2E1065),
        onPrimaryContainer = Color(0xFFDDD6FE),
        secondary = Color(0xFFBDBDBD),
        onSecondary = Color(0xFF111111),
        secondaryVariant = Color(0xFF9A9A9A),
        onSecondaryVariant = Color(0xFF111111),
        secondaryContainer = Color(0xFF2A2A2A),
        onSecondaryContainer = Color(0xFFF2F2F2),
        secondaryContainerVariant = Color(0xFF232323),
        onSecondaryContainerVariant = Color(0xFF9A9A9A),
        tertiaryContainer = Color(0xFF2A2A2A),
        onTertiaryContainer = Color(0xFFF2F2F2),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF2F2F2),
        onBackgroundVariant = Color(0xFF9A9A9A),
        surface = Color(0xFF1A1A1A),
        onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF232323),
        onSurfaceSecondary = Color(0xFF9A9A9A),
        onSurfaceVariantSummary = Color(0xFF8A8A8A),
        onSurfaceVariantActions = Color(0xFFF2F2F2),
        onSurfaceContainer = Color(0xFFF2F2F2),
        onSurfaceContainerVariant = Color(0xFF9A9A9A),
        error = Color(0xFFFB7185),
        onError = Color(0xFF111111),
        errorContainer = Color(0xFF4C0519),
        onErrorContainer = Color(0xFFFECDD3),
        outline = Color(0x33FFFFFF),
        dividerLine = Color(0x1FFFFFFF),
        windowDimming = Color(0x99000000)
    )
}
