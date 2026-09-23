package com.mytracksapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color tokens transcribed 1:1 from `design_handoff_my_tracks/organic-styles.css`'s `:root`
 * block. Names mirror the CSS custom properties exactly (`--color-accent-700` -> [Accent700]) so
 * this file can be diffed against the source of truth at a glance. The CSS defines a light theme
 * only (no `prefers-color-scheme: dark` overrides anywhere in the handoff), so [MyTracksColorScheme]
 * built from these in `Theme.kt` is intentionally the only color scheme this app uses — see that
 * file's doc for why dark mode is not offered.
 */

// --color-bg / --color-surface / --color-text
val ColorBg = Color(0xFFF5EAD8)
val ColorSurface = Color(0xFFEBDDC5)
val ColorText = Color(0xFF201E1D)

// --color-divider: color-mix(in srgb, #201e1d 16%, transparent)
val ColorDivider = Color(0x29201E1D)

// --color-neutral-100..900
val Neutral100 = Color(0xFFF9F4ED)
val Neutral200 = Color(0xFFEEE7DB)
val Neutral300 = Color(0xFFDCD3C4)
val Neutral400 = Color(0xFFC0B6A5)
val Neutral500 = Color(0xFFA19786)
val Neutral600 = Color(0xFF82796A)
val Neutral700 = Color(0xFF645C50)
val Neutral800 = Color(0xFF474238)
val Neutral900 = Color(0xFF2E2B25)

// --color-accent (terracotta) + ramps 100-900
val Accent100 = Color(0xFFFFF2EB)
val Accent200 = Color(0xFFFFE1D0)
val Accent300 = Color(0xFFFFC6A5)
val Accent400 = Color(0xFFF6A06B)
val Accent500 = Color(0xFFD67F48)
val Accent = Color(0xFFC67139) // --color-accent
val Accent600 = Color(0xFFB2622D)
val Accent700 = Color(0xFF8C491A)
val Accent800 = Color(0xFF643312)
val Accent900 = Color(0xFF402310)

// --color-accent-2 (sage) + ramps 100-900
val Accent2100 = Color(0xFFF0FAE1)
val Accent2200 = Color(0xFFE1EECC)
val Accent2300 = Color(0xFFCCDBB2)
val Accent2400 = Color(0xFFAEBF92)
val Accent2500 = Color(0xFF8FA073)
val Accent2 = Color(0xFF7A8A5E) // --color-accent-2
val Accent2600 = Color(0xFF728157)
val Accent2700 = Color(0xFF56633F)
val Accent2800 = Color(0xFF3D472B)
val Accent2900 = Color(0xFF272E1B)

/**
 * Destructive/error color — NOT defined anywhere in `organic-styles.css` (the design system has
 * no error/destructive token). [com.mytracksapp.ui.history.HistoryListScreen]'s delete
 * confirmation already leans on `MaterialTheme.colorScheme.error`/`errorContainer`, so this app
 * needs *a* semantic error color. Chosen as a muted brick-red that stays in the same warm/earthy
 * family as the accent ramp (rotated toward red rather than the CSS's orange-terracotta) so it
 * still reads as "belongs to this palette" rather than a jarring stock Material red.
 */
val ErrorRed = Color(0xFFAB3B2A)
val OnErrorCream = Color(0xFFFFF7F0)
val ErrorContainer = Color(0xFFF3D8CE)
val OnErrorContainer = Color(0xFF5A2116)
