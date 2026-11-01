package com.editpro.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * True when the window is phone-sized.
 *
 * Both axes are checked on purpose: a phone held sideways is wide but short, so judging on width
 * alone would call it a tablet and leave every pane a sliver of vertical space.
 */
@Composable
fun rememberIsCompact(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp < 600 || configuration.screenHeightDp < 480
}

/**
 * Caps a dialog at most of the window height.
 *
 * Dialogs used fixed maximum heights, which is fine in portrait and clips on a landscape phone
 * where the window is only about 360dp tall.
 */
@Composable
fun rememberDialogMaxHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp * 0.86f

/** The three workspace panes. A tablet shows them together; a phone shows one at a time. */
enum class MobilePane(val label: String) {
    Library("Media"),
    Preview("Preview"),
    Inspector("Inspector")
}

/**
 * Segmented switcher for the compact layout.
 *
 * On a phone the three-pane workspace would leave roughly 120dp per column, which is unusable, so
 * the panes become tabs instead. The timeline stays visible underneath regardless — it is the
 * surface you are actually editing on.
 *
 * Targets are 44dp tall with generous horizontal padding rather than compact chips, because this is
 * the primary navigation on a touch device.
 */
@Composable
fun MobilePaneSwitcher(
    active: MobilePane,
    onSelect: (MobilePane) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalEditProPalette.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(palette.panel.copy(alpha = 0.92f))
            .border(0.5.dp, palette.line)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        MobilePane.entries.forEach { pane ->
            val selected = pane == active
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) palette.accent.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { onSelect(pane) }
            ) {
                Text(
                    text = pane.label,
                    color = if (selected) Color.White else palette.muted,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}
