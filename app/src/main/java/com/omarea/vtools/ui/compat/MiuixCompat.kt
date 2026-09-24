package com.omarea.vtools.ui.compat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compatibility layer that replaces the third-party `top.yukonga.miuix` Compose theme.
 *
 * Why this exists
 * ---------------
 * miuix 0.8.8 declared `minCompileSdk=36` in its AAR metadata and transitively pinned
 * Compose 1.10.5 + Material3 1.4.0 (both `minCompileSdk=35`). That single dependency
 * forced `compileSdk` to 36 for the whole application, overriding the Compose BOM.
 *
 * This project targets Xiaomi/Snapdragon devices on Android 10-13, so `compileSdk` is
 * pinned to 34 — the true floor declared by the rest of the dependency set. Removing
 * miuix is what makes that possible.
 *
 * Rather than rewrite ~100 call sites, the same `MiuixTheme.textStyles.*` and
 * `MiuixTheme.colorScheme.*` accessors are re-provided here, backed by Material3. The
 * call sites therefore stay unchanged and there is exactly one file to maintain.
 *
 * Design notes
 * ------------
 * - `MiuixTheme` is an `object`, so `MiuixTheme.textStyles.footnote1` reads without a
 *   receiver at the call site. The values are exposed through composition locals that
 *   are read with `@ReadOnlyComposable`, so Compose can skip recomposition when the
 *   theme has not changed.
 * - The text styles mirror Material3 typography roles (body1 -> bodyLarge,
 *   footnote1 -> bodySmall, footnote2 -> labelSmall) rather than inventing new scales,
 *   which keeps the screens visually consistent with the rest of the app.
 * - `ThemeController` + `ColorSchemeMode` are kept as thin types so the existing
 *   `ThemeController(if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light)`
 *   expressions compile unchanged.
 */
object MiuixTheme {

    val colorScheme: MiuixColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalMiuixColorScheme.current

    val textStyles: MiuixTextStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalMiuixTextStyles.current
}

/**
 * Selects the colour scheme a [MiuixTheme] composition should use.
 *
 * Mirrors the miuix type this project used, so existing call sites keep working.
 */
enum class ColorSchemeMode {
    Light,
    Dark,
}

/**
 * Wrapper around [ColorSchemeMode] retained so `ThemeController(mode)` call sites do
 * not need to change. The controller carries no state beyond the chosen mode.
 */
class ThemeController(val mode: ColorSchemeMode)

/**
 * Subset of the miuix colour scheme that this project actually referenced.
 *
 * Only the members that were in use are reproduced; adding more would be guesswork
 * about a dependency that is no longer present.
 */
class MiuixColorScheme(
    val primary: Color,
    val onSurface: Color,
    val onSurfaceContainerVariant: Color,
)

/**
 * Subset of the miuix text styles that this project actually referenced.
 *
 * The original names are kept because they appear at ~70 call sites.
 */
class MiuixTextStyles(
    val body1: TextStyle,
    val footnote1: TextStyle,
    val footnote2: TextStyle,
)

private val LocalMiuixColorScheme = staticCompositionLocalOf {
    MiuixColorScheme(
        primary = Color.Unspecified,
        onSurface = Color.Unspecified,
        onSurfaceContainerVariant = Color.Unspecified,
    )
}

private val LocalMiuixTextStyles = staticCompositionLocalOf {
    MiuixTextStyles(
        body1 = TextStyle.Default,
        footnote1 = TextStyle.Default,
        footnote2 = TextStyle.Default,
    )
}

/**
 * Applies a Material3 theme and publishes the miuix-shaped accessors on top of it.
 *
 * Drop-in replacement for `MiuixTheme(controller = controller) { ... }`.
 *
 * @param controller the mode wrapper produced by the call site
 * @param content the composable subtree that reads [MiuixTheme]
 */
@Composable
fun MiuixTheme(
    controller: ThemeController,
    content: @Composable () -> Unit,
) {
    val dark = when (controller.mode) {
        ColorSchemeMode.Dark -> true
        ColorSchemeMode.Light -> false
    }
    MiuixTheme(dark = dark, content = content)
}

/**
 * Convenience overload that follows the system setting instead of an explicit mode.
 */
@Composable
fun MiuixTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    // Material3 resolves the concrete colours; the miuix-shaped view is then built from
    // the resolved values so both APIs report the same colours.
    val miuixColors = MiuixColorScheme(
        primary = scheme.primary,
        onSurface = scheme.onSurface,
        onSurfaceContainerVariant = scheme.onSurfaceVariant,
    )
    val miuixType = MiuixTextStyles(
        body1 = MaterialTheme.typography.bodyLarge,
        footnote1 = MaterialTheme.typography.bodySmall,
        footnote2 = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
    )

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalMiuixColorScheme provides miuixColors,
            LocalMiuixTextStyles provides miuixType,
            content = content,
        )
    }
}

/**
 * Drop-in replacement for the miuix `Card` composable.
 *
 * All three call sites in this project pass the same four parameters, so a single
 * signature covers them. miuix split corner radius and inner padding into their own
 * parameters rather than a `shape`; both are mapped onto the Material3 equivalent here
 * so the call sites do not have to change.
 *
 * @param cornerRadius the card's corner radius, applied as a Material3 shape
 * @param insideMargin padding applied inside the card
 * @param colors retained for source compatibility; see [CardDefaults]
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    insideMargin: PaddingValues = PaddingValues(12.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = colors.containerColor,
        contentColor = colors.contentColor,
    ) {
        Box(modifier = Modifier.padding(insideMargin)) {
            content()
        }
    }
}

/**
 * Resolved colours for a [Card], mirroring the miuix `CardColors` type.
 */
class CardColors(
    val containerColor: Color,
    val contentColor: Color,
)

/**
 * Entry points mirroring the miuix `CardDefaults` object.
 */
object CardDefaults {

    /**
     * The default card colours, taken from the active Material3 scheme so cards follow
     * the app theme instead of carrying a hardcoded surface colour.
     */
    @Composable
    @ReadOnlyComposable
    fun defaultColors(): CardColors {
        val scheme = MaterialTheme.colorScheme
        return CardColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        )
    }
}
