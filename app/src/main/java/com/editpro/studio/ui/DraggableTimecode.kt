package com.editpro.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.editpro.studio.model.formatTimecode

/**
 * A timecode you can drag sideways to change its value.
 *
 * Typing a timecode on a tablet keyboard is miserable, and a slider is too coarse for frame-level
 * work, so the number itself becomes the control: drag right to advance, left to rewind. The caller
 * sets [secondsPerDp], which is what makes one component serve both a fine clip trim and a coarse
 * playhead scrub.
 *
 * The delta is reported per drag event rather than as an absolute value, because the caller owns the
 * clamping rules — a clip cannot start before zero, a trim cannot cross its own in-point.
 */
@Composable
fun DraggableTimecode(
    seconds: Double,
    onDelta: (Double) -> Unit,
    modifier: Modifier = Modifier,
    secondsPerDp: Double = 0.02,
    color: Color = LocalEditProPalette.current.accent,
    fontSize: TextUnit = 12.sp,
    background: Color = Color.Transparent,
    enabled: Boolean = true
) {
    var dragging by remember { mutableStateOf(false) }

    Text(
        text = formatTimecode(seconds),
        color = if (enabled) color else color.copy(alpha = 0.5f),
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (dragging) background.copy(alpha = 0.55f) else background)
            .then(
                if (enabled) {
                    Modifier.pointerInput(secondsPerDp) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val dp = dragAmount / density
                            if (dp != 0f) onDelta(dp * secondsPerDp)
                        }
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}
