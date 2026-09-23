package com.mytracksapp.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material3 [androidx.compose.material3.ColorScheme] built from the tokens in `Color.kt`, mapped
 * onto Material3 roles:
 *  - `primary`/`onPrimary` = `--color-accent` / `--color-bg`, matching `.btn-primary { background:
 *    var(--color-accent); color: var(--color-bg) }`.
 *  - `secondary`/`onSecondary` = `--color-accent-2` / `--color-neutral-100` — the sage accent used
 *    for live-data (instant speed) and the "moving" segment; the CSS never puts text/icons
 *    directly on a solid `--color-accent-2` fill, so `onSecondary` is a judgment call, chosen for
 *    contrast rather than lifted from a specific rule.
 *  - `background`/`onBackground` = `--color-bg` / `--color-text`, `surface`/`onSurface` =
 *    `--color-surface` / `--color-text` (card backgrounds).
 *  - `error`/`onError`/`errorContainer`/`onErrorContainer`: no error token exists in the CSS — see
 *    `Color.kt`'s doc on [ErrorRed] for the reasoning behind this app's choice.
 *  - `surfaceTint` is pinned to [Accent] (rather than left at Material's default purple) so
 *    Material3's automatic tonal-elevation overlays on `Surface`/`Card` tint warm, not purple.
 *
 * Light-only, deliberately: `organic-styles.css` defines exactly one `:root` token block with no
 * `prefers-color-scheme: dark` (or any other) override anywhere in the design handoff — the
 * design system has no dark theme to reproduce, so [MyTracksTheme] always applies this scheme
 * regardless of the device's system dark-mode setting.
 */
private val MyTracksColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = ColorBg,
    primaryContainer = Accent100,
    onPrimaryContainer = Accent800,
    inversePrimary = Accent300,

    secondary = Accent2,
    onSecondary = Neutral100,
    secondaryContainer = Accent2100,
    onSecondaryContainer = Accent2800,

    tertiary = Accent2600,
    onTertiary = Neutral100,
    tertiaryContainer = Accent2200,
    onTertiaryContainer = Accent2900,

    background = ColorBg,
    onBackground = ColorText,

    surface = ColorSurface,
    onSurface = ColorText,
    surfaceVariant = Neutral200,
    onSurfaceVariant = Neutral700,
    surfaceTint = Accent,

    inverseSurface = Neutral900,
    inverseOnSurface = Neutral100,

    outline = Neutral400,
    outlineVariant = ColorDivider,

    error = ErrorRed,
    onError = OnErrorCream,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

/**
 * `--shadow-sm/md/lg` are soft, warm-tinted ("ink"-colored) shadows — `color-mix(in srgb,
 * #2e2b25 <alpha>%, transparent)`. Compose's [Modifier.shadow] takes separate ambient/spot tint
 * colors rather than a CSS-style single blurred color-mix, so both are set to the same
 * [Neutral900]-derived tint here to approximate the CSS's warm (not neutral-black) shadow.
 */
private val ShadowTint = Neutral900

/** `--shadow-sm` (`0 1px 2px`, ~14% tint). */
fun Modifier.elevSm(shape: Shape = CircleShape): Modifier = shadow(
    elevation = 2.dp,
    shape = shape,
    ambientColor = ShadowTint.copy(alpha = 0.14f),
    spotColor = ShadowTint.copy(alpha = 0.14f),
)

/** `--shadow-md` (`0 3px 10px`, ~16% tint) — used by the map card's expand/collapse button. */
fun Modifier.elevMd(shape: Shape = CircleShape): Modifier = shadow(
    elevation = 6.dp,
    shape = shape,
    ambientColor = ShadowTint.copy(alpha = 0.16f),
    spotColor = ShadowTint.copy(alpha = 0.16f),
)

/** `--shadow-lg` (`0 12px 32px`, ~22% tint). */
fun Modifier.elevLg(shape: Shape = CircleShape): Modifier = shadow(
    elevation = 16.dp,
    shape = shape,
    ambientColor = ShadowTint.copy(alpha = 0.22f),
    spotColor = ShadowTint.copy(alpha = 0.22f),
)

/**
 * App-wide Compose theme: [MyTracksColorScheme] + [MyTracksShapes] + [MyTracksTypography].
 * Replaces the bare `MaterialTheme { }` call that previously wrapped [com.mytracksapp.ui.navigation.MyTracksApp]
 * in `MainActivity.kt` with Material3's stock (purple) defaults.
 */
@Composable
fun MyTracksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MyTracksColorScheme,
        typography = MyTracksTypography,
        shapes = MyTracksShapes,
        content = content,
    )
}
