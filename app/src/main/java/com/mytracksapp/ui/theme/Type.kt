package com.mytracksapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mytracksapp.R

/**
 * `--font-heading`: "Caprasimo" — a single-weight (400) display font used for the "My Tracks"
 * title, section headings and (per the design) every button label (`.btn { font-family:
 * var(--font-heading) }`). Fetched as a static TTF from the google/fonts GitHub repo
 * (`ofl/caprasimo/Caprasimo-Regular.ttf`) since Caprasimo ships only one style upstream — no
 * [FontVariation] needed.
 */
val headingFontFamily = FontFamily(
    Font(R.font.caprasimo_regular, weight = FontWeight.Normal),
)

/**
 * `--font-body`: "Figtree" — used at weights 400/600/700 everywhere except the elements listed on
 * [headingFontFamily]. Figtree ships upstream ONLY as a variable font in the google/fonts repo
 * (`ofl/figtree/Figtree[wght].ttf`; no `ofl/figtree/static/` subfolder exists there, verified via
 * the GitHub contents API before falling back to this approach) — each weight below instantiates
 * the same variable TTF at a specific `wght` axis value via [FontVariation.weight], per Compose's
 * documented pattern for variable fonts (supported here since minSdk 29 > API 26).
 */
@OptIn(ExperimentalTextApi::class)
val bodyFontFamily = FontFamily(
    Font(
        R.font.figtree_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.figtree_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.figtree_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * Material3 [Typography] built from the two font families above. General body/label text in the
 * redesigned screens should read from this object (`MaterialTheme.typography.*`); the design's
 * one-off large numeric displays (the 48px distance figure, 24px card values, etc.) are applied as
 * direct [TextStyle] overrides at their call sites instead — see `TrackingScreen.kt`.
 *
 * Notably, `displayLarge`/`headlineLarge`/`titleLarge` use [headingFontFamily] (matching the CSS's
 * `h1..h4 { font-family: var(--font-heading) }` rule), and so does [Typography.labelLarge] — the
 * text style Material3's `Button`/`OutlinedButton`/`TextButton` apply to their content by default
 * — because `organic-styles.css`'s `.btn` class itself sets `font-family: var(--font-heading)`,
 * i.e. every button label in this design system is set in Caprasimo, not Figtree.
 */
val MyTracksTypography = Typography(
    displayLarge = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 48.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 32.sp),
    headlineLarge = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 25.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = headingFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)
