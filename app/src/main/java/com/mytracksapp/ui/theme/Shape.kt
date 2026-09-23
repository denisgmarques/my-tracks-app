package com.mytracksapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii transcribed from `organic-styles.css`'s `--radius-sm/md/lg` tokens, mapped onto
 * Material3's small/medium/large [Shapes] roles.
 */
val MyTracksShapes = Shapes(
    small = RoundedCornerShape(8.dp), // --radius-sm
    medium = RoundedCornerShape(16.dp), // --radius-md
    large = RoundedCornerShape(28.dp), // --radius-lg
)

/**
 * The CSS's `.btn, .tag, .seg, .input { border-radius: 999px }` rule — a fully pill-shaped corner
 * radius. Material3's [Shapes] class has no "pill" slot, so this lives alongside it as its own
 * top-level constant for any composable that needs it directly (buttons, tags, the segmented time
 * bar, icon buttons).
 */
val PillShape = RoundedCornerShape(percent = 50)
